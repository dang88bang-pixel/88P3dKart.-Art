"""API-Abdeckung: Algorithmen-Endpunkte (Positionierung, Signal, Sync,
Checkpoints, Privacy-Filter) + Kalman-Flotten-RSSI."""
import time
import uuid

import pytest
from fastapi.testclient import TestClient

from agent import app, db, fleet_registry
from alarm_repository import AlarmRepository
from alarm_service import AlarmService
from security import CredentialAttemptControls, CredentialStore, GatewaySecurity


@pytest.fixture
def authenticated_client(tmp_path):
    security = GatewaySecurity(
        CredentialStore(str(tmp_path / "credentials.db")),
        signing_secret="s" * 32,
        admin_bootstrap_token="a" * 32,
        session_ttl_s=900,
    )
    app.state.security = security
    app.state.credential_attempts = CredentialAttemptControls()
    app.state.alarm_service = AlarmService(
        AlarmRepository(str(tmp_path / "alarms.db")), "gateway-test"
    )
    enrollment = security.store.create_enrollment_code("CT45P-01", 1_000, 600)
    credential = security.store.claim_enrollment("CT45P-01", enrollment.code, 1_001)
    token, _ = security.issue_device_session("CT45P-01", credential.secret)
    with TestClient(app, base_url="https://testserver") as client:
        yield client, {
            "admin": {"Authorization": f"Bearer {'a' * 32}"},
            "device": {"Authorization": f"Bearer {token}"},
        }


@pytest.fixture(autouse=True)
def clean_state(tmp_path):
    fleet_registry._vehicles.clear()
    fleet_registry._kalman.clear()
    db.db_path = str(tmp_path / "algo-agent.db")
    db._init_db()
    yield


def test_positioning_estimate_endpoint(authenticated_client):
    client, headers = authenticated_client
    r = client.post("/api/v1/positioning/estimate", json={
        "anchors": [
            {"id": "a1", "x": 0, "y": 0, "distance": 5},
            {"id": "a2", "x": 10, "y": 0, "distance": 5},
            {"id": "a3", "x": 5, "y": 7.07, "distance": 7.07},
        ],
        "use_z": False,
    }, headers=headers["device"])
    assert r.status_code == 200
    body = r.json()
    assert body["method"] == "trilateration"
    assert abs(body["position"]["x"] - 5.0) < 0.5
    # WCL-Fallback bei einem Anker
    r = client.post("/api/v1/positioning/estimate", json={
        "anchors": [{"id": "a1", "x": 3, "y": 4, "distance": 2}],
    }, headers=headers["device"])
    assert r.status_code == 200 and r.json()["method"] == "weighted_centroid"
    # Ohne Anker → 422
    r = client.post("/api/v1/positioning/estimate", json={"anchors": []}, headers=headers["device"])
    assert r.status_code == 422


def test_fingerprint_endpoints(authenticated_client):
    client, headers = authenticated_client
    assert client.post("/api/v1/positioning/fingerprint", json={
        "lat": 52.5160, "lon": 13.3770, "rssi_map": {"b1": -50, "b2": -60},
    }, headers=headers["device"]).status_code == 200
    r = client.post("/api/v1/positioning/fingerprint/locate", json={
        "rssi_map": {"b1": -51, "b2": -61}, "k": 1,
    }, headers=headers["device"])
    assert r.status_code == 200
    assert abs(r.json()["position"]["x"] - 52.5160) < 0.001


def test_signal_smooth_endpoint(authenticated_client):
    client, headers = authenticated_client
    r = client.post("/api/v1/signal/smooth", json={
        "values": [-65.0, -66.0, -64.5, -65.5, -64.8, -65.1, -40.0, -65.0],
        "method": "kalman",
    }, headers=headers["device"])
    assert r.status_code == 200 and len(r.json()["values"]) == 8
    r = client.post("/api/v1/signal/smooth", json={
        "values": [-65.0, -66.0, -64.5, -40.0], "method": "hampel",
    }, headers=headers["device"])
    assert r.status_code == 200
    # Hampel ersetzt den Spike
    assert r.json()["values"][3] == pytest.approx(-65.0, abs=1.0)


