package com.example.agent.bluetooth

import com.example.agent.network.ClientCapabilities
import com.example.agent.network.ClientRegistration
import com.example.agent.network.ClientStatus
import com.example.agent.network.ClientType
import com.example.agent.network.SensorType

/**
 * Repräsentiert ein physisches Bluetooth-Zubehörgerät im 3dxAgent Ökosystem.
 *
 * - Wird aus BLE Advertisements + GATT gelesen und laufend aktualisiert
 * - Mappt auf ClientRegistry (Token → TOKEN, Wearable → WEARABLE, Sensor → SENSOR, etc.)
 */
data class BluetoothAccessory(
    val macAddress: String,
    val type: BluetoothAccessoryType,
    var name: String = "Unknown",
    var rssi: Int = -100,
    var txPower: Int? = null,
    var batteryLevel: Int = 100,
    var isConnectable: Boolean = false,
    var isBonded: Boolean = false,
    var isConnected: Boolean = false,
    var lastSeenMs: Long = System.currentTimeMillis(),
    var firmwareVersion: String? = null,
    var manufacturerData: ByteArray? = null,
    var protocolVersion: Int = 1,

    // Sensorik – je nach Typ befüllt
    var accelX: Float = 0f,
    var accelY: Float = 0f,
    var accelZ: Float = 0f,
    var gyroX: Float? = null,
    var gyroY: Float? = null,
    var gyroZ: Float? = null,
    var temperatureC: Float? = null,
    var humidityPct: Float? = null,
    var pressureHpa: Float? = null,
    var airQualityPpm: Float? = null,
    var lightLux: Float? = null,
    var heartRateBpm: Int? = null,
    var steps: Int? = null,
    var heartRateVariabilityMs: Int? = null,

    // Position / Beacon
    var iBeaconUuid: String? = null,
    var iBeaconMajor: Int? = null,
    var iBeaconMinor: Int? = null,
    var eddystoneUrl: String? = null,
    var eddystoneNamespace: String? = null,
    var eddystoneInstance: String? = null,

    // Remote / Controller
    var buttonState: Int = 0,
    var joystickX: Float? = null,
    var joystickY: Float? = null,

    // Flags / Zustand
    var flags: Int = 0,
    var estimatedDistanceM: Float? = null,
    var dataQuality: Float = 0.9f,
) {
    val isMoving: Boolean get() = (flags and AccessoryFlags.MOVING) != 0
    val isButtonPressed: Boolean get() = (flags and AccessoryFlags.BUTTON_PRESSED) != 0
    val isLowBattery: Boolean get() = (flags and AccessoryFlags.LOW_BATTERY) != 0
    val isSosActive: Boolean get() = (flags and AccessoryFlags.SOS) != 0
    val isFallDetected: Boolean get() = (flags and AccessoryFlags.FALL_DETECTED) != 0

    val ageMs: Long get() = System.currentTimeMillis() - lastSeenMs
    val isExpired: Boolean get() = ageMs > 30_000 // 30s ohne Update → als offline

    /** In ClientRegistry abbilden für einheitliches Health/Recovery-System */
    fun toClientRegistration(apiKey: String = "ble-${macAddress.replace(":", "")}"): ClientRegistration {
        val clientType = type.toClientType()
        val sensorTypes = type.toSensorTypes()
        return ClientRegistration(
            clientId = macAddress,
            type = clientType,
            capabilities = ClientCapabilities(
                deviceType = clientType,
                sensorTypes = sensorTypes,
                maxFrequency = type.defaultFrequencyHz,
                batteryPowered = type.isBatteryPowered,
                hasPositioning = type in setOf(
                    BluetoothAccessoryType.ASSET_TAG,
                    BluetoothAccessoryType.TOKEN_CLASSIC,
                    BluetoothAccessoryType.TOKEN_PRO
                ),
                hasIMU = sensorTypes.contains(SensorType.IMU),
                hasBLE = true,
                supportsMesh = type == BluetoothAccessoryType.GATEWAY_BRIDGE,
                supportsSecureConnection = type.supportsGatt,
            ),
            apiKey = apiKey,
            allowedTopics = listOf("ble/tokens/${macAddress}", "bluetooth/accessories/${macAddress}"),
            batteryLevel = batteryLevel,
            dataQuality = dataQuality,
            status = if (isExpired) ClientStatus.OFFLINE else ClientStatus.ONLINE,
            lastSeen = lastSeenMs,
        )
    }

    fun toSignalPayload(): Map<String, Any?> = mutableMapOf<String, Any?>(
        "mac" to macAddress,
        "type" to type.name,
        "name" to name,
        "rssi" to rssi,
        "battery" to batteryLevel,
        "tx_power" to txPower,
        "distance_m" to estimatedDistanceM,
        "protocol_version" to protocolVersion,
        "flags" to flags,
        "last_seen" to lastSeenMs,
        "connected" to isConnected,
        "bonded" to isBonded,
    ).apply {
        if (accelX != 0f || accelY != 0f || accelZ != 0f) {
            put("accel_x", accelX); put("accel_y", accelY); put("accel_z", accelZ)
        }
        temperatureC?.let { put("temperature_c", it) }
        humidityPct?.let { put("humidity_pct", it) }
        pressureHpa?.let { put("pressure_hpa", it) }
        airQualityPpm?.let { put("air_quality_ppm", it) }
        lightLux?.let { put("light_lux", it) }
        heartRateBpm?.let { put("heart_rate_bpm", it) }
        steps?.let { put("steps", it) }
        iBeaconUuid?.let { put("ibeacon_uuid", it) }
        iBeaconMajor?.let { put("ibeacon_major", it) }
        iBeaconMinor?.let { put("ibeacon_minor", it) }
        eddystoneUrl?.let { put("eddystone_url", it) }
        if (buttonState != 0 || joystickX != null) {
            put("button_state", buttonState)
            joystickX?.let { put("joystick_x", it) }
            joystickY?.let { put("joystick_y", it) }
        }
        firmwareVersion?.let { put("firmware_version", it) }
    }

    /** Distanz-Schätzung über RSSI + TX Power (log-distance path loss, n=2) */
    fun updateDistanceEstimate() {
        val tx = txPower ?: -59
        if (rssi == 0) { estimatedDistanceM = null; return }
        val ratio = rssi * 1.0 / tx
        estimatedDistanceM = if (ratio < 1.0) {
            Math.pow(ratio, 10.0).toFloat()
        } else {
            (0.89976 * Math.pow(ratio, 7.7095) + 0.111).toFloat()
        }
        // Qualität sinkt mit Distanz und Alter
        val distancePenalty = (estimatedDistanceM!! / 20f).coerceIn(0f, 0.5f)
        val agePenalty = (ageMs / 30_000f).coerceIn(0f, 0.3f)
        dataQuality = (0.9f - distancePenalty - agePenalty).coerceIn(0.1f, 1f)
    }

    fun copyForBroadcast(): BluetoothAccessory = copy(manufacturerData = null)
}

