"""honeyKart-Integration: QR-Token-Anbindung + semantische Klassifikation (API)."""
import time

import numpy as np
import pytest
from fastapi.testclient import TestClient

from agent import app, fleet_registry
from alarm_repository import AlarmRepository
from alarm_service import AlarmService
from security import CredentialAttemptControls, CredentialStore, GatewaySecurity

QR_PAYLOAD = {
    "token_id": "TKN-001",
    "mac": "AA:BB:CC:DD:EE:01",
    "name": "AURA-Token-001",
    "pairing_code": "123456",
    "company_id": "0059",
    "battery_type": "CR2032",
    "firmware_version": "v1.2.0",
}


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
def clean_fleet():
    fleet_registry._vehicles.clear()
    fleet_registry._kalman.clear()
    yield
    fleet_registry._vehicles.clear()
    fleet_registry._kalman.clear()


# ─── Registry-Ebene ──────────────────────────────────────────

def test_bind_token_from_qr_creates_vehicle():
    v = fleet_registry.bind_token_from_qr(dict(QR_PAYLOAD), owner="CT45P-01")
    assert v.id == "token:aa:bb:cc:dd:ee:01"
    assert v.kind == "ble_token"
    assert v.source == "qr_bound"
    assert v.name == "AURA-Token-001"
    assert v.pairing_code == "123456"
    assert v.company_id == "0059"
    assert v.battery_type == "CR2032"
    assert v.firmware_version == "v1.2.0"


def test_bind_token_rebind_updates_metadata():
    fleet_registry.bind_token_from_qr(dict(QR_PAYLOAD), owner="CT45P-01")
    updated = dict(QR_PAYLOAD)
    updated["name"] = "AURA-Token-v2"
    updated["firmware_version"] = "v1.3.0"
    v = fleet_registry.bind_token_from_qr(updated, owner="CT45P-01")
    assert v.name == "AURA-Token-v2"
    assert v.firmware_version == "v1.3.0"
    assert len(fleet_registry.get_all()) == 1


def test_bind_token_requires_token_id():
    with pytest.raises(ValueError):
        fleet_registry.bind_token_from_qr({"mac": "AA:BB:CC:DD:EE:02"})


# ─── API-Ebene ───────────────────────────────────────────────

def test_fleet_bind_qr_endpoint(authenticated_client):
    client, headers = authenticated_client
    r = client.post("/api/v1/fleet/bind-qr", json=QR_PAYLOAD, headers=headers["device"])
    assert r.status_code == 200
    body = r.json()
    assert body["status"] == "bound"
    assert body["vehicle"]["kind"] == "ble_token"
    assert body["vehicle"]["source"] == "qr_bound"
    # In der Flottenliste sichtbar
    r = client.get("/api/v1/fleet", headers=headers["device"])
    assert r.json()["count"] == 1
    # Ohne token_id → 422
    r = client.post("/api/v1/fleet/bind-qr", json={"mac": "AA:BB:CC:DD:EE:02"},
                    headers=headers["device"])
    assert r.status_code == 422


def test_semantic_classify_endpoint(authenticated_client):
    client, headers = authenticated_client
    rng = np.random.default_rng(4)
    # Planare Wand
    wall = np.column_stack([
        rng.uniform(-2, 2, 500), 2.0 + rng.normal(0, 0.02, 500),
        rng.uniform(0.6, 2.4, 500),
    ])
    # Volumetrischer Blob
    blob = rng.normal(0, 0.12, (300, 3))
    blob[:, 0] += 0.5
    blob[:, 1] += -1.0
    blob[:, 2] = np.clip(blob[:, 2] + 1.2, 0.6, 2.4)
    points = np.vstack([wall, blob]).flatten().tolist()

    r = client.post("/api/v1/semantic/classify", json={
        "device_id": "CT45P-01", "points": points,
    }, headers=headers["device"])
    assert r.status_code == 200
    body = r.json()
    labels = {c["label"] for c in body["clusters"]}
    assert "wall" in labels and "dynamic" in labels
    dynamic = [c for c in body["clusters"] if c["label"] == "dynamic"]
    assert all(c["persistable"] is False for c in dynamic)
    assert body["live_only_clusters"] >= 1
    assert body["persistable_clusters"] >= 1
    # Ungültige Punktzahl (nicht durch 3 teilbar) → 422
    r = client.post("/api/v1/semantic/classify", json={
        "device_id": "CT45P-01", "points": [1.0, 2.0],
    }, headers=headers["device"])
    assert r.status_code == 422
