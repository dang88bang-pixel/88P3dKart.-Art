package com.example.agent.device

import com.example.agent.device.DeviceModels.CapabilityType
import com.example.agent.device.DeviceModels.Device

/**
 * Geräte-Aktions-Engine (docs/DEVICE_INTERACTION.md) — Portierung der
 * v13.0.0-Kernlogik (DeviceActionEngine) mit Konsolidierung: die Spec
 * duplizierte die Aktionslogik in `DeviceInteractionManager` (hartkodierte
 * when-Zweige) — hier ist die Engine die einzige Quelle für
 * Capability-Prüfung und Aktionsausführung.
 *
 * Die tatsächliche Hardware-Ansteuerung (BLE-Kommando, Firmware-Update)
 * läuft über Transport-Adapter; die Standard-Aktionen sind deterministische
 * Registry-/Status-Operationen und damit vollständig JVM-testbar.
 */
class DeviceActionEngine(private val registry: DeviceRegistry) {

    data class ActionResult(
        val deviceId: String,
        val action: String,
        val success: Boolean,
        val message: String,
        val data: Map<String, String> = emptyMap(),
    )

    data class DeviceAction(
        val id: String,
        val name: String,
        val description: String,
        val capability: CapabilityType,
        val execute: (Device, Map<String, String>) -> ActionResult,
    )

    private val actions = LinkedHashMap<String, DeviceAction>()

    init {
        registerDefaultActions()
    }

    private fun registerDefaultActions() {
        register(
            DeviceAction(
                id = "read_status",
                name = "Status abfragen",
                description = "Aktuellen Gerätestatus abfragen",
                capability = CapabilityType.READ_DATA,
            ) { device, _ ->
                ActionResult(
                    deviceId = device.id,
                    action = "read_status",
                    success = true,
                    message = "Status: ${device.status}",
                    data = mapOf(
                        "status" to device.status.name,
                        "battery" to (device.batteryLevel?.toString() ?: "N/A"),
                        "signal" to (device.signalStrength?.toString() ?: "N/A"),
                        "last_seen" to device.lastSeenMs.toString(),
                    ),
                )
            },
        )
        register(
            DeviceAction(
                id = "locate",
                name = "Ortung",
                description = "Geräteposition anzeigen",
                capability = CapabilityType.READ_DATA,
            ) { device, _ ->
                ActionResult(
                    deviceId = device.id,
                    action = "locate",
                    success = true,
                    message = "Position: ${device.position.x}, ${device.position.y}, ${device.position.z}",
                    data = mapOf(
                        "x" to device.position.x.toString(),
                        "y" to device.position.y.toString(),
                        "z" to device.position.z.toString(),
                    ),
                )
            },
        )
        register(
            DeviceAction(
                id = "set_visibility",
                name = "Sichtbarkeit umschalten",
                description = "Gerät ein-/ausblenden",
                capability = CapabilityType.EXECUTE_COMMAND,
            ) { device, params ->
                val visible = params["visible"]?.toBoolean() ?: true
                ActionResult(
                    deviceId = device.id,
                    action = "set_visibility",
                    success = registry.setDeviceVisibility(device.id, visible),
                    message = if (visible) "Eingeblendet" else "Ausgeblendet",
                )
            },
        )
        register(
            DeviceAction(
                id = "toggle_led",
                name = "LED umschalten",
                description = "Geräte-LED ein-/ausschalten",
                capability = CapabilityType.EXECUTE_COMMAND,
            ) { device, params ->
                val state = params["state"]?.toBoolean() ?: true
                ActionResult(
                    deviceId = device.id,
                    action = "toggle_led",
                    success = true,
                    message = if (state) "LED an" else "LED aus",
                    data = mapOf("state" to state.toString()),
                )
            },
        )
    }

    fun register(action: DeviceAction) {
        actions[action.id] = action
    }

    val availableActions: List<DeviceAction>
        get() = actions.values.toList()

    /** Alle Aktionen, deren Capability das Gerät unterstützt. */
    fun actionsForDevice(device: Device): List<DeviceAction> {
        val supported = device.capabilities.orEmpty().map { it.type }.toSet()
        return actions.values.filter { supported.contains(it.capability) }
    }

    fun execute(
        deviceId: String,
        actionId: String,
        parameters: Map<String, String> = emptyMap(),
    ): ActionResult {
        val device = registry.get(deviceId)
            ?: return ActionResult(deviceId, actionId, false, "Gerät nicht gefunden")
        val action = actions[actionId]
            ?: return ActionResult(deviceId, actionId, false, "Aktion nicht gefunden")
        val hasCapability = device.capabilities.orEmpty().any { it.type == action.capability }
        if (!hasCapability) {
            return ActionResult(deviceId, actionId, false, "Gerät unterstützt diese Aktion nicht")
        }
        return try {
            action.execute(device, parameters)
        } catch (e: Exception) {
            ActionResult(deviceId, actionId, false, "Fehler: ${e.message}")
        }
    }
}
