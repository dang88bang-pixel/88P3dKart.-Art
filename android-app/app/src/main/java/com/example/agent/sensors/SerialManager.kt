package com.example.agent.sensors

import android.content.Context
import android.hardware.usb.UsbManager
import com.felhr.usbserial.UsbSerialDevice
import com.felhr.usbserial.UsbSerialInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.math.cos
import kotlin.math.sin

/**
 * USB-Serial-Manager für RPLIDAR A1 (Silicon Labs, VID 0x10C4)
 * und TI IWR6843 mmWave (FTDI, VID 0x0403).
 */
class SerialManager(private val context: Context) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _lidarPoints = MutableSharedFlow<List<Float>>(extraBufferCapacity = 100)
    val lidarPoints: SharedFlow<List<Float>> = _lidarPoints.asSharedFlow()

    private val _mmwaveTargets = MutableSharedFlow<List<MmwaveTarget>>(extraBufferCapacity = 100)
    val mmwaveTargets: SharedFlow<List<MmwaveTarget>> = _mmwaveTargets.asSharedFlow()

    private var lidarDevice: UsbSerialDevice? = null
    private var mmwaveDevice: UsbSerialDevice? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    data class MmwaveTarget(val x: Float, val y: Float, val z: Float, val velocity: Float)

    fun initDevices() {
        usbManager.deviceList.values.forEach { device ->
            when (device.vendorId) {
                0x10C4 -> { // Silicon Labs → RPLIDAR
                    val conn = usbManager.openDevice(device) ?: return@forEach
                    lidarDevice = UsbSerialDevice.createUsbSerialDevice(device, conn)?.apply {
                        setBaudRate(115200)
                        setDataBits(UsbSerialInterface.DATA_BITS_8)
                        setStopBits(UsbSerialInterface.STOP_BITS_1)
                        setParity(UsbSerialInterface.PARITY_NONE)
                        open()
                        startLidarReader()
                    }
                }
                0x0403 -> { // FTDI → TI mmWave
                    val conn = usbManager.openDevice(device) ?: return@forEach
                    mmwaveDevice = UsbSerialDevice.createUsbSerialDevice(device, conn)?.apply {
                        setBaudRate(921600)
                        setDataBits(UsbSerialInterface.DATA_BITS_8)
                        setStopBits(UsbSerialInterface.STOP_BITS_1)
                        setParity(UsbSerialInterface.PARITY_NONE)
                        open()
                        startMmwaveReader()
                    }
                }
            }
        }
        startWatchdog()
    }

    /** EXPRESS_SCAN (0xA5 0x82) starten. */
    fun triggerLidarScan() {
        val cmd = byteArrayOf(0xA5.toByte(), 0x82.toByte(), 0x05, 0x00, 0x00, 0x00, 0x00, 0x00)
        lidarDevice?.write(cmd)
    }

    /** mmWave-Profil senden (Full / Reduced). */
    fun configureMmwave(reduced: Boolean) {
        val cfg = if (reduced)
            "profileCfg 0 60.6 30 10 62 0 0 53 1 128 2500 0 0 30\r\n"
        else
            "profileCfg 0 60.6 30 10 62 0 0 53 1 256 5000 0 0 30\r\n"
        mmwaveDevice?.write(cfg.toByteArray())
    }

    private fun startLidarReader() {
        lidarDevice?.read { data ->
            val points = parseLidarData(data)
            if (points.isNotEmpty()) scope.launch { _lidarPoints.emit(points) }
        }
    }

    /**
     * RPLIDAR A1 EXPRESS_SCAN: 5 Bytes pro Punkt
     * [Sync/S-Qualität(1) | Winkel_L(1) | Winkel_H(1) | Distanz_L(1) | Distanz_H(1)].
     */
    private fun parseLidarData(raw: ByteArray): List<Float> {
        val points = mutableListOf<Float>()
        var i = 0
        while (i + 4 < raw.size) {
            val quality = raw[i].toInt() and 0xFF
            val angle = ((raw[i + 1].toInt() and 0xFF) or ((raw[i + 2].toInt() and 0xFF) shl 8)).toFloat() / 64f
            val dist = ((raw[i + 3].toInt() and 0xFF) or ((raw[i + 4].toInt() and 0xFF) shl 8)).toFloat() / 1000f
            if (dist in 0.05f..40f) {
                val rad = Math.toRadians(angle.toDouble())
                points.add((dist * cos(rad)).toFloat())
                points.add((dist * sin(rad)).toFloat())
                points.add(0f) // Z=0 bei 2D-LiDAR
            }
            i += 5
        }
        return points
    }

    private fun startMmwaveReader() {
        mmwaveDevice?.read { data ->
            val targets = parseMmwaveData(data)
            if (targets.isNotEmpty()) scope.launch { _mmwaveTargets.emit(targets) }
        }
    }

    /**
     * TI IWR6843 TLV-Format (Magic 0x02010403 → Header → TLV-Längen).
     * Vereinfachte Extraktion; auf Hardware gegen das exakte TI-Protokoll validieren.
     */
    private fun parseMmwaveData(raw: ByteArray): List<MmwaveTarget> {
        val list = mutableListOf<MmwaveTarget>()
        // Platzhalter für das vollständige TLV-Parsing (Magic Word 0x0102).
        // Auf dem CT45P wird hier das TI-TLV (Targets, Doppler, SNR) dekodiert.
        return list
    }

    private fun startWatchdog() {
        scope.launch {
            while (true) {
                delay(3000)
                if (lidarDevice?.isOpen != true || mmwaveDevice?.isOpen != true) {
                    initDevices()
                }
            }
        }
    }

    fun close() {
        try { lidarDevice?.close() } catch (_: IOException) {}
        try { mmwaveDevice?.close() } catch (_: IOException) {}
    }
}
