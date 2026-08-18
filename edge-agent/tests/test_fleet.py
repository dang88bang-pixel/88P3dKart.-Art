"""Flotten-Feature: Registry, GPS/Triangulation/BLE-Position, Nearby, Aktionen."""
import time
import uuid

import pytest
from fastapi.testclient import TestClient

from agent import app, fleet_registry, geo_resolver
from alarm_repository import AlarmRepository
from alarm_service import AlarmService
from fleet import FleetRegistry, FleetVehicle, _local_to_geodetic
from models import GeoAnchor, GeoFix
from security import CredentialAttemptControls, CredentialStore, GatewaySecurity

BERLIN = (52.5163, 13.3777)


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
def clean_registry():
    fleet_registry._vehicles.clear()
    geo_resolver._anchor = None
    yield
    fleet_registry._vehicles.clear()
    geo_resolver._anchor = None


# ─── Registry-Logik ──────────────────────────────────────────

def test_gps_ingest_sets_position_directly():
    reg = FleetRegistry()
    v = reg.update_from_payload({"id": "eb-1", "kind": "ebike", "lat": 52.5, "lon": 13.4, "accuracy_m": 4.0, "battery": 82})
    assert v.lat == 52.5 and v.lon == 13.4
    assert v.source == "gps"
    assert v.accuracy_m == 4.0
    assert reg.stats()["total"] == 1


def test_ble_ingest_sets_distance_estimate_but_no_position():
    reg = FleetRegistry()
    v = reg.update_from_payload({"id": "tok-1", "kind": "ble_token", "rssi": -65})
    assert v.lat is None
    assert v.distance_m == pytest.approx(10 ** ((-59 + 65) / 20), rel=1e-9)  # ≈ 2.0 m
    assert v.source == "ble"
    assert reg.stats()["unlocated"] == 1


def test_local_ingest_projects_via_anchor():
    reg = FleetRegistry()
    anchor = GeoAnchor(
        fix=GeoFix(lat=BERLIN[0], lon=BERLIN[1], accuracy_m=2.0, source="manual", license="n/a", timestamp=0.0, quality=1.0),
        local_origin=[0.0, 0.0, 0.0],
        heading_deg=0.0,
    )
    v = reg.update_from_payload(
        {"id": "eb-2", "kind": "ebike", "local": [10.0, 0.0, 0.0], "accuracy_m": 2.0},
        anchor=anchor,
    )
    # +10 m Ost vom Anker (Heading 0): Breite ~gleich, Länge größer
    assert v.source == "triangulation"
    assert v.lat == pytest.approx(BERLIN[0], abs=0.0005)
    assert v.lon > BERLIN[1]


def test_nearby_sorted_by_distance():
    reg = FleetRegistry()
    reg.update_from_payload({"id": "a", "kind": "tool", "lat": 52.5163, "lon": 13.3777})
    reg.update_from_payload({"id": "b", "kind": "phone", "lat": 52.5600, "lon": 13.3777})  # ~4.9 km
    nearby = reg.nearby(52.5163, 13.3777, 6000)
    assert [vid for v, _ in nearby for vid in [v.id]] == ["a", "b"]
    assert nearby[0][1] < nearby[1][1]
    # Radius greift
    assert len(reg.nearby(52.5163, 13.3777, 100)) == 1


def test_actions_capability_gated():
    reg = FleetRegistry()
    ebike = reg.update_from_payload({"id": "eb-1", "kind": "ebike", "lat": 52.5, "lon": 13.4})
    token = reg.update_from_payload({"id": "tok-1", "kind": "ble_token", "rssi": -60})
    assert "lock" in reg.available_actions(ebike)
    assert "lock" not in reg.available_actions(token)
    res = reg.execute_action("eb-1", "lock")
    assert res["success"] is True and "Schloss" in res["message"]
    with pytest.raises(PermissionError):
        reg.execute_action("tok-1", "lock")
    res = reg.execute_action("eb-1", "locate")
    assert res["data"]["lat"] == 52.5


# ─── REST-Endpunkte ──────────────────────────────────────────

