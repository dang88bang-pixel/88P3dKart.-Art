"""Tests für den Live-Netzwerk-Geräte-Tracker (docs/TACTICAL.md)."""

from network_tracker import DeviceTracker


def _device(device_id: str, rssi: int):
    return {"id": device_id, "type": "ble_device", "name": f"dev-{device_id}", "rssi": rssi}


def test_added_and_removed_detection():
    tracker = DeviceTracker()
    first = tracker.update([_device("a", -50), _device("b", -60)])
    assert {d["id"] for d in first["added"]} == {"a", "b"}
    assert first["removed"] == []

    second = tracker.update([_device("a", -50), _device("c", -70)])
    assert [d["id"] for d in second["added"]] == ["c"]
    assert [d["id"] for d in second["removed"]] == ["b"]


def test_signal_change_threshold():
    tracker = DeviceTracker(signal_change_threshold_dbm=10.0)
    tracker.update([_device("a", -50)])
    result = tracker.update([_device("a", -55)])  # 5 dBm → unauffällig
    assert result["signal_changes"] == []
    result = tracker.update([_device("a", -66)])  # 11 dBm → Sprung
    assert len(result["signal_changes"]) == 1
    assert result["signal_changes"][0]["diff"] == 11


def test_anomaly_via_history_deviation():
    tracker = DeviceTracker(anomaly_deviation_dbm=20.0, anomaly_window=5)
    for _ in range(5):
        result = tracker.update([_device("a", -60)])
        assert result["anomalies"] == []
    # Sprung um > 20 dBm gegen den 5er-Mittelwert
    result = tracker.update([_device("a", -95)])
    assert len(result["anomalies"]) == 1
    assert result["anomalies"][0]["id"] == "a"
    assert result["anomalies"][0]["severity"] in ("medium", "high")


def test_clear_resets_state():
    tracker = DeviceTracker()
    tracker.update([_device("a", -50)])
    tracker.clear()
    assert tracker.known_devices() == {}
