package com.example.agent.device

/**
 * Geräteinteraktion — Modelle (docs/DEVICE_INTERACTION.md).
 *
 * Portierung der v13.0.0-Datenmodelle, **mit Korrektur**: die Spec nutzte
 * `@Serializable` mit `metadata: Map<String, Any>` — kotlinx.serialization
 * kann `Any` nicht serialisieren → hier `Map<String, String>`
 * (Python-Port: `Dict[str, str]`).
 */
object DeviceModels {

    enum class DeviceType {
        BLE_TOKEN, UWB_SENSOR, MMWAVE_RADAR, LIDAR,
        TEMPERATURE_SENSOR, HUMIDITY_SENSOR,
        WIFI_AP, WIFI_CLIENT, BLE_DEVICE, ZIGBEE_NODE,
        LORA_GATEWAY, ETHERNET_SWITCH,
        SMART_LIGHT, SMART_LOCK, HVAC_CONTROLLER, SMART_SWITCH, DIMMER,
        EBIKE, ESCOOTER, EROLLER, EV, SMART_PHONE,
        UNKNOWN,
    }

    enum class DeviceCategory { SENSOR, NETWORK, ACTUATOR, VEHICLE, OTHER }

    enum class DeviceStatus { ONLINE, OFFLINE, ERROR, UNKNOWN, UPDATING, CONNECTING }

    enum class ConnectionType { BLE, WIFI, UWB, ZIGBEE, LORA, ETHERNET, USB }

    enum class CapabilityType {
        READ_DATA, WRITE_DATA, EXECUTE_COMMAND, STREAM_DATA,
        FIRMWARE_UPDATE, BATTERY_STATUS, SIGNAL_STRENGTH,
    }

    data class DeviceCapability(
        val type: CapabilityType,
        val description: String = "",
        val parameters: Map<String, String> = emptyMap(),
    )

    data class Position3D(
        val x: Float,
        val y: Float,
        val z: Float,
        val lat: Double? = null,
        val lon: Double? = null,
    )

    /** Typ → Kategorie (Default-Zuordnung wie in der Spec). */
    val TYPE_CATEGORY: Map<DeviceType, DeviceCategory> = mapOf(
        DeviceType.BLE_TOKEN to DeviceCategory.SENSOR,
        DeviceType.UWB_SENSOR to DeviceCategory.SENSOR,
        DeviceType.MMWAVE_RADAR to DeviceCategory.SENSOR,
        DeviceType.LIDAR to DeviceCategory.SENSOR,
        DeviceType.TEMPERATURE_SENSOR to DeviceCategory.SENSOR,
        DeviceType.HUMIDITY_SENSOR to DeviceCategory.SENSOR,
        DeviceType.WIFI_AP to DeviceCategory.NETWORK,
        DeviceType.WIFI_CLIENT to DeviceCategory.NETWORK,
        DeviceType.BLE_DEVICE to DeviceCategory.NETWORK,
        DeviceType.ZIGBEE_NODE to DeviceCategory.NETWORK,
        DeviceType.LORA_GATEWAY to DeviceCategory.NETWORK,
        DeviceType.ETHERNET_SWITCH to DeviceCategory.NETWORK,
        DeviceType.SMART_LIGHT to DeviceCategory.ACTUATOR,
        DeviceType.SMART_LOCK to DeviceCategory.ACTUATOR,
        DeviceType.HVAC_CONTROLLER to DeviceCategory.ACTUATOR,
        DeviceType.SMART_SWITCH to DeviceCategory.ACTUATOR,
        DeviceType.DIMMER to DeviceCategory.ACTUATOR,
        DeviceType.EBIKE to DeviceCategory.VEHICLE,
        DeviceType.ESCOOTER to DeviceCategory.VEHICLE,
        DeviceType.EROLLER to DeviceCategory.VEHICLE,
        DeviceType.EV to DeviceCategory.VEHICLE,
        DeviceType.SMART_PHONE to DeviceCategory.VEHICLE,
        DeviceType.UNKNOWN to DeviceCategory.OTHER,
    )

    data class Device(
        val id: String,
        val name: String,
        val type: DeviceType,
        val category: DeviceCategory,
        val position: Position3D,
        val status: DeviceStatus,
        val capabilities: List<DeviceCapability>? = null, // null = bei Upsert behalten
        val metadata: Map<String, String> = emptyMap(),
        val isVisible: Boolean = true,
        val isActive: Boolean = true,
        val lastSeenMs: Long = System.currentTimeMillis(),
        val batteryLevel: Int? = null,
        val signalStrength: Int? = null,
        val connectionType: ConnectionType? = null,
    ) {
        companion object {
            /** Kategorie aus dem Typ ableiten (Default-Zuordnung). */
            fun categoryOf(type: DeviceType): DeviceCategory =
                TYPE_CATEGORY[type] ?: DeviceCategory.OTHER
        }
    }
}
