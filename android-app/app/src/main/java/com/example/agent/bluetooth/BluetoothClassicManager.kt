package com.example.agent.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID

/**
 * Classic Bluetooth Manager – SPP (Serial Port Profile), HID, Headset, A2DP-Erkennung.
 *
 * Für Zubehör wie:
 * - BT Fernbedienung via SPP (z.B. Gamepad im SPP-Modus)
 * - Externe Tastaturen / Presenter (HID wird vom OS behandelt, wir detektieren nur)
 * - BT Headsets für Sprachkommandos (Audio wird über AudioManager gelenkt)
 * - Legacy Sensoren (HC-05, HC-06)
 *
 * SPP UUID ist Standard: 00001101-0000-1000-8000-00805f9b34fb
 */
class BluetoothClassicManager(context: Context) {

    companion object {
        private const val TAG = "BTClassicManager"
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
        val KNOWN_DEVICE_CLASSES = mapOf(
            0x0400 to BluetoothAccessoryType.HEADSET, // Audio/Video Headset
            0x0500 to BluetoothAccessoryType.HEADSET,
            0x0540 to BluetoothAccessoryType.HEADSET,
            0x0800 to BluetoothAccessoryType.REMOTE_CONTROLLER, // Toy
            0x0508 to BluetoothAccessoryType.HID, // Keyboard classified as per BT spec
        )
    }

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothPermissions.getBluetoothAdapter(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _classicEvents = MutableSharedFlow<ClassicEvent>(extraBufferCapacity = 50)
    val classicEvents: SharedFlow<ClassicEvent> = _classicEvents.asSharedFlow()

    private val connectedSockets = mutableMapOf<String, BluetoothSocket>()

    sealed class ClassicEvent {
        data class DeviceFound(val accessory: BluetoothAccessory) : ClassicEvent()
        data class Connected(val mac: String, val type: BluetoothAccessoryType) : ClassicEvent()
        data class Disconnected(val mac: String) : ClassicEvent()
        data class DataReceived(val mac: String, val data: ByteArray, val asString: String) : ClassicEvent()
        data class Error(val mac: String, val message: String) : ClassicEvent()
    }

    @SuppressLint("MissingPermission")
    fun getPairedAccessories(): List<BluetoothAccessory> {
        val paired = bluetoothAdapter?.bondedDevices ?: return emptyList()
        return paired.mapNotNull { device ->
            try {
                val klass = device.bluetoothClass?.deviceClass ?: 0
                val inferredType = inferTypeFromClass(klass, device.name)
                BluetoothAccessory(
                    macAddress = device.address,
                    type = inferredType,
                    name = device.name ?: device.address,
                    isBonded = true,
                    isConnectable = true,
                )
            } catch (_: Exception) { null }
        }
    }

    private fun inferTypeFromClass(deviceClass: Int, name: String?): BluetoothAccessoryType {
        KNOWN_DEVICE_CLASSES[deviceClass]?.let { return it }
        val n = name?.lowercase() ?: ""
        return when {
            "headset" in n || "buds" in n || "airpod" in n -> BluetoothAccessoryType.HEADSET
            "keyboard" in n || "key" in n -> BluetoothAccessoryType.HID
            "mouse" in n -> BluetoothAccessoryType.HID
            "gamepad" in n || "controller" in n || "joystick" in n -> BluetoothAccessoryType.REMOTE_CONTROLLER
            "hc-05" in n || "hc-06" in n || "serial" in n -> BluetoothAccessoryType.CLASSIC_SPP
            else -> BluetoothAccessoryType.GENERIC_CLASSIC
        }
    }

    @SuppressLint("MissingPermission")
    fun connectSpp(device: BluetoothDevice): Boolean {
        val mac = device.address
        if (connectedSockets.containsKey(mac)) {
            Log.i(TAG, "SPP bereits verbunden: $mac")
            return true
        }
        scope.launch {
            try {
                val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                bluetoothAdapter?.cancelDiscovery()
                socket.connect()
                connectedSockets[mac] = socket
                _classicEvents.emit(ClassicEvent.Connected(mac, BluetoothAccessoryType.CLASSIC_SPP))
                Log.i(TAG, "SPP verbunden: $mac")

                // Lese-Loop
                val buffer = ByteArray(1024)
                val input = socket.inputStream
                while (true) {
                    val len = input.read(buffer)
                    if (len <= 0) break
                    val data = buffer.copyOf(len)
                    val str = String(data, Charsets.UTF_8).trim()
                    _classicEvents.emit(ClassicEvent.DataReceived(mac, data, str))
                }
            } catch (e: IOException) {
                Log.w(TAG, "SPP Fehler $mac: ${e.message}")
                _classicEvents.emit(ClassicEvent.Error(mac, e.message ?: "SPP error"))
                _classicEvents.emit(ClassicEvent.Disconnected(mac))
                connectedSockets.remove(mac)?.close()
            }
        }
        return true
    }

    fun disconnect(mac: String) {
        val sock = connectedSockets.remove(mac) ?: return
        try { sock.close() } catch (_: Exception) {}
        scope.launch { _classicEvents.emit(ClassicEvent.Disconnected(mac)) }
    }

    fun disconnectAll() {
        connectedSockets.keys.toList().forEach { disconnect(it) }
    }

    @SuppressLint("MissingPermission")
    fun sendSppData(mac: String, data: ByteArray): Boolean {
        val sock = connectedSockets[mac] ?: return false
        return try {
            sock.outputStream.write(data)
            true
        } catch (e: IOException) {
            Log.w(TAG, "SPP send fail $mac: ${e.message}")
            false
        }
    }

    /** Discovery Start – löscht alte Ergebnisse und startet suche nach Classic-Geräten */
    @SuppressLint("MissingPermission")
    fun startDiscovery(): Boolean {
        return try {
            if (bluetoothAdapter?.isDiscovering == true) bluetoothAdapter.cancelDiscovery()
            bluetoothAdapter?.startDiscovery() ?: false
        } catch (e: Exception) {
            Log.w(TAG, "Discovery start failed: ${e.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun cancelDiscovery() {
        try { bluetoothAdapter?.cancelDiscovery() } catch (_: Exception) {}
    }
}
