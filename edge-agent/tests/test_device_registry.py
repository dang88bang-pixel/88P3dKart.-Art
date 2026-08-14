"""Tests für die Geräteinteraktion (docs/DEVICE_INTERACTION.md)."""

from device_registry import (
    ActionResult,
    DEFAULT_LAYERS,
    Device,
    DeviceAction,
    DeviceActionEngine,
    DeviceCapability,
    DeviceRegistry,
)

READ = DeviceCapability(type="READ_DATA", description="Daten lesen")
EXEC = DeviceCapability(type="EXECUTE_COMMAND", description="Befehl ausführen")


def _device(
    device_id="dev-1",
    name="Token 1",
    device_type="BLE_TOKEN",
    category="SENSOR",
    capabilities=None,
    status="ONLINE",
    last_seen=None,
    battery_level=None,
):
    return Device(
        id=device_id, name=name, type=device_type, category=category,
        position=[0.0, 0.0, 0.0], status=status,
        capabilities=capabilities,  # None = „nicht angegeben" (behalten)
        last_seen=last_seen if last_seen is not None else int(1e12),
        battery_level=battery_level,
    )


def test_upsert_inserts_and_updates():
    registry = DeviceRegistry()
    registry.upsert(_device())
    assert len(registry.devices) == 1

    # Update: neue Felder werden übernommen, last_seen aktualisiert
    registry.upsert(_device(name="Token Neu", battery_level=80))
    device = registry.devices[0]
    assert device.name == "Token Neu"
    assert device.battery_level == 80


def test_upsert_preserves_capabilities_when_omitted():
    registry = DeviceRegistry()
    registry.upsert(_device(capabilities=[READ, EXEC]))
    # Update ohne Capabilities → vorhandene bleiben (Spec-Fix)
    registry.upsert(_device(name="Update"))
    assert {c.type for c in registry.devices[0].capabilities} == {"READ_DATA", "EXECUTE_COMMAND"}
    # Update mit neuer Liste → ersetzt
    registry.upsert(_device(capabilities=[READ]))
    assert {c.type for c in registry.devices[0].capabilities} == {"READ_DATA"}


def test_layer_visibility_propagates_to_devices():
    registry = DeviceRegistry()
    registry.upsert(_device(device_id="a"))
    registry.upsert(_device(device_id="b", device_type="WIFI_AP", category="NETWORK"))
    assert registry.set_layer_visibility("sensors", False)
    sensor = next(d for d in registry.devices if d.id == "a")
    network = next(d for d in registry.devices if d.id == "b")
    assert sensor.is_visible is False
    assert network.is_visible is True
    assert len(registry.visible_devices()) == 1
    # Unbekannter Layer → False
    assert registry.set_layer_visibility("gibt_es_nicht", False) is False


def test_selection_cleared_on_remove():
    registry = DeviceRegistry()
    registry.upsert(_device())
    assert registry.select("dev-1")
    assert registry.selected is not None
    registry.remove("dev-1")
    assert registry.selected is None


def test_staleness_marks_online_devices_offline():
    registry = DeviceRegistry()
    registry.upsert(_device(device_id="fresh", last_seen=1_000_000))
    registry.upsert(_device(device_id="stale", last_seen=100_000))
    changed = registry.mark_stale(now_ms=1_000_000, stale_after_ms=120_000)
    assert changed == 1
    fresh = next(d for d in registry.devices if d.id == "fresh")
    stale = next(d for d in registry.devices if d.id == "stale")
    assert fresh.status == "ONLINE"
    assert stale.status == "OFFLINE"


def test_action_engine_capability_gating():
    registry = DeviceRegistry()
    registry.upsert(_device(capabilities=[READ]))  # kein EXECUTE_COMMAND
    engine = DeviceActionEngine(registry)

    assert engine.execute("dev-1", "read_status").success is True
    assert engine.execute("dev-1", "locate").success is True
    blocked = engine.execute("dev-1", "toggle_led")
    assert blocked.success is False
    assert "unterstützt diese Aktion nicht" in blocked.message


def test_action_engine_unknown_device_and_action():
    registry = DeviceRegistry()
    engine = DeviceActionEngine(registry)
    assert engine.execute("gibts-nicht", "read_status").success is False
    registry.upsert(_device())
    assert engine.execute("dev-1", "gibts-nicht").success is False


def test_default_actions_execute():
    registry = DeviceRegistry()
    registry.upsert(_device(capabilities=[READ, EXEC], last_seen=int(1e12)))
    engine = DeviceActionEngine(registry)

    status = engine.execute("dev-1", "read_status")
    assert status.success and status.data["status"] == "ONLINE"

    locate = engine.execute("dev-1", "locate")
    assert locate.data["position"] == [0.0, 0.0, 0.0]

    toggle = engine.execute("dev-1", "toggle_led", {"state": True})
    assert toggle.success and toggle.message == "LED an"

    hide = engine.execute("dev-1", "set_visibility", {"visible": False})
    assert hide.success and hide.message == "Ausgeblendet"
    assert registry.devices[0].is_visible is False


def test_actions_for_device_filters_by_capability():
    registry = DeviceRegistry()
    registry.upsert(_device(capabilities=[READ]))
    engine = DeviceActionEngine(registry)
    ids = {a.id for a in engine.actions_for_device(registry.devices[0])}
    assert "read_status" in ids and "locate" in ids
    assert "toggle_led" not in ids and "set_visibility" not in ids


def test_default_layers_complete():
    categories = {layer.category for layer in DEFAULT_LAYERS}
    assert categories == {"SENSOR", "NETWORK", "ACTUATOR", "VEHICLE", "OTHER"}


def test_device_dict_roundtrip():
    device = _device(capabilities=[READ, EXEC], last_seen=12345)
    restored = Device.from_dict(device.to_dict())
    assert restored.id == device.id
    assert restored.type == "BLE_TOKEN"
    assert restored.category == "SENSOR"
    assert restored.status == "ONLINE"
    assert {c.type for c in restored.capabilities} == {"READ_DATA", "EXECUTE_COMMAND"}
    assert restored.last_seen == 12345
    assert restored.connection_type is None


def test_device_normalization_falls_back_to_unknown():
    device = Device(
        id="x", name="X", type="gibt_es_nicht", category="gibt_es_nicht",
        position=[0, 0, 0], status="gibt_es_nicht",
    )
    assert device.type == "UNKNOWN"
    assert device.category == "OTHER"
    assert device.status == "UNKNOWN"