def test_fleet_rest_flow(authenticated_client):
    client, headers = authenticated_client
    # Anchor setzen (Admin)
    r = client.post("/api/v1/fleet/anchor", json={
        "lat": BERLIN[0], "lon": BERLIN[1], "heading_deg": 0.0,
    }, headers=headers["admin"])
    assert r.status_code == 200
    r = client.get("/api/v1/fleet/anchor", headers=headers["device"])
    assert r.json()["anchor"]["lat"] == pytest.approx(BERLIN[0])

    # GPS-Fahrzeug + BLE-Token upserten
    r = client.post("/api/v1/fleet/upsert", json={
        "device_id": "CT45P-01", "timestamp": time.time(),
        "vehicles": [
            {"id": "eb-1", "name": "Flotten-Bike 1", "kind": "ebike", "lat": 52.52, "lon": 13.40, "battery": 85},
            {"id": "tok-1", "name": "Werkzeug-Token", "kind": "ble_token", "rssi": -58},
        ],
    }, headers=headers["device"])
    assert r.status_code == 200 and r.json()["updated"] == 2

    # Liste
    r = client.get("/api/v1/fleet", headers=headers["device"])
    body = r.json()
    assert body["count"] == 2
    assert body["stats"]["by_kind"]["ebike"] == 1
    by_id = {v["id"]: v for v in body["vehicles"]}
    assert by_id["eb-1"]["lat"] == 52.52
    assert by_id["tok-1"]["source"] == "ble"

    # Nearby (mit BLE-Zubehör kombiniert)
    r = client.post("/api/v1/bluetooth/accessories/update", json={
        "device_id": "CT45P-01", "timestamp": time.time(),
        "accessories": [{"mac": "aa:bb:cc:dd:ee:01", "type": "TOKEN_PRO", "rssi": -50, "battery": 70}],
    }, headers=headers["device"])
    assert r.status_code == 200
    r = client.get("/api/v1/fleet/nearby?lat=52.5163&lon=13.3777&radius_m=3000", headers=headers["device"])
    body = r.json()
    ids = {e["id"] for e in body["entries"]}
    assert "eb-1" in ids and "aa:bb:cc:dd:ee:01" in ids

    # Aktion: lock (Fahrzeug) + capability-Gate (Token darf nicht)
    r = client.post("/api/v1/fleet/eb-1/action", json={"action": "lock"}, headers=headers["device"])
    assert r.status_code == 200 and r.json()["success"] is True
    r = client.post("/api/v1/fleet/tok-1/action", json={"action": "lock"}, headers=headers["device"])
    assert r.status_code == 403

    # Scope: fremdes Fahrzeug
    r = client.post("/api/v1/fleet/upsert", json={
        "device_id": "OTHER-DEVICE", "timestamp": time.time(),
        "vehicles": [{"id": "x-1", "kind": "tool", "lat": 52.5, "lon": 13.4}],
    }, headers=headers["device"])
    assert r.status_code == 403

    # Delete
    r = client.delete("/api/v1/fleet/eb-1", headers=headers["device"])
    assert r.status_code == 200
    assert fleet_registry.get("eb-1") is None


def test_fleet_websocket_position_ingest(authenticated_client):
    """fleet_position über den WS → Registry + fleet_update-Broadcast."""
    client, headers = authenticated_client
    with client.websocket_connect(
        "wss://testserver/ws/agent/events", headers=headers["device"]
    ) as ws:
        ws.send_json({"type": "handshake", "payload": {"device_id": "CT45P-01"}})
        assert ws.receive_json()["type"] == "handshake_ack"
        ws.send_json({"type": "fleet_position", "payload": {
            "device_id": "CT45P-01",
            "id": "sc-9",
            "name": "Scooter 9",
            "kind": "escooter",
            "lat": 52.53,
            "lon": 13.41,
            "battery": 42,
        }})
        update = ws.receive_json()
        assert update["type"] == "fleet_update"
        assert update["payload"]["vehicles"][0]["id"] == "sc-9"
        assert fleet_registry.get("sc-9").lat == 52.53
