"""API-Abdeckung: Premises, EDM, Sync-Queue, Flotten-Gruppen, Historie."""
import time

import pytest
from fastapi.testclient import TestClient

from agent import app, db, fleet_registry, edm_registry, premises_security, _sync_outbox
from alarm_repository import AlarmRepository
from alarm_service import AlarmService
from fleet import FleetVehicle
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
    fleet_registry._groups.clear()
    edm_registry._devices.clear()
    edm_registry._audit.clear()
    premises_security._observed.clear()
    premises_security._sensor_reports.clear()
    premises_security.alerts.clear()
    premises_security._known_own_ids.clear()
    premises_security._last_own_counts.clear()
    _sync_outbox.clear()
    db.db_path = str(tmp_path / "premises-agent.db")
    db._init_db()
    yield


def _seed_fleet():
    fleet_registry.upsert(FleetVehicle(id="veh-1", name="E-Bike 1", kind="ebike", lat=52.5, lon=13.4))
    premises_security.register_own("veh-1")


# ─── Premises ────────────────────────────────────────────────

def test_premises_sensor_report_and_overview(authenticated_client):
    client, headers = authenticated_client
    r = client.post("/api/v1/premises/sensor-report", json={
        "device_id": "CT45P-01", "kind": "magnetometer", "value": 48.2, "unit": "uT",
    }, headers=headers["device"])
    assert r.status_code == 200
    r = client.get("/api/v1/premises/overview", headers=headers["device"])
    assert r.status_code == 200
    body = r.json()
    assert body["sensor_reports"][0]["kind"] == "magnetometer"
    assert "own_registered" in body


def test_premises_unknown_detection_via_bluetooth_ingest(authenticated_client):
    client, headers = authenticated_client
    _seed_fleet()
    # Unbekanntes BLE-Gerät (nicht gebunden, nicht in der Flotte)
    r = client.post("/api/v1/bluetooth/accessories/update", json={
        "device_id": "CT45P-01", "timestamp": time.time(),
        "accessories": [{"mac": "aa:bb:cc:dd:ee:99", "type": "GENERIC_BLE", "rssi": -70}],
    }, headers=headers["device"])
    assert r.status_code == 200
    assert r.json()["unknown_detected"] == 1
    r = client.get("/api/v1/premises/unknown", headers=headers["device"])
    assert r.status_code == 200
    assert r.json()["count"] == 1
    assert r.json()["devices"][0]["id"] == "aa:bb:cc:dd:ee:99"


# ─── EDM ─────────────────────────────────────────────────────

def test_edm_full_flow(authenticated_client):
    client, headers = authenticated_client
    r = client.post("/api/v1/edm/devices", json={
        "device_id": "CT45P-01", "serial": "S-123", "model": "CT45P-X0N",
        "location": "Halle A",
    }, headers=headers["admin"])
    assert r.status_code == 200
    assert r.json()["device"]["state"] == "ENROLLED"

    r = client.post("/api/v1/edm/devices/CT45P-01/state", json={
        "state": "PROVISIONED", "reason": "Gerät eingerichtet",
    }, headers=headers["admin"])
    assert r.status_code == 200

    # Reset-Auftrag (legitim, EDM)
    r = client.post("/api/v1/edm/devices/CT45P-01/reset", json={
        "state": "RESET_PENDING", "reason": "Mitarbeiter ausgeschieden",
    }, headers=headers["admin"])
    assert r.status_code == 200
    assert r.json()["device"]["state"] == "RESET_PENDING"

    r = client.get("/api/v1/edm/audit", headers=headers["device"])
    actions = [e["action"] for e in r.json()["entries"]]
    assert actions == ["REGISTER", "SET_STATE", "SET_STATE"]

    # Nicht-Admin darf nichts ändern
    r = client.post("/api/v1/edm/devices/CT45P-01/state", json={
        "state": "RESET", "reason": "x",
    }, headers=headers["device"])
    assert r.status_code == 403

    # Bestätigung über den legitimen EDM-Weg (RESET_PENDING → RESET) ist erlaubt
    r = client.post("/api/v1/edm/devices/CT45P-01/state", json={
        "state": "RESET", "reason": "Provisioning Mode durchgeführt",
    }, headers=headers["admin"])
    assert r.status_code == 200
    assert r.json()["device"]["state"] == "RESET"

    # Direkt-Reset aus ENROLLED (ohne EDM-Prozess) → 403
    client.post("/api/v1/edm/devices", json={"device_id": "CT45P-02"}, headers=headers["admin"])
    r = client.post("/api/v1/edm/devices/CT45P-02/state", json={
        "state": "RESET", "reason": "direkt ohne EDM-Prozess",
    }, headers=headers["admin"])
    assert r.status_code == 403


