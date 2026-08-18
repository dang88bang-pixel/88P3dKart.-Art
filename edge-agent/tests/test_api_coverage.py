"""API-Abdeckungstests für die neu implementierten Endpunkte (docs/API.md):

- GET  /api/v1/agent/mesh          (json/obj/ply/stl, Limit, 501 für glb/gltf/ifc)
- GET  /api/v1/agent/evaluation    (leer + mit Punkten)
- POST /api/v1/agent/scenario/start|stop
- GET  /api/v1/metrics             (Prometheus-Textformat, echte Zähler)
- POST /api/v1/devices/upsert      (String-Capabilities, kein 500 mehr)
"""
import uuid

import pytest
from fastapi.testclient import TestClient

from agent import app, db
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

    def enroll(device_id: str) -> dict:
        """Stellt für ein beliebiges Gerät einen Session-Header aus."""
        code = security.store.create_enrollment_code(device_id, 1_000, 600)
        cred = security.store.claim_enrollment(device_id, code.code, 1_001)
        t, _ = security.issue_device_session(device_id, cred.secret)
        return {"Authorization": f"Bearer {t}"}

    with TestClient(app, base_url="https://testserver") as client:
        yield client, {
            "admin": {"Authorization": f"Bearer {'a' * 32}"},
            "device": {"Authorization": f"Bearer {token}"},
            "enroll": enroll,
        }


def _seed_points(device_id: str, points):
    for x, y, z in points:
        db.save_transform(device_id, (x, y, z), (0.1, 0.1), {})


def test_mesh_endpoint_roundtrip(authenticated_client):
    client, headers = authenticated_client
    device_id = f"mesh-{uuid.uuid4().hex[:8]}"
    device_headers = headers["enroll"](device_id)
    _seed_points(device_id, [(1, 0, 0), (2, 0, 0), (1, 1, 0)])

    r = client.get(f"/api/v1/agent/mesh?device_id={device_id}", headers=device_headers)
    assert r.status_code == 200
    body = r.json()
    assert body["count"] == 3
    assert len(body["points"]) == 3
    assert "bounds" in body and "faces" in body

    for fmt, needle in (("obj", "o 3dxagent"), ("ply", "element vertex 3"), ("stl", "endsolid")):
        r = client.get(f"/api/v1/agent/mesh?device_id={device_id}&format={fmt}", headers=device_headers)
        assert r.status_code == 200
        assert needle in r.text

    r = client.get(f"/api/v1/agent/mesh?device_id={device_id}&format=glb", headers=device_headers)
    assert r.status_code == 501  # ehrliche Nicht-Implementierung

    r = client.get(f"/api/v1/agent/mesh?device_id={device_id}&limit=0", headers=device_headers)
    assert r.status_code == 422

    r = client.get(f"/api/v1/agent/mesh?device_id={device_id}&semantic_filter=person", headers=device_headers)
    assert r.status_code == 400  # Semantik-Filter ehrlich abgelehnt


def test_evaluation_endpoint_empty_and_ready(authenticated_client):
    client, headers = authenticated_client
    device_id = f"eval-{uuid.uuid4().hex[:8]}"
    device_headers = headers["enroll"](device_id)

    r = client.get(f"/api/v1/agent/evaluation?device_id={device_id}", headers=device_headers)
    assert r.status_code == 200
    assert r.json()["status"] == "empty"

    _seed_points(device_id, [(1, 0, 0), (2, 0, 0), (1, 1, 0), (0.5, 0.5, 0.2)])
    r = client.get(f"/api/v1/agent/evaluation?device_id={device_id}", headers=device_headers)
    assert r.status_code == 200
    body = r.json()
    assert body["status"] == "ready"
    assert body["num_points"] == 4
    assert 0 <= body["confidence"] <= 1


def test_scenario_lifecycle(authenticated_client):
    client, headers = authenticated_client
    r = client.post(
        "/api/v1/agent/scenario/start",
        json={"type": "architecture", "params": {"floor": 1}},
        headers=headers["admin"],
    )
    assert r.status_code == 200
    scenario_id = r.json()["scenario_id"]
    assert r.json()["status"] == "running"

    r = client.post(
        "/api/v1/agent/scenario/stop",
        json={"scenario_id": scenario_id},
        headers=headers["admin"],
    )
    assert r.status_code == 200
    body = r.json()
    assert body["status"] == "stopped"
    assert "duration_seconds" in body and "points_delta" in body

    r = client.post(
        "/api/v1/agent/scenario/stop",
        json={"scenario_id": "scn_unbekannt"},
        headers=headers["admin"],
    )
    assert r.status_code == 404


def test_metrics_endpoint_prometheus_format(authenticated_client):
    client, _headers = authenticated_client
    r = client.get("/api/v1/metrics")
    assert r.status_code == 200
    text = r.text
    assert "3dxagent_uptime_seconds" in text
    assert "3dxagent_ws_connections" in text
    assert "3dxagent_pipeline_runs_total" in text
    assert "3dxagent_lidar_frames_total" in text
    assert "3dxagent_devices_registered" in text
    assert "3dxagent_bluetooth_accessories" in text
    assert "3dxagent_active_scenarios" in text
    # Alle Zeilen gültig: HELP/TYPE-Kommentare oder metrik[labels] wert
    for line in text.splitlines():
        if not line.startswith("#") and line.strip():
            assert " " in line and not line.endswith(" ")


def test_devices_upsert_accepts_string_capabilities(authenticated_client):
    """Regression: String-Capabilities durften keinen 500er erzeugen."""
    client, headers = authenticated_client
    r = client.post(
        "/api/v1/devices/upsert",
        json={
            "device": {
                "id": f"dev-{uuid.uuid4().hex[:8]}",
                "name": "Sensor-A",
                "category": "sensor",
                "capabilities": ["STATUS"],
                "position": [1, 2, 3],
            }
        },
        headers=headers["device"],
    )
    assert r.status_code == 200
    assert r.json()["status"] == "ok"
