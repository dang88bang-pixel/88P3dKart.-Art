package com.example.agent.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
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
import java.util.UUID

/**
 * GATT-Client für Bluetooth-Zubehör.
 *
 * Behandelt:
 * - Verbindung, Service-Discovery
 * - Battery Level, Device Info, Env Sensing, Heart Rate Notifications
 * - Custom 3dxAgent Service (0x8d81e7c0...) mit Data/Config/Command
 * - DFU-Trigger (OTA)
 */
class GattServiceManager(private val context: Context) {

    companion object {
        private const val TAG = "GattServiceManager"
        private const val CCC_DESCRIPTOR = "00002902-0000-1000-8000-00805f9b34fb"
    }

    data class GattDataEvent(
        val mac: String,
        val type: String,
        val payload: Map<String, Any?>,
        val raw: ByteArray? = null,
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val gattMap = mutableMapOf<String, BluetoothGatt>()

    private val _dataEvents = MutableSharedFlow<GattDataEvent>(extraBufferCapacity = 100)
    val dataEvents: SharedFlow<GattDataEvent> = _dataEvents.asSharedFlow()

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice): BluetoothGatt? {
        val mac = device.address
        if (gattMap.containsKey(mac)) {
            Log.i(TAG, "Bereits verbunden / verbindet: $mac")
            return gattMap[mac]
        }
        Log.i(TAG, "Verbinde GATT: $mac")
        val gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        gattMap[mac] = gatt
        return gatt
    }

    @SuppressLint("MissingPermission")
    fun disconnect(mac: String) {
        val gatt = gattMap[mac] ?: return
        try {
            gatt.disconnect()
            gatt.close()
        } catch (_: Exception) {}
        gattMap.remove(mac)
        Log.i(TAG, "GATT getrennt: $mac")
    }

    fun disconnectAll() {
        gattMap.keys.toList().forEach { disconnect(it) }
    }

