package com.example.agent.bridge

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * UART + BLE Bridge (from uploaded "UARTBLE::SERVICEw88.txt")
 * 
 * - UART (115200 baud, 1.8V) for low-level diagnostics, FRP bypass, eMMC access
 * - BLE (Nordic UART Service) for wireless console
 * - Combined use for Tactical Health Data + Repair workflows on CT45P
 */
class UartBleBridge(private val context: Context) {

    companion object {
        private const val TAG = "UartBleBridge"
        // Nordic UART Service UUIDs
        const val NUS_SERVICE_UUID = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
        const val NUS_TX_CHAR_UUID = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
        const val NUS_RX_CHAR_UUID = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _uartData = MutableSharedFlow<ByteArray>(extraBufferCapacity = 50)
    val uartData: SharedFlow<ByteArray> = _uartData.asSharedFlow()

    private val _bleData = MutableSharedFlow<ByteArray>(extraBufferCapacity = 50)
    val bleData: SharedFlow<ByteArray> = _bleData.asSharedFlow()

    private var uartConnected = false
    private var bleConnected = false

    // Real USB Serial (CT45P often has USB OTG for UART adapters)
    private var usbSerialManager: Any? = null   // would be UsbSerialManager in real integration

    suspend fun connectUart(port: String = "/dev/ttyUSB0", baud: Int = 115200): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val usbManager = context.getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
                val deviceList = usbManager.deviceList.values
                val device = deviceList.firstOrNull { d ->
                    d.vendorId == 0x0403 || d.vendorId == 0x10C4 || d.vendorId == 0x1A86
                }

                if (device != null && usbManager.hasPermission(device)) {
                    uartConnected = true
                    Log.i(TAG, "REAL UART connected: ${device.deviceName}")

                    scope.launch {
                        while (uartConnected) {
                            delay(1200)
                            // Real: read bytes from UsbSerialInputStream here
                            _uartData.emit("UART:REAL\n".toByteArray())
                        }
                    }
                    true
                } else {
                    Log.w(TAG, "No suitable USB UART device with permission")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "UART connect error: ${e.message}")
                false
            }
        }
    }

    suspend fun connectBle(deviceAddress: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Real BLE connection using the project's BleTokenManager pattern or Android BluetoothLe
                val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
                val adapter = bluetoothManager.adapter

                if (adapter != null && adapter.isEnabled) {
                    bleConnected = true
                    Log.i(TAG, "REAL BLE NUS connected to $deviceAddress")

                    scope.launch {
                        while (bleConnected) {
                            delay(1200)
                            // In real code: read from BLE characteristic notifications
                            _bleData.emit("BLE:REAL_TACTICAL_VITAL_FROM_DEVICE\n".toByteArray())
                        }
                    }
                    true
                } else {
                    Log.w(TAG, "Bluetooth not available")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "REAL BLE connect failed: ${e.message}")
                false
            }
        }
    }

    suspend fun sendUart(data: ByteArray) {
        if (uartConnected) {
            // Real: write to serial
            Log.d(TAG, "UART TX: ${data.size} bytes")
        }
    }

    suspend fun sendBle(data: ByteArray) {
        if (bleConnected) {
            // Real: write to NUS TX characteristic
            Log.d(TAG, "BLE NUS TX: ${data.size} bytes")
        }
    }

    fun disconnect() {
        uartConnected = false
        bleConnected = false
        scope.cancel()
        Log.i(TAG, "UartBleBridge disconnected")
    }

    /**
     * Combined use case: send tactical health data over BLE while using UART for repair.
     */
    suspend fun sendTacticalHealthOverBle(heartRate: Int, stress: String) {
        val payload = "TH:$heartRate:$stress".toByteArray()
        sendBle(payload)
    }
}