private fun BluetoothAccessoryType.toClientType(): ClientType = when (this) {
    BluetoothAccessoryType.TOKEN_CLASSIC, BluetoothAccessoryType.TOKEN_PRO, BluetoothAccessoryType.ASSET_TAG -> ClientType.TOKEN
    BluetoothAccessoryType.WEARABLE -> ClientType.WEARABLE
    BluetoothAccessoryType.SENSOR_TAG, BluetoothAccessoryType.AUDIO_BEACON -> ClientType.SENSOR
    BluetoothAccessoryType.RELAY -> ClientType.RELAY
    BluetoothAccessoryType.GATEWAY_BRIDGE -> ClientType.GATEWAY
    BluetoothAccessoryType.REMOTE_CONTROLLER, BluetoothAccessoryType.HID, BluetoothAccessoryType.HEADSET,
    BluetoothAccessoryType.CLASSIC_SPP, BluetoothAccessoryType.GENERIC_BLE, BluetoothAccessoryType.GENERIC_CLASSIC -> ClientType.SENSOR
}

private fun BluetoothAccessoryType.toSensorTypes(): List<SensorType> = when (this) {
    BluetoothAccessoryType.TOKEN_CLASSIC, BluetoothAccessoryType.TOKEN_PRO -> listOf(SensorType.BLE, SensorType.IMU)
    BluetoothAccessoryType.SENSOR_TAG -> listOf(SensorType.BLE, SensorType.TEMPERATURE, SensorType.HUMIDITY, SensorType.AIR_QUALITY)
    BluetoothAccessoryType.WEARABLE -> listOf(SensorType.BLE, SensorType.IMU)
    BluetoothAccessoryType.ASSET_TAG -> listOf(SensorType.BLE)
    BluetoothAccessoryType.REMOTE_CONTROLLER, BluetoothAccessoryType.HID -> listOf(SensorType.BLE, SensorType.IMU)
    BluetoothAccessoryType.RELAY -> listOf(SensorType.BLE, SensorType.IMU)
    BluetoothAccessoryType.GATEWAY_BRIDGE -> listOf(SensorType.BLE)
    BluetoothAccessoryType.AUDIO_BEACON -> listOf(SensorType.BLE)
    else -> listOf(SensorType.BLE)
}
