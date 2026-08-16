package com.example.agent.sensors

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.felhr.usbserial.UsbSerialDevice
import com.felhr.usbserial.UsbSerialInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.math.cos
import kotlin.math.sin

/** Owns USB permission, serial-device, parser, and watchdog lifecycles. */
class SerialManager(private val context: Context) {
    companion object {
        private const val TAG = "SerialManager"
        private const val SILICON_LABS_VENDOR_ID = 0x10C4
        private const val FTDI_VENDOR_ID = 0x0403
        private const val RPLIDAR_BAUD = 115200
        private const val MMWAVE_DATA_BAUD = 921600
        private val RPLIDAR_SCAN = byteArrayOf(0xA5.toByte(), 0x20)
    }

    data class MmwaveTarget(val x: Float, val y: Float, val z: Float, val velocity: Float)

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val permissionAction = "${context.packageName}.USB_PERMISSION"
    private val permissionIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(permissionAction).setPackage(context.packageName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lidarParser = RplidarStandardParser()
    private val mmwaveParser = TiMmwaveParser()
    private val permissionRequests = mutableSetOf<Int>()

    private val _lidarPoints = MutableSharedFlow<List<Float>>(extraBufferCapacity = 100)
    val lidarPoints: SharedFlow<List<Float>> = _lidarPoints.asSharedFlow()

    private val _mmwaveTargets = MutableSharedFlow<List<MmwaveTarget>>(extraBufferCapacity = 100)
    val mmwaveTargets: SharedFlow<List<MmwaveTarget>> = _mmwaveTargets.asSharedFlow()

    private var lidarDevice: UsbSerialDevice? = null
    private var lidarUsbDeviceId: Int? = null
    private var mmwaveDevice: UsbSerialDevice? = null
    private var mmwaveUsbDeviceId: Int? = null
    private var receiverRegistered = false
    private var watchdogJob: Job? = null
    private var lidarScanRequested = false
    @Volatile private var active = false
    @Volatile private var closed = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) {
            if (!active || closed) return
            val device = intent.usbDevice() ?: return
            when (intent.action) {
                permissionAction -> handlePermissionResult(
                    device,
                    intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false),
                )
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> requestOrOpen(device)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> closeDetachedDevice(device.deviceId)
            }
        }
    }

    /** Registers USB lifecycle handling and requests permission for attached adapters. */
    @Synchronized
    fun initDevices() {
        check(!closed) { "SerialManager is closed" }
        active = true
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(permissionAction)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            ContextCompat.registerReceiver(
                context,
                usbReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
        usbManager.deviceList.values.filter(::isRecognized).forEach(::requestOrOpen)
        if (watchdogJob == null) {
            watchdogJob = scope.launch {
                while (true) {
                    delay(3_000)
                    reconcileOpenDevices()
                }
            }
        }
    }

    /** Starts the RPLIDAR standard scan protocol (not express-scan capsules). */
    @Synchronized
    fun triggerLidarScan() {
        lidarScanRequested = true
        if (!active || closed) return
        lidarParser.reset()
        lidarDevice?.takeIf { it.isOpen }?.write(RPLIDAR_SCAN)
    }

    /**
     * The 921600-baud port is a binary data port, not the mmWave CLI port.
     * Configuration is rejected until a separately identified CLI interface
     * and a complete, hardware-approved profile are supplied.
     */
    fun configureMmwave(reduced: Boolean) {
        Log.w(
            TAG,
            "mmWave configuration not sent on data UART (requested reduced=$reduced)",
        )
    }

    @Synchronized
    private fun handlePermissionResult(device: UsbDevice, granted: Boolean) {
        permissionRequests.remove(device.deviceId)
        if (!active || closed) return
        if (granted) openRecognizedDevice(device)
        else Log.w(TAG, "USB permission denied for ${device.deviceName}")
    }

    @Synchronized
    private fun requestOrOpen(device: UsbDevice) {
        if (!isRecognized(device) || !active || closed) return
        if (usbManager.hasPermission(device)) {
            openRecognizedDevice(device)
        } else if (permissionRequests.add(device.deviceId)) {
            usbManager.requestPermission(device, permissionIntent)
        }
    }

    @Synchronized
    private fun openRecognizedDevice(device: UsbDevice) {
        if (!active || closed || !usbManager.hasPermission(device)) return
        when (device.vendorId) {
            SILICON_LABS_VENDOR_ID -> if (lidarDevice?.isOpen != true) openLidar(device)
            FTDI_VENDOR_ID -> if (mmwaveDevice?.isOpen != true) openMmwave(device)
        }
    }

    private fun openLidar(device: UsbDevice) {
        val serial = openSerial(device, RPLIDAR_BAUD) ?: return
        lidarDevice = serial
        lidarUsbDeviceId = device.deviceId
        lidarParser.reset()
        serial.read { bytes ->
            if (!active || closed) return@read
            val points = lidarParser.consume(bytes).flatMap { sample ->
                val radians = Math.toRadians(sample.angleDegrees.toDouble())
                val distance = sample.distanceMeters
                listOf(
                    (distance * cos(radians)).toFloat(),
                    (distance * sin(radians)).toFloat(),
                    0f,
                )
            }
            if (points.isNotEmpty()) scope.launch {
                if (active && !closed) _lidarPoints.emit(points)
            }
        }
        if (lidarScanRequested) serial.write(RPLIDAR_SCAN)
        Log.i(TAG, "RPLIDAR serial adapter opened")
    }

    private fun openMmwave(device: UsbDevice) {
        val serial = openSerial(device, MMWAVE_DATA_BAUD) ?: return
        mmwaveDevice = serial
        mmwaveUsbDeviceId = device.deviceId
        mmwaveParser.reset()
        serial.read { bytes ->
            if (!active || closed) return@read
            mmwaveParser.consume(bytes).forEach { frame ->
                val targets = frame.targets.map {
                    MmwaveTarget(it.x, it.y, it.z, it.velocity)
                }
                if (targets.isNotEmpty()) scope.launch {
                    if (active && !closed) _mmwaveTargets.emit(targets)
                }
            }
        }
        Log.i(TAG, "mmWave data serial adapter opened")
    }

    private fun openSerial(device: UsbDevice, baudRate: Int): UsbSerialDevice? {
        val connection = usbManager.openDevice(device) ?: run {
            Log.w(TAG, "Could not open ${device.deviceName} despite USB permission")
            return null
        }
        val serial = UsbSerialDevice.createUsbSerialDevice(device, connection) ?: run {
            connection.close()
            Log.w(TAG, "No serial driver for ${device.deviceName}")
            return null
        }
        if (!serial.open()) {
            serial.close()
            connection.close()
            Log.w(TAG, "Serial open failed for ${device.deviceName}")
            return null
        }
        serial.setBaudRate(baudRate)
        serial.setDataBits(UsbSerialInterface.DATA_BITS_8)
        serial.setStopBits(UsbSerialInterface.STOP_BITS_1)
        serial.setParity(UsbSerialInterface.PARITY_NONE)
        serial.setFlowControl(UsbSerialInterface.FLOW_CONTROL_OFF)
        return serial
    }

    @Synchronized
    private fun reconcileOpenDevices() {
        if (!active || closed) return
        val devices = usbManager.deviceList.values.associateBy { it.deviceId }
        if (lidarUsbDeviceId != null && lidarUsbDeviceId !in devices) closeLidar()
        if (mmwaveUsbDeviceId != null && mmwaveUsbDeviceId !in devices) closeMmwave()
        devices.values.filter(::isRecognized).forEach { device ->
            if (usbManager.hasPermission(device)) openRecognizedDevice(device)
        }
    }

    @Synchronized
    private fun closeDetachedDevice(deviceId: Int) {
        permissionRequests.remove(deviceId)
        if (lidarUsbDeviceId == deviceId) closeLidar()
        if (mmwaveUsbDeviceId == deviceId) closeMmwave()
    }

    private fun closeLidar() {
        try {
            lidarDevice?.close()
        } catch (_: IOException) {
        }
        lidarDevice = null
        lidarUsbDeviceId = null
        lidarParser.reset()
    }

    private fun closeMmwave() {
        try {
            mmwaveDevice?.close()
        } catch (_: IOException) {
        }
        mmwaveDevice = null
        mmwaveUsbDeviceId = null
        mmwaveParser.reset()
    }

    /** Stops I/O while retaining the manager for a later foreground resume. */
    @Synchronized
    fun stop() {
        if (closed) return
        stopIo()
    }

    @Synchronized
    fun close() {
        if (closed) return
        stopIo()
        closed = true
        scope.cancel()
    }

    private fun stopIo() {
        active = false
        watchdogJob?.cancel()
        watchdogJob = null
        permissionRequests.clear()
        closeLidar()
        closeMmwave()
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(usbReceiver)
            } catch (_: IllegalArgumentException) {
            }
            receiverRegistered = false
        }
    }

    private fun isRecognized(device: UsbDevice): Boolean =
        device.vendorId == SILICON_LABS_VENDOR_ID || device.vendorId == FTDI_VENDOR_ID

    private fun Intent.usbDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
}
