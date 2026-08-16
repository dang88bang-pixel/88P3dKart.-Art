import time
from fastapi.testclient import TestClient

from agent import app
from bluetooth_accessories import BluetoothAccessory, BluetoothAccessoryRegistry, BluetoothAccessoryType, global_accessory_registry


def test_accessory_parse_from_dict():
    payload = {
        "mac": "aa:bb:cc:dd:ee:01",
        "type": "TOKEN_PRO",
        "name": "3dxAgent-Token-Pro",
        "rssi": -55,
        "battery": 85,
        "accel_x": 0.1,
        "accel_y": -0.2,
        "accel_z": 9.8,
        "temperature_c": 24.5,
        "flags": 1,  # MOVING
    }
    acc = BluetoothAccessory.from_dict(payload)
    assert acc.mac == "aa:bb:cc:dd:ee:01"
    assert acc.type == BluetoothAccessoryType.TOKEN_PRO
    assert acc.battery == 85
    assert acc.is_moving is True


def test_accessory_parse_legacy_battery_level():
    payload = {
        "mac_address": "AA:BB:CC:DD:EE:02",
        "type": "sensor_tag",
        "rssi": -70,
        "battery_level": 20,
        "humidity_pct": 55,
    }
    acc = BluetoothAccessory.from_dict(payload)
    assert acc.mac == "aa:bb:cc:dd:ee:02"
    assert acc.battery == 20


def test_registry_update_and_stats():
    registry = BluetoothAccessoryRegistry()
    payloads = [
        {"mac": "aa:bb:cc:00:00:01", "type": "TOKEN_PRO", "rssi": -50, "battery": 90},
        {"mac": "aa:bb:cc:00:00:02", "type": "SENSOR_TAG", "rssi": -65, "battery": 60, "temperature_c": 22.0},
        {"mac": "aa:bb:cc:00:00:03", "type": "WEARABLE", "rssi": -55, "battery": 10, "heart_rate_bpm": 78},
    ]
    registry.update_batch(payloads)
    assert registry.count() == 3
    stats = registry.stats()
    assert stats["total"] == 3
    assert stats["low_battery"] == 1
    assert stats["by_type"]["TOKEN_PRO"] == 1


def test_registry_health_evaluation():
    registry = BluetoothAccessoryRegistry()
    # SOS device
    acc = BluetoothAccessory.from_dict({
        "mac": "aa:bb:cc:00:00:99",
        "type": "REMOTE_CONTROLLER",
        "rssi": -50,
        "battery": 80,
        "flags": 128,  # SOS
    })
    registry.update_or_create(acc)
    health = registry.evaluate_health("aa:bb:cc:00:00:99")
    assert health is not None
    assert health.status == "CRITICAL"
    assert "SOS_ACTIVE" in health.warnings


def test_registry_expiry():
    registry = BluetoothAccessoryRegistry()
    acc = BluetoothAccessory.from_dict({"mac": "aa:bb:cc:00:00:10", "type": "TOKEN_CLASSIC", "rssi": -60})
    acc.last_seen = time.time() - 70  # 70s ago
    registry.update_or_create(acc)
    removed = registry.remove_expired(max_age_s=60)
    assert "aa:bb:cc:00:00:10" in removed
    assert registry.count() == 0


def test_rest_endpoints():
    client = TestClient(app)
    # Clean global registry for test isolation
    global_accessory_registry._accessories.clear()

    # Update via REST
    payload = {
        "device_id": "CT45P-01",
        "timestamp": time.time(),
        "accessories": [
            {"mac": "aa:bb:cc:dd:ee:01", "type": "TOKEN_PRO", "rssi": -55, "battery": 88, "name": "Token-Pro-01"},
            {"mac": "aa:bb:cc:dd:ee:02", "type": "SENSOR_TAG", "rssi": -68, "battery": 65, "temperature_c": 23.5, "humidity_pct": 50},
            {"mac": "aa:bb:cc:dd:ee:03", "type": "WEARABLE", "rssi": -60, "battery": 75, "heart_rate_bpm": 80},
        ]
    }
    r = client.post("/api/v1/bluetooth/accessories/update", json=payload)
    assert r.status_code == 200
    assert r.json()["updated"] == 3

    # List
    r = client.get("/api/v1/bluetooth/accessories")
    assert r.status_code == 200
    body = r.json()
    assert body["count"] == 3
    assert "by_type" in body["stats"]

    # Filter by type
    r = client.get("/api/v1/bluetooth/accessories?type=SENSOR_TAG")
    assert r.status_code == 200
    assert r.json()["count"] == 1

    # Detail
    r = client.get("/api/v1/bluetooth/accessories/aa:bb:cc:dd:ee:01")
    assert r.status_code == 200
    assert r.json()["accessory"]["mac"] == "aa:bb:cc:dd:ee:01"

    # Health
    r = client.get("/api/v1/bluetooth/health")
    assert r.status_code == 200
    assert "total" in r.json()

    # Stats
    r = client.get("/api/v1/bluetooth/stats")
    assert r.status_code == 200
    assert r.json()["total"] == 3

    # Cleanup
    r = client.delete("/api/v1/bluetooth/accessories/aa:bb:cc:dd:ee:01")
    assert r.status_code == 200

    r = client.get("/api/v1/bluetooth/accessories")
    assert r.json()["count"] == 2


def test_health_endpoint_includes_bluetooth():
    client = TestClient(app)
    r = client.get("/api/v1/health")
    assert r.status_code == 200
    body = r.json()
    assert "bluetooth" in body