    // ─── Callback ──────────────────────────────────────
    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val mac = gatt.device.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "GATT connected $mac – discover")
                gatt.discoverServices()
                scope.launch {
                    _dataEvents.emit(GattDataEvent(mac, "connected", mapOf("status" to status)))
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "GATT disconnected $mac status=$status")
                scope.launch {
                    _dataEvents.emit(GattDataEvent(mac, "disconnected", mapOf("status" to status)))
                }
                try { gatt.close() } catch (_: Exception) {}
                gattMap.remove(mac)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val mac = gatt.device.address
            Log.i(TAG, "Services discovered $mac count=${gatt.services.size}")
            scope.launch {
                // Battery
                readBatteryLevel(gatt)
                readDeviceInfo(gatt)
                // Custom 3dxAgent Service
                setupCustomServiceNotifications(gatt)
                // Standard sensor services
                setupHeartRateNotification(gatt)
                setupEnvSensingNotifications(gatt)
                delay(200)
                _dataEvents.emit(GattDataEvent(mac, "services_discovered", mapOf("count" to gatt.services.size)))
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            handleCharacteristic(gatt.device.address, characteristic)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            handleCharacteristic(gatt.device.address, characteristic)
        }
    }

    // ─── Characteristic Handling ──────────────────────
    private fun handleCharacteristic(mac: String, c: BluetoothGattCharacteristic) {
        when (c.uuid.toString().lowercase()) {
            GattUuids.BATTERY_LEVEL -> {
                val level = c.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0)
                emitData(mac, "battery", mapOf("battery" to level))
            }
            GattUuids.FIRMWARE_REV -> {
                val fw = c.getStringValue(0)
                emitData(mac, "firmware", mapOf("firmware_version" to fw))
            }
            GattUuids.TEMPERATURE_CHAR -> {
                val temp = c.getIntValue(BluetoothGattCharacteristic.FORMAT_SINT16, 0) / 100f
                emitData(mac, "environment", mapOf("temperature_c" to temp))
            }
            GattUuids.HUMIDITY_CHAR -> {
                val hum = c.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT16, 0) / 100f
                emitData(mac, "environment", mapOf("humidity_pct" to hum))
            }
            GattUuids.HEART_RATE_MEASUREMENT -> {
                val flag = c.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0)
                val hrFormat = if ((flag and 0x01) != 0) BluetoothGattCharacteristic.FORMAT_UINT16 else BluetoothGattCharacteristic.FORMAT_UINT8
                val hr = c.getIntValue(hrFormat, 1)
                emitData(mac, "wearable", mapOf("heart_rate_bpm" to hr))
            }
            GattUuids.CUSTOM_DATA_CHAR -> {
                val raw = c.value
                val parsed = parseCustomDataChar(raw)
                emitData(mac, "custom", parsed, raw)
            }
            else -> {
                // Unbekannte Charakteristik → raw forward
                emitData(mac, "raw_${c.uuid}", mapOf("hex" to c.value.joinToString("") { "%02x".format(it) }), c.value)
            }
        }
    }

    private fun parseCustomDataChar(data: ByteArray?): Map<String, Any?> {
        if (data == null || data.size < 4) return emptyMap()
        return try {
            val type = data[0].toInt() and 0xFF
            val payload = data.sliceArray(1 until data.size)
            when (type) {
                0x01 -> mapOf(
                    "accel_x" to readInt16(payload, 0) / 1000f,
                    "accel_y" to readInt16(payload, 2) / 1000f,
                    "accel_z" to readInt16(payload, 4) / 1000f,
                )
                0x02 -> mapOf(
                    "temperature_c" to readInt16(payload, 0) / 100f,
                    "humidity_pct" to payload.getOrNull(2)?.toInt()?.and(0xFF),
                    "pressure_hpa" to readInt16(payload, 3) / 10f,
                )
                0x03 -> mapOf(
                    "heart_rate_bpm" to payload.getOrNull(0)?.toInt()?.and(0xFF),
                    "steps" to readUInt16(payload, 1),
                )
                0x04 -> mapOf(
                    "button_state" to (payload.getOrNull(0)?.toInt()?.and(0xFF) ?: 0),
                    "joystick_x" to (payload.getOrNull(1)?.toInt()?.toFloat()?.div(127f) ?: 0f),
                    "joystick_y" to (payload.getOrNull(2)?.toInt()?.toFloat()?.div(127f) ?: 0f),
                )
                else -> mapOf("custom_type" to type, "hex" to payload.joinToString("") { "%02x".format(it) })
            }
        } catch (e: Exception) {
            mapOf("error" to e.message, "hex" to (data.joinToString("") { "%02x".format(it) }))
        }
    }

    @SuppressLint("MissingPermission")
    private fun readBatteryLevel(gatt: BluetoothGatt) {
        gatt.getService(UUID.fromString(GattUuids.BATTERY_SERVICE))
            ?.getCharacteristic(UUID.fromString(GattUuids.BATTERY_LEVEL))?.let {
                gatt.readCharacteristic(it)
            }
    }

    @SuppressLint("MissingPermission")
    private fun readDeviceInfo(gatt: BluetoothGatt) {
        gatt.getService(UUID.fromString(GattUuids.DEVICE_INFO))
            ?.getCharacteristic(UUID.fromString(GattUuids.FIRMWARE_REV))?.let {
                gatt.readCharacteristic(it)
            }
    }

    @SuppressLint("MissingPermission")
    private fun setupHeartRateNotification(gatt: BluetoothGatt) {
        val svc = gatt.getService(UUID.fromString(GattUuids.HEART_RATE_SERVICE)) ?: return
        val ch = svc.getCharacteristic(UUID.fromString(GattUuids.HEART_RATE_MEASUREMENT)) ?: return
        gatt.setCharacteristicNotification(ch, true)
        ch.getDescriptor(UUID.fromString(CCC_DESCRIPTOR))?.let { desc ->
            desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(desc)
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupEnvSensingNotifications(gatt: BluetoothGatt) {
        val svc = gatt.getService(UUID.fromString(GattUuids.ENV_SENSING)) ?: return
        listOf(GattUuids.TEMPERATURE_CHAR, GattUuids.HUMIDITY_CHAR).forEach { charUuid ->
            val ch = svc.getCharacteristic(UUID.fromString(charUuid)) ?: return@forEach
            gatt.setCharacteristicNotification(ch, true)
            ch.getDescriptor(UUID.fromString(CCC_DESCRIPTOR))?.let { desc ->
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(desc)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupCustomServiceNotifications(gatt: BluetoothGatt) {
        val svc = gatt.getService(UUID.fromString(GattUuids.CUSTOM_3DX_SERVICE)) ?: return
        val dataChar = svc.getCharacteristic(UUID.fromString(GattUuids.CUSTOM_DATA_CHAR)) ?: return
        gatt.setCharacteristicNotification(dataChar, true)
        dataChar.getDescriptor(UUID.fromString(CCC_DESCRIPTOR))?.let { desc ->
            desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(desc)
        }
    }

    @SuppressLint("MissingPermission")
    fun writeConfig(mac: String, jsonConfig: String): Boolean {
        val gatt = gattMap[mac] ?: return false
        val char = gatt.getService(UUID.fromString(GattUuids.CUSTOM_3DX_SERVICE))
            ?.getCharacteristic(UUID.fromString(GattUuids.CUSTOM_CONFIG_CHAR)) ?: return false
        char.value = jsonConfig.toByteArray(Charsets.UTF_8)
        return gatt.writeCharacteristic(char)
    }

    @SuppressLint("MissingPermission")
    fun sendCommand(mac: String, cmd: ByteArray): Boolean {
        val gatt = gattMap[mac] ?: return false
        val char = gatt.getService(UUID.fromString(GattUuids.CUSTOM_3DX_SERVICE))
            ?.getCharacteristic(UUID.fromString(GattUuids.CUSTOM_COMMAND_CHAR)) ?: return false
        char.value = cmd
        return gatt.writeCharacteristic(char)
    }

    private fun emitData(mac: String, type: String, payload: Map<String, Any?>, raw: ByteArray? = null) {
        scope.launch {
            _dataEvents.emit(GattDataEvent(mac, type, payload, raw))
        }
    }

    private fun readInt16(data: ByteArray, off: Int): Short {
        if (off + 1 >= data.size) return 0
        return ((data[off].toInt() and 0xFF) or ((data[off + 1].toInt() and 0xFF) shl 8)).toShort()
    }

    private fun readUInt16(data: ByteArray, off: Int): Int {
        if (off + 1 >= data.size) return 0
        return (data[off].toInt() and 0xFF) or ((data[off + 1].toInt() and 0xFF) shl 8)
    }
}