def test_mesh_sync_endpoint(authenticated_client):
    client, headers = authenticated_client
    r = client.post("/api/v1/mesh/sync", json={
        "times": [0.0, 5.0, -3.0, 8.0], "tolerance": 0.01, "max_rounds": 500,
    }, headers=headers["device"])
    assert r.status_code == 200
    body = r.json()
    assert body["converged"] is True
    assert body["disagreement_after"] <= 0.01


def test_checkpoint_endpoints(authenticated_client):
    client, headers = authenticated_client
    db.save_transform("CT45P-01", (1, 2, 3), (0.1, 0.1), {})
    r = client.post("/api/v1/checkpoints", json={"metadata": {"reason": "api-test"}},
                    headers=headers["admin"])
    assert r.status_code == 200 and len(r.json()["checksum"]) == 64
    r = client.get("/api/v1/checkpoints", headers=headers["device"])
    assert r.status_code == 200 and len(r.json()["checkpoints"]) == 1
    r = client.post("/api/v1/checkpoints/verify", headers=headers["device"])
    assert r.status_code == 200
    assert r.json()["integrity_ok"] is True
    # Nur Admin darf Checkpoints anlegen
    assert client.post("/api/v1/checkpoints", json={}, headers=headers["device"]).status_code == 403


def test_privacy_filter_endpoint(authenticated_client):
    client, headers = authenticated_client
    r = client.post("/api/v1/privacy/filter", json={
        "objects": [
            {"kind": "wall", "position": [0, 0, 1]},
            {"kind": "person", "position": [1, 1, 1]},
            {"kind": "device", "position": [2, 2, 1]},
        ],
        "devices": [{"id": "AA:BB:CC:DD:EE:FF", "mac": "AA:BB:CC:DD:EE:FF", "metadata": {"user_id": "u1"}}],
        "metadata": {"user_id": "x", "note": "keep"},
    }, headers=headers["device"])
    assert r.status_code == 200
    body = r.json()
    assert [o["kind"] for o in body["objects"]] == ["wall", "device"]
    assert body["audit"]["live_only_removed"] == 1
    assert body["devices"][0]["id"].startswith("ANON_")
    assert body["metadata"] == {"note": "keep"}


def test_fleet_kalman_rssi_smoothing():
    """Kalman-Smoothing in der Flotten-BLE-Distanz (erste Messung = Rohwert)."""
    fleet_registry._kalman.clear()
    v1 = fleet_registry.update_from_payload({"id": "tok-1", "kind": "ble_token", "rssi": -65})
    assert v1.rssi_smoothed == pytest.approx(-65.0, abs=0.01)
    raw_distance = 10 ** ((-59.0 + 65.0) / 20.0)
    assert v1.distance_m == pytest.approx(raw_distance, rel=1e-6)
    # Jitter: Kalman-geglättete Distanzstreuung < Rohwert-Streuung
    jitters = [4, -3, 5, -4, 2, -5, 3]
    raw = [10 ** ((-59.0 + 65.0 - j) / 20.0) for j in jitters]
    smoothed = []
    for j in jitters:
        v = fleet_registry.update_from_payload(
            {"id": "tok-1", "kind": "ble_token", "rssi": -65 + j}
        )
        smoothed.append(v.distance_m)
    std = lambda xs: (sum((x - sum(xs) / len(xs)) ** 2 for x in xs) / len(xs)) ** 0.5
    assert std(smoothed) < std(raw) * 0.6
    # Rohwert bleibt erhalten, geglätteter Wert separat ausgewiesen
    v = fleet_registry.get("tok-1")
    assert v.rssi == -62 and v.rssi_smoothed != pytest.approx(v.rssi, abs=0.01)
