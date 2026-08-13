import numpy as np
from fastapi.testclient import TestClient

from agent import app


def test_state_endpoint():
    client = TestClient(app)
    r = client.get("/api/v1/agent/state")
    assert r.status_code == 200
    body = r.json()
    assert "x" in body and "mode" in body


def test_health_endpoint():
    client = TestClient(app)
    r = client.get("/api/v1/health")
    assert r.status_code == 200
    assert r.json()["status"] == "ok"


def test_pipeline_endpoint():
    client = TestClient(app)
    rng = np.random.default_rng(0)
    pts = rng.uniform(-3, 3, (200, 3)).flatten().tolist()
    r = client.post(
        "/api/v1/pipeline/run",
        json={"device_id": "CT45P-01", "points": pts},
    )
    assert r.status_code == 200
    body = r.json()
    assert body["status"] == "ready"
    assert body["num_points"] == 200


def test_merge_endpoint_requires_two_devices():
    client = TestClient(app)
    r = client.post("/api/v1/agent/merge", json={"device_ids": ["CT45P-01"]})
    assert r.status_code == 400
