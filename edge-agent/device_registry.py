"""Geräteinteraktion — Python-Kern (docs/DEVICE_INTERACTION.md).

Portierung der v13.0.0-Kernlogik (DeviceRegistry, DeviceActionEngine) —
mit Korrekturen gegenüber der Spec (Fehlerkatalog in der Doku):

- Spec-Kotlin nutzte `@Serializable` mit `metadata: Map<String, Any>` —
  kotlinx.serialization kann `Any` nicht serialisieren → hier
  `Dict[str, str]` (Kotlin: `Map<String, String>`).
- Spec-`upsertDevice` überschrieb `capabilities`/`connectionType` nie
  (partielle Kopie) → hier Merge: leere Capability-Liste behält die
  vorhandenen, sonst Update.
- Kein Offline-Übergang (lastSeen nie ausgewertet) → `mark_stale`.
- JS-Tippfehler `capabilitie` (ReferenceError) und `device.type.name`
  (Typ ist im JSON ein String) — im Visualizer korrigiert umgesetzt.
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional

# ─── Modelle ────────────────────────────────────────────────────────────────

DEVICE_TYPES = {
    "BLE_TOKEN", "UWB_SENSOR", "MMWAVE_RADAR", "LIDAR",
    "TEMPERATURE_SENSOR", "HUMIDITY_SENSOR",
    "WIFI_AP", "WIFI_CLIENT", "BLE_DEVICE", "ZIGBEE_NODE",
    "LORA_GATEWAY", "ETHERNET_SWITCH",
    "SMART_LIGHT", "SMART_LOCK", "HVAC_CONTROLLER", "SMART_SWITCH", "DIMMER",
    "EBIKE", "ESCOOTER", "EROLLER", "EV", "SMART_PHONE",
    "UNKNOWN",
}
DEVICE_CATEGORIES = {"SENSOR", "NETWORK", "ACTUATOR", "VEHICLE", "OTHER"}
DEVICE_STATUSES = {"ONLINE", "OFFLINE", "ERROR", "UNKNOWN", "UPDATING", "CONNECTING"}
CONNECTION_TYPES = {"BLE", "WIFI", "UWB", "ZIGBEE", "LORA", "ETHERNET", "USB"}
CAPABILITY_TYPES = {
    "READ_DATA", "WRITE_DATA", "EXECUTE_COMMAND", "STREAM_DATA",
    "FIRMWARE_UPDATE", "BATTERY_STATUS", "SIGNAL_STRENGTH",
}

# Typ → Kategorie (Default-Zuordnung wie in der Spec)
TYPE_CATEGORY = {
    "BLE_TOKEN": "SENSOR", "UWB_SENSOR": "SENSOR", "MMWAVE_RADAR": "SENSOR",
    "LIDAR": "SENSOR", "TEMPERATURE_SENSOR": "SENSOR", "HUMIDITY_SENSOR": "SENSOR",
    "WIFI_AP": "NETWORK", "WIFI_CLIENT": "NETWORK", "BLE_DEVICE": "NETWORK",
    "ZIGBEE_NODE": "NETWORK", "LORA_GATEWAY": "NETWORK", "ETHERNET_SWITCH": "NETWORK",
    "SMART_LIGHT": "ACTUATOR", "SMART_LOCK": "ACTUATOR", "HVAC_CONTROLLER": "ACTUATOR",
    "SMART_SWITCH": "ACTUATOR", "DIMMER": "ACTUATOR",
    "EBIKE": "VEHICLE", "ESCOOTER": "VEHICLE", "EROLLER": "VEHICLE",
    "EV": "VEHICLE", "SMART_PHONE": "VEHICLE",
    "UNKNOWN": "OTHER",
}


def normalize(value: str, valid: set, default: str) -> str:
    return value if value in valid else default


@dataclass
class DeviceCapability:
    type: str
    description: str = ""
    parameters: Optional[Dict[str, str]] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "type": normalize(self.type, CAPABILITY_TYPES, "READ_DATA"),
            "description": self.description,
            "parameters": self.parameters or {},
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "DeviceCapability":
        params = data.get("parameters") or {}
        return cls(
            type=normalize(str(data.get("type", "READ_DATA")), CAPABILITY_TYPES, "READ_DATA"),
            description=str(data.get("description", "")),
            parameters={str(k): str(v) for k, v in params.items()} or None,
        )


@dataclass
class Device:
    id: str
    name: str
    type: str
    category: str
    position: List[float]  # [x, y, z]
    status: str
    capabilities: Optional[List[DeviceCapability]] = None
    metadata: Dict[str, str] = field(default_factory=dict)
    is_visible: bool = True
    is_active: bool = True
    last_seen: int = field(default_factory=lambda: int(time.time() * 1000))
    battery_level: Optional[int] = None
    signal_strength: Optional[int] = None
    connection_type: Optional[str] = None

    def __post_init__(self) -> None:
        self.type = normalize(self.type, DEVICE_TYPES, "UNKNOWN")
        if not self.category or self.category not in DEVICE_CATEGORIES:
            self.category = TYPE_CATEGORY.get(self.type, "OTHER")
        self.status = normalize(self.status, DEVICE_STATUSES, "UNKNOWN")
        if self.connection_type is not None:
            self.connection_type = normalize(self.connection_type, CONNECTION_TYPES, "BLE")

    def to_dict(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "name": self.name,
            "type": self.type,
            "category": self.category,
            "position": list(self.position),
            "status": self.status,
            "capabilities": [c.to_dict() for c in self.capabilities or []],
            "metadata": dict(self.metadata),
            "is_visible": self.is_visible,
            "is_active": self.is_active,
            "last_seen": self.last_seen,
            "battery_level": self.battery_level,
            "signal_strength": self.signal_strength,
            "connection_type": self.connection_type,
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "Device":
        position = data.get("position") or [0.0, 0.0, 0.0]
        return cls(
            id=str(data["id"]),
            name=str(data.get("name", data["id"])),
            type=str(data.get("type", "UNKNOWN")),
            category=str(data.get("category", "")),
            position=[float(position[0]), float(position[1]), float(position[2])],
            status=str(data.get("status", "UNKNOWN")),
            capabilities=[DeviceCapability.from_dict(c) for c in data.get("capabilities", [])],
            metadata={str(k): str(v) for k, v in (data.get("metadata") or {}).items()},
            is_visible=bool(data.get("is_visible", True)),
            is_active=bool(data.get("is_active", True)),
            last_seen=int(data.get("last_seen", int(time.time() * 1000))),
            battery_level=data.get("battery_level"),
            signal_strength=data.get("signal_strength"),
            connection_type=data.get("connection_type"),
        )


@dataclass
class LayerConfig:
    id: str
    name: str
    category: str
    is_visible: bool = True
    color: int = 0xFF4488FF
    icon: str = "📡"
    priority: int = 0

    def to_dict(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "name": self.name,
            "category": self.category,
            "is_visible": self.is_visible,
            "color": self.color,
            "icon": self.icon,
            "priority": self.priority,
        }


@dataclass
class ActionResult:
    device_id: str
    action: str
    success: bool
    message: str
    data: Optional[Dict[str, Any]] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "device_id": self.device_id,
            "action": self.action,
            "success": self.success,
            "message": self.message,
            "data": self.data or {},
        }


# ─── Registry ──────────────────────────────────────────────────────────────

DEFAULT_LAYERS = [
    LayerConfig("sensors", "Sensoren", "SENSOR", True, 0xFF44FF88, "📡", 1),
    LayerConfig("network", "Netzwerk", "NETWORK", True, 0xFF4488FF, "📶", 2),
    LayerConfig("actuators", "Aktoren", "ACTUATOR", True, 0xFFFF8800, "⚙️", 3),
    LayerConfig("vehicles", "Fahrzeuge", "VEHICLE", True, 0xFFFF44FF, "🚗", 4),
    LayerConfig("other", "Sonstige", "OTHER", True, 0xFF888888, "🔌", 5),
]


class DeviceRegistry:
    """Zentrale Geräteverwaltung (Layer-Sichtbarkeit, Auswahl, Staleness)."""

    def __init__(self) -> None:
        self._devices: Dict[str, Device] = {}
        self._layers: Dict[str, LayerConfig] = {l.id: l for l in DEFAULT_LAYERS}
        self._selected_id: Optional[str] = None

    @property
    def devices(self) -> List[Device]:
        return list(self._devices.values())

    @property
    def layers(self) -> Dict[str, LayerConfig]:
        return dict(self._layers)

    @property
    def selected(self) -> Optional[Device]:
        return self._devices.get(self._selected_id) if self._selected_id else None

    def get(self, device_id: str) -> Optional[Device]:
        return self._devices.get(device_id)

    # ── Mutationen ─────────────────────────────────────────────

    def upsert(self, device: Device) -> Device:
        """Fügt ein Gerät hinzu oder aktualisiert es (Merge).

        Korrektur gegenüber der Spec: leere Capability-Liste behält die
        vorhandenen Capabilities, sonst Update; connection_type wird
        übernommen statt verworfen.
        """
        existing = self._devices.get(device.id)
        if existing is None:
            self._devices[device.id] = device
            return device

        capabilities = device.capabilities if device.capabilities is not None else existing.capabilities
        merged = Device(
            id=device.id,
            name=device.name,
            type=device.type,
            category=device.category,
            position=device.position,
            status=device.status,
            capabilities=capabilities,
            metadata={**existing.metadata, **device.metadata},
            is_visible=device.is_visible,
            is_active=device.is_active,
            last_seen=device.last_seen,
            battery_level=device.battery_level if device.battery_level is not None else existing.battery_level,
            signal_strength=device.signal_strength if device.signal_strength is not None else existing.signal_strength,
            connection_type=device.connection_type or existing.connection_type,
        )
        self._devices[device.id] = merged
        return merged

    def update_position(self, device_id: str, position: List[float]) -> bool:
        device = self._devices.get(device_id)
        if device is None:
            return False
        device.position = list(position)
        device.last_seen = int(time.time() * 1000)
        return True

    def update_status(self, device_id: str, status: str) -> bool:
        device = self._devices.get(device_id)
        if device is None:
            return False
        device.status = normalize(status, DEVICE_STATUSES, "UNKNOWN")
        device.last_seen = int(time.time() * 1000)
        return True

    def set_visibility(self, device_id: str, visible: bool) -> bool:
        device = self._devices.get(device_id)
        if device is None:
            return False
        device.is_visible = visible
        return True

    def set_layer_visibility(self, layer_id: str, visible: bool) -> bool:
        layer = self._layers.get(layer_id)
        if layer is None:
            return False
        layer.is_visible = visible
        for device in self._devices.values():
            if device.category == layer.category:
                device.is_visible = visible
        return True

    def select(self, device_id: Optional[str]) -> bool:
        if device_id is None:
            self._selected_id = None
            return True
        if device_id in self._devices:
            self._selected_id = device_id
            return True
        return False

    def remove(self, device_id: str) -> bool:
        removed = self._devices.pop(device_id, None) is not None
        if self._selected_id == device_id:
            self._selected_id = None
        return removed

    # ── Abfragen ────────────────────────────────────────────────

    def by_category(self, category: str) -> List[Device]:
        return [d for d in self._devices.values() if d.category == category]

    def visible_devices(self) -> List[Device]:
        return [d for d in self._devices.values() if d.is_visible and d.is_active]

    def mark_stale(self, now_ms: Optional[int] = None, stale_after_ms: int = 120_000) -> int:
        """ONLINE-Geräte ohne Lebenszeichen → OFFLINE (Status-Lifecycle)."""
        now = now_ms if now_ms is not None else int(time.time() * 1000)
        count = 0
        for device in self._devices.values():
            if device.status == "ONLINE" and now - device.last_seen > stale_after_ms:
                device.status = "OFFLINE"
                count += 1
        return count


# ─── Action-Engine ─────────────────────────────────────────────────────────


@dataclass
class DeviceAction:
    id: str
    name: str
    description: str
    capability: Optional[str]  # None = immer erlaubt
    execute: Callable[[Device, Dict[str, Any]], ActionResult]


class DeviceActionEngine:
    """Capability-geprüfte Geräteaktionen (Standard-Aktionen registriert)."""

    def __init__(self, registry: DeviceRegistry) -> None:
        self.registry = registry
        self._actions: Dict[str, DeviceAction] = {}
        self._register_defaults()

    def _register_defaults(self) -> None:
        self.register(DeviceAction(
            id="read_status", name="Status abfragen",
            description="Aktuellen Gerätestatus abfragen", capability="READ_DATA",
            execute=lambda device, _: ActionResult(
                device_id=device.id, action="read_status", success=True,
                message=f"Status: {device.status}",
                data={
                    "status": device.status,
                    "battery": device.battery_level if device.battery_level is not None else "N/A",
                    "signal": device.signal_strength if device.signal_strength is not None else "N/A",
                    "last_seen": device.last_seen,
                },
            ),
        ))
        self.register(DeviceAction(
            id="locate", name="Ortung", description="Geräteposition anzeigen",
            capability="READ_DATA",
            execute=lambda device, _: ActionResult(
                device_id=device.id, action="locate", success=True,
                message=f"Position: {device.position}",
                data={"position": list(device.position)},
            ),
        ))
        self.register(DeviceAction(
            id="set_visibility", name="Sichtbarkeit umschalten",
            description="Gerät ein-/ausblenden", capability="EXECUTE_COMMAND",
            execute=lambda device, params: ActionResult(
                device_id=device.id, action="set_visibility",
                success=self.registry.set_visibility(device.id, bool(params.get("visible", True))),
                message="Eingeblendet" if params.get("visible", True) else "Ausgeblendet",
            ),
        ))
        self.register(DeviceAction(
            id="toggle_led", name="LED umschalten", description="Geräte-LED ein-/ausschalten",
            capability="EXECUTE_COMMAND",
            execute=lambda device, params: ActionResult(
                device_id=device.id, action="toggle_led", success=True,
                message="LED an" if params.get("state", True) else "LED aus",
                data={"state": bool(params.get("state", True))},
            ),
        ))

    def register(self, action: DeviceAction) -> None:
        self._actions[action.id] = action

    @property
    def available_actions(self) -> List[DeviceAction]:
        return list(self._actions.values())

    def actions_for_device(self, device: Device) -> List[DeviceAction]:
        supported = {c.type for c in device.capabilities}
        return [a for a in self._actions.values() if a.capability is None or a.capability in supported]

    def execute(self, device_id: str, action_id: str, params: Optional[Dict[str, Any]] = None) -> ActionResult:
        params = params or {}
        device = self.registry.get(device_id)
        if device is None:
            return ActionResult(device_id, action_id, False, "Gerät nicht gefunden")
        action = self._actions.get(action_id)
        if action is None:
            return ActionResult(device_id, action_id, False, "Aktion nicht gefunden")
        if action.capability is not None and not any(
            c.type == action.capability for c in device.capabilities
        ):
            return ActionResult(device_id, action_id, False, "Gerät unterstützt diese Aktion nicht")
        try:
            return action.execute(device, params)
        except Exception as exc:  # noqa: BLE001
            return ActionResult(device_id, action_id, False, f"Fehler: {exc}")
