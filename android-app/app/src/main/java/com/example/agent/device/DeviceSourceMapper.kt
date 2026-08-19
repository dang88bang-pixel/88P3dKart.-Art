package com.example.agent.device

import com.example.agent.device.DeviceModels.CapabilityType
import com.example.agent.device.DeviceModels.ConnectionType
import com.example.agent.device.DeviceModels.Device
import com.example.agent.device.DeviceModels.DeviceCapability
import com.example.agent.device.DeviceModels.DeviceCategory
import com.example.agent.device.DeviceModels.DeviceStatus
import com.example.agent.device.DeviceModels.DeviceType
import com.example.agent.device.DeviceModels.Position3D

/**
 * Geräte-Synchronisation („DeviceSync" der v13.0.0-Spec): bildet die
 * bestehenden Datenquellen der Plattform auf das Geräte-Registry ab.
 *
 * Korrektur gegenüber dem Spec-MainActivity-Sketch: dort wurde
 * `wifiManager.scanResults` als Flow verwendet (existiert nicht) und
 * Positionen wurden gestubbt — hier arbeitet der Mapper auf
 * quellenneutralen Datensätzen; die Positionsbestimmung kommt aus den
 * vorhandenen Modulen (Triangulation/EKF).
 */
object DeviceSourceMapper {

    /** Quelle: BLE-Token (BleTokenManager.TokenData-Äquivalent). */
    data class BleSource(val mac: String, val rssi: Int, val battery: Int)

    /** Quelle: Netzwerkgerät (NetworkDeviceTracker.NetworkDevice-Äquivalent). */
    data class NetworkSource(
        val id: String,
        val name: String,
        val kind: String,     // "wifi_ap" | "ble_device" | ... (normalisiert)
        val rssi: Double,
        val lastSeenMs: Long,
    )

    /** Quelle: mmWave-Target (SerialManager.MmwaveTarget-Äquivalent). */
    data class TargetSource(
        val id: String,
        val x: Float,
        val y: Float,
        val z: Float,
        val velocity: Float,
    )

    /** BLE-Token → Gerät (SENSOR, READ/STREAM, BLE-Verbindung). */
    fun fromBle(source: BleSource, position: Position3D = Position3D(0f, 0f, 0f)): Device =
        Device(
            id = source.mac,
            name = "BLE-Token ${source.mac.takeLast(6)}",
            type = DeviceType.BLE_TOKEN,
            category = DeviceCategory.SENSOR,
            position = position,
            status = DeviceStatus.ONLINE,
            capabilities = listOf(
                DeviceCapability(CapabilityType.READ_DATA, "RSSI lesen"),
                DeviceCapability(CapabilityType.STREAM_DATA, "Streaming"),
            ),
            batteryLevel = source.battery,
            signalStrength = source.rssi,
            connectionType = ConnectionType.BLE,
        )

    /** Netzwerkgerät → Device (Typ-Normalisierung + Staleness-Status). */
    fun fromNetwork(source: NetworkSource): Device {
        val type = when (source.kind.lowercase()) {
            "wifi_ap" -> DeviceType.WIFI_AP
            "wifi_client" -> DeviceType.WIFI_CLIENT
            "ble_device" -> DeviceType.BLE_DEVICE
            "zigbee_node" -> DeviceType.ZIGBEE_NODE
            "lora_gateway" -> DeviceType.LORA_GATEWAY
            else -> DeviceType.UNKNOWN
        }
        return Device(
            id = source.id,
            name = source.name,
            type = type,
            category = DeviceModels.Device.categoryOf(type),
            position = Position3D(0f, 0f, 0f),
            status = stalenessStatus(source.lastSeenMs),
            capabilities = listOf(DeviceCapability(CapabilityType.READ_DATA, "Signalstärke lesen")),
            signalStrength = source.rssi.toInt(),
            lastSeenMs = source.lastSeenMs,
            connectionType = ConnectionType.WIFI,
        )
    }

    /** mmWave-Target → Gerät (SENSOR, Positions-Tracking). */
    fun fromTarget(source: TargetSource, nowMs: Long = System.currentTimeMillis()): Device =
        Device(
            id = source.id,
            name = "Ziel ${"%.1f".format(java.util.Locale.US, source.velocity)} m/s",
            type = DeviceType.MMWAVE_RADAR,
            category = DeviceCategory.SENSOR,
            position = Position3D(source.x, source.y, source.z),
            status = DeviceStatus.ONLINE,
            capabilities = listOf(
                DeviceCapability(CapabilityType.READ_DATA, "Position lesen"),
                DeviceCapability(CapabilityType.STREAM_DATA, "Tracking"),
            ),
            lastSeenMs = nowMs,
        )

    /** Staleness-Status: ONLINE innerhalb des Fensters, sonst OFFLINE. */
    fun stalenessStatus(
        lastSeenMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        staleAfterMs: Long = DeviceRegistry.DEFAULT_STALE_AFTER_MS,
    ): DeviceStatus =
        if (nowMs - lastSeenMs > staleAfterMs) DeviceStatus.OFFLINE else DeviceStatus.ONLINE
}
