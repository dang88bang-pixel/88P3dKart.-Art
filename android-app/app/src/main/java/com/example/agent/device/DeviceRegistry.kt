package com.example.agent.device

import com.example.agent.device.DeviceModels.Device
import com.example.agent.device.DeviceModels.DeviceCategory
import com.example.agent.device.DeviceModels.DeviceStatus
import com.example.agent.device.DeviceModels.Position3D
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Zentrale Geräteverwaltung (docs/DEVICE_INTERACTION.md).
 *
 * Portierung der v13.0.0-Kernlogik (DeviceRegistry) — als reiner,
 * JVM-testbarer Kern (StateFlow, keine Android-Abhängigkeiten):
 *
 * - Upsert mit **Merge-Semantik** (Korrektur der Spec: leere/fehlende
 *   Capability-Liste behält die vorhandenen; connection_type wird
 *   übernommen statt verworfen),
 * - Layer-Sichtbarkeit propagiert auf die Gerätekategorie,
 * - Auswahl-Lifecycle (Entfernen räumt die Selektion),
 * - **Status-Lifecycle** (`markStale`: ONLINE → OFFLINE ohne Lebenszeichen).
 */
class DeviceRegistry {

    data class LayerConfig(
        val id: String,
        val name: String,
        val category: DeviceCategory,
        val isVisible: Boolean = true,
        val color: Int = 0xFF4488FF.toInt(),
        val icon: String = "📡",
        val priority: Int = 0,
    )

    companion object {
        const val DEFAULT_STALE_AFTER_MS: Long = 120_000

        val DEFAULT_LAYERS: List<LayerConfig> = listOf(
            LayerConfig("sensors", "Sensoren", DeviceCategory.SENSOR, true, 0xFF44FF88.toInt(), "📡", 1),
            LayerConfig("network", "Netzwerk", DeviceCategory.NETWORK, true, 0xFF4488FF.toInt(), "📶", 2),
            LayerConfig("actuators", "Aktoren", DeviceCategory.ACTUATOR, true, 0xFFFF8800.toInt(), "⚙️", 3),
            LayerConfig("vehicles", "Fahrzeuge", DeviceCategory.VEHICLE, true, 0xFFFF44FF.toInt(), "🚗", 4),
            LayerConfig("other", "Sonstige", DeviceCategory.OTHER, true, 0xFF888888.toInt(), "🔌", 5),
        )
    }

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    private val _layers = MutableStateFlow<Map<String, LayerConfig>>(
        DEFAULT_LAYERS.associateBy { it.id }
    )
    val layers: StateFlow<Map<String, LayerConfig>> = _layers.asStateFlow()

    private val _selectedDevice = MutableStateFlow<Device?>(null)
    val selectedDevice: StateFlow<Device?> = _selectedDevice.asStateFlow()

    /** Fügt ein Gerät hinzu oder aktualisiert es (Merge-Semantik). */
    fun upsertDevice(device: Device): Device {
        val current = _devices.value
        val index = current.indexOfFirst { it.id == device.id }
        val merged: Device

        if (index < 0) {
            merged = device
            _devices.value = current + device
        } else {
            val existing = current[index]
            merged = Device(
                id = device.id,
                name = device.name,
                type = device.type,
                category = device.category,
                position = device.position,
                status = device.status,
                // Spec-Fix: fehlende Capabilities behalten, sonst ersetzen
                capabilities = device.capabilities ?: existing.capabilities,
                metadata = existing.metadata + device.metadata,
                isVisible = device.isVisible,
                isActive = device.isActive,
                lastSeenMs = device.lastSeenMs,
                batteryLevel = device.batteryLevel ?: existing.batteryLevel,
                signalStrength = device.signalStrength ?: existing.signalStrength,
                connectionType = device.connectionType ?: existing.connectionType,
            )
            _devices.value = current.toMutableList().also { it[index] = merged }
        }
        return merged
    }

    fun get(deviceId: String): Device? = _devices.value.firstOrNull { it.id == deviceId }

    fun updateDevicePosition(deviceId: String, position: Position3D): Boolean {
        val current = _devices.value
        val index = current.indexOfFirst { it.id == deviceId }
        if (index < 0) return false
        _devices.value = current.toMutableList().also {
            it[index] = it[index].copy(position = position, lastSeenMs = System.currentTimeMillis())
        }
        return true
    }

    fun updateDeviceStatus(deviceId: String, status: DeviceStatus): Boolean {
        val current = _devices.value
        val index = current.indexOfFirst { it.id == deviceId }
        if (index < 0) return false
        _devices.value = current.toMutableList().also {
            it[index] = it[index].copy(status = status, lastSeenMs = System.currentTimeMillis())
        }
        return true
    }

    fun setDeviceVisibility(deviceId: String, visible: Boolean): Boolean {
        val current = _devices.value
        val index = current.indexOfFirst { it.id == deviceId }
        if (index < 0) return false
        _devices.value = current.toMutableList().also { it[index] = it[index].copy(isVisible = visible) }
        return true
    }

    /** Layer-Sichtbarkeit setzen (propagiert auf alle Geräte der Kategorie). */
    fun setLayerVisibility(layerId: String, visible: Boolean): Boolean {
        val currentLayers = _layers.value
        val layer = currentLayers[layerId] ?: return false
        _layers.value = currentLayers + (layerId to layer.copy(isVisible = visible))
        _devices.value = _devices.value.map { device ->
            if (device.category == layer.category) device.copy(isVisible = visible) else device
        }
        return true
    }

    fun selectDevice(deviceId: String?) {
        _selectedDevice.value = if (deviceId == null) null else get(deviceId)
    }

    fun getDevicesByCategory(category: DeviceCategory): List<Device> =
        _devices.value.filter { it.category == category }

    fun visibleDevices(): List<Device> =
        _devices.value.filter { it.isVisible && it.isActive }

    fun removeDevice(deviceId: String): Boolean {
        val before = _devices.value.size
        _devices.value = _devices.value.filterNot { it.id == deviceId }
        if (_selectedDevice.value?.id == deviceId) _selectedDevice.value = null
        return _devices.value.size < before
    }

    /** ONLINE-Geräte ohne Lebenszeichen → OFFLINE (Status-Lifecycle). */
    fun markStale(
        nowMs: Long = System.currentTimeMillis(),
        staleAfterMs: Long = DEFAULT_STALE_AFTER_MS,
    ): Int {
        var changed = 0
        _devices.value = _devices.value.map { device ->
            if (device.status == DeviceStatus.ONLINE && nowMs - device.lastSeenMs > staleAfterMs) {
                changed++
                device.copy(status = DeviceStatus.OFFLINE)
            } else {
                device
            }
        }
        return changed
    }
}