# ─── Sync-Queue ──────────────────────────────────────────────

def test_sync_queue_roundtrip(authenticated_client):
    client, headers = authenticated_client
    r = client.post("/api/v1/sync/queue", json={
        "device_id": "CT45P-01", "kind": "mesh_observation", "payload": {"n": 3},
    }, headers=headers["device"])
    assert r.status_code == 200
    item_id = r.json()["item_id"]

    r = client.get("/api/v1/sync/next?device_id=CT45P-01", headers=headers["device"])
    assert r.status_code == 200
    assert len(r.json()["items"]) == 1

    r = client.post(f"/api/v1/sync/{item_id}/ack", json={
        "device_id": "CT45P-01", "kind": "mesh_observation", "payload": {},
    }, headers=headers["device"])
    assert r.json()["acknowledged"] == 1

    r = client.get("/api/v1/sync/next?device_id=CT45P-01", headers=headers["device"])
    assert r.json()["items"] == []

    # Scope-Isolation
    r = client.get("/api/v1/sync/next?device_id=OTHER-DEVICE", headers=headers["device"])
    assert r.status_code == 403


# ─── Flotten-Gruppen + Historie ──────────────────────────────

def test_fleet_groups_and_history(authenticated_client):
    client, headers = authenticated_client
    _seed_fleet()
    fleet_registry.upsert(FleetVehicle(id="veh-2", name="Scooter 2", kind="escooter", lat=52.51, lon=13.39))

    r = client.post("/api/v1/fleet/groups", json={
        "name": "Außendienst Nord", "vehicle_ids": ["veh-1", "veh-2"],
    }, headers=headers["admin"])
    assert r.status_code == 200
    group_id = r.json()["group"]["id"]

    r = client.get("/api/v1/fleet/groups", headers=headers["device"])
    groups = r.json()["groups"]
    assert len(groups) == 1
    assert {v["id"] for v in groups[0]["vehicles"]} == {"veh-1", "veh-2"}
    assert all(v["group"] == group_id for v in groups[0]["vehicles"])

    # Unbekanntes Fahrzeug in Gruppe → 404
    r = client.post("/api/v1/fleet/groups", json={
        "name": "Kaputt", "vehicle_ids": ["gibt-es-nicht"],
    }, headers=headers["admin"])
    assert r.status_code == 404

    # Historie
    db.save_transform("veh-1", (1.0, 2.0, 3.0), (0.1, 0.1), {"kind": "triangulation"})
    db.save_transform("veh-1", (1.1, 2.1, 3.0), (0.1, 0.1), {"kind": "triangulation"})
    r = client.get("/api/v1/fleet/veh-1/history?limit=10", headers=headers["device"])
    assert r.status_code == 200
    assert len(r.json()["records"]) == 2

    r = client.get("/api/v1/fleet/unbekannt/history", headers=headers["device"])
    assert r.status_code == 404

    # Gruppe löschen
    r = client.delete(f"/api/v1/fleet/groups/{group_id}", headers=headers["admin"])
    assert r.status_code == 200
    assert client.get("/api/v1/fleet/groups", headers=headers["device"]).json()["groups"] == []
