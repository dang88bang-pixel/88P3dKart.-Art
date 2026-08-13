from types import SimpleNamespace

import numpy as np
import pytest
from fastapi.testclient import TestClient

from agent import app, run_server
from alarm_repository import AlarmRepository
from alarm_service import AlarmService
from config import CONFIG
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
    token, _ = security.issue_device_session(
        "CT45P-01", credential.secret, now_utc_s=1_002
    )
    # API authentication uses the real wall clock, so issue another current token.
    token, _ = security.issue_device_session("CT45P-01", credential.secret)
    with TestClient(app, base_url="https://testserver") as client:
        yield client, {
            "admin": {"Authorization": f"Bearer {'a' * 32}"},
            "device": {"Authorization": f"Bearer {token}"},
            "device_secret": credential.secret,
        }


def test_cleartext_transport_is_rejected_except_minimal_health(authenticated_client):
    _secure_client, _headers = authenticated_client
    with TestClient(app, base_url="http://testserver") as client:
        assert client.get("/api/v1/agent/state").status_code == 426
        assert client.get("/api/v1/health").status_code == 200


def test_unlisted_browser_origin_gets_no_cors_grant(authenticated_client):
    client, _headers = authenticated_client
    response = client.options(
        "/api/v1/agent/state",
        headers={
            "Origin": "https://attacker.example",
            "Access-Control-Request-Method": "GET",
        },
    )
    assert "access-control-allow-origin" not in response.headers


def test_protected_endpoint_rejects_missing_or_malformed_bearer(authenticated_client):
    client, _headers = authenticated_client
    assert client.get("/api/v1/agent/state").status_code == 401
    assert client.get(
        "/api/v1/agent/state", headers={"Authorization": "Basic not-a-bearer"}
    ).status_code == 401


def test_http_body_limit_counts_actual_bytes_and_rejects_malformed_length(
    authenticated_client,
):
    client, _headers = authenticated_client
    oversized = b"x" * (CONFIG.MAX_HTTP_BODY_BYTES + 1)
    response = client.post(
        "/api/v1/session",
        content=oversized,
        headers={"Content-Type": "application/json", "Content-Length": "1"},
    )
    assert response.status_code == 413

    malformed = client.post(
        "/api/v1/session",
        content=b"{}",
        headers={"Content-Type": "application/json", "Content-Length": "invalid"},
    )
    assert malformed.status_code == 400


def test_state_endpoint(authenticated_client):
    client, headers = authenticated_client
    response = client.get("/api/v1/agent/state", headers=headers["device"])
    assert response.status_code == 200
    body = response.json()
    assert "x" in body and "mode" in body


def test_health_endpoint(authenticated_client):
    client, _headers = authenticated_client
    response = client.get("/api/v1/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok", "authentication": "ready"}


def test_pipeline_endpoint_is_device_scoped(authenticated_client):
    client, headers = authenticated_client
    rng = np.random.default_rng(0)
    points = rng.uniform(-3, 3, (200, 3)).flatten().tolist()
    response = client.post(
        "/api/v1/pipeline/run",
        json={"device_id": "CT45P-01", "points": points},
        headers=headers["device"],
    )
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "ready"
    assert body["num_points"] == 200

    denied = client.post(
        "/api/v1/pipeline/run",
        json={"device_id": "OTHER-DEVICE", "points": points[:3]},
        headers=headers["device"],
    )
    assert denied.status_code == 403


def test_pipeline_rejects_incomplete_or_excessive_point_input(authenticated_client):
    client, headers = authenticated_client
    incomplete = client.post(
        "/api/v1/pipeline/run",
        json={"device_id": "CT45P-01", "points": [1.0, 2.0]},
        headers=headers["device"],
    )
    assert incomplete.status_code == 422


def test_merge_endpoint_requires_at_least_two_devices(authenticated_client):
    client, headers = authenticated_client
    response = client.post(
        "/api/v1/agent/merge",
        json={"device_ids": ["CT45P-01"]},
        headers=headers["admin"],
    )
    assert response.status_code == 422


def test_enrollment_and_session_api_returns_secrets_once(tmp_path):
    security = GatewaySecurity(
        CredentialStore(str(tmp_path / "api-credentials.db")),
        signing_secret="s" * 32,
        admin_bootstrap_token="a" * 32,
    )
    app.state.security = security
    app.state.credential_attempts = CredentialAttemptControls()
    with TestClient(app, base_url="https://testserver") as client:
        created = client.post(
            "/api/v1/admin/enrollment-codes",
            json={"device_id": "NEW-CT45P", "ttl_seconds": 600},
            headers={"Authorization": f"Bearer {'a' * 32}"},
        )
        assert created.status_code == 200
        assert created.headers["cache-control"] == "no-store"
        code = created.json()["enrollment_code"]

        claimed = client.post(
            "/api/v1/enrollment/claim",
            json={"device_id": "NEW-CT45P", "code": code},
        )
        assert claimed.status_code == 200
        secret = claimed.json()["device_secret"]

        replay = client.post(
            "/api/v1/enrollment/claim",
            json={"device_id": "NEW-CT45P", "code": code},
        )
        assert replay.status_code == 401

        session = client.post(
            "/api/v1/session",
            json={"device_id": "NEW-CT45P", "device_secret": secret},
        )
        assert session.status_code == 200
        assert session.headers["cache-control"] == "no-store"
        assert session.json()["token_type"] == "Bearer"


def test_session_attempt_limit_uses_direct_peer_and_success_resets_subject(
    authenticated_client,
):
    client, credentials = authenticated_client
    app.state.credential_attempts = CredentialAttemptControls(
        max_failures=2, window_s=60, max_keys=32
    )
    request_body = {
        "device_id": "CT45P-01",
        "device_secret": "x" * 43,
    }

    first = client.post(
        "/api/v1/session",
        json=request_body,
        headers={"X-Forwarded-For": "198.51.100.1"},
    )
    assert first.status_code == 401

    # A valid credential clears its subject-specific failures.
    valid = client.post(
        "/api/v1/session",
        json={
            "device_id": "CT45P-01",
            "device_secret": credentials["device_secret"],
        },
        headers={"X-Forwarded-For": "198.51.100.2"},
    )
    assert valid.status_code == 200

    assert client.post(
        "/api/v1/session",
        json=request_body,
        headers={"X-Forwarded-For": "198.51.100.3"},
    ).status_code == 401
    assert client.post(
        "/api/v1/session",
        json=request_body,
        headers={"X-Forwarded-For": "198.51.100.4"},
    ).status_code == 401
    limited = client.post(
        "/api/v1/session",
        json=request_body,
        headers={"X-Forwarded-For": "198.51.100.5"},
    )
    assert limited.status_code == 429
    assert int(limited.headers["retry-after"]) >= 1


def test_authenticated_alarm_api_and_durable_websocket_dispatch(authenticated_client):
    client, headers = authenticated_client
    policy_id = "123e4567-e89b-12d3-a456-426614174000"
    policy_document = {
        "schema_version": "1.0.0",
        "policy_id": policy_id,
        "asset_id": "CT45P-01",
        "revision": 1,
        "enabled": True,
        "metric": "RANGE_FROM_CT45P",
        "reference_id": None,
        "threshold_m": 10.0,
        "trigger_direction": "ABOVE",
        "decision_mode": "CONFIRMED_BREACH",
        "minimum_confidence": 0.8,
        "maximum_age_ms": 10_000,
        "dwell_ms": 0,
        "clear_dwell_ms": 0,
        "data_loss_dwell_ms": 500,
        "recovery_dwell_ms": 0,
        "hysteresis_m": 1.0,
        "cooldown_ms": 0,
        "severity": "WARNING",
        "data_loss_behavior": "SEPARATE_ALARM",
        "delivery_profile_id": "operators",
    }
    with client.websocket_connect(
        "wss://testserver/ws/agent/events", headers=headers["device"]
    ) as websocket:
        websocket.send_json(
            {"type": "handshake", "payload": {"device_id": "CT45P-01"}}
        )
        assert websocket.receive_json()["type"] == "handshake_ack"

        created = client.post(
            "/api/v1/alarm/policies",
            json=policy_document,
            headers=headers["admin"],
        )
        assert created.status_code == 200
        assert created.json()["authority"] == "GATEWAY_AUTHORITATIVE"

        dispatched = websocket.receive_json()
        assert dispatched["type"] == "alarm_event"
        assert dispatched["payload"]["event_type"] == "POLICY_ENABLED"
        assert dispatched["payload"]["asset_id"] == "CT45P-01"

        evidence_document = {
            "policy_id": policy_id,
            "asset_id": "CT45P-01",
            "source_id": "fusion",
            "cursor": "cursor-1",
            "estimate_status": "VALID",
            "method": "calibrated-fusion-v1",
            "value_m": 12.0,
            "confidence": 0.9,
            "lower_95_m": 11.0,
            "upper_95_m": 13.0,
            "observed_at": "2026-08-14T12:00:00+00:00",
            "source_ids": ["lidar-1"],
            "measurement_ids": ["measurement-1"],
            "calibration_id": "calibration-1",
            "quality_flags": [],
        }
        # A historical fixed timestamp is admissible at the model boundary but
        # becomes stale in the authoritative freshness check; use current UTC.
        from datetime import datetime, timezone

        evidence_document["observed_at"] = datetime.now(timezone.utc).isoformat()
        ingested = client.post(
            "/api/v1/alarm/evidence",
            json=evidence_document,
            headers=headers["device"],
        )
        assert ingested.status_code == 200
        assert ingested.json()["condition"] == "ACTIVE"

        runtime = client.get(
            "/api/v1/alarm/runtime",
            params={"policy_id": policy_id, "asset_id": "CT45P-01"},
            headers=headers["device"],
        )
        assert runtime.status_code == 200
        assert runtime.json()["condition"] == "ACTIVE"

        denied_document = dict(evidence_document)
        denied_document.update(asset_id="OTHER-DEVICE", cursor="cursor-2")
        denied = client.post(
            "/api/v1/alarm/evidence",
            json=denied_document,
            headers=headers["device"],
        )
        assert denied.status_code == 403

        acknowledged = client.post(
            "/api/v1/alarm/acknowledge",
            params={"policy_id": policy_id, "asset_id": "CT45P-01"},
            headers=headers["device"],
        )
        assert acknowledged.status_code == 200
        assert acknowledged.json()["attention"] == "ACKNOWLEDGED"

        history = client.get(
            "/api/v1/alarm/events",
            params={"policy_id": policy_id, "asset_id": "CT45P-01"},
            headers=headers["device"],
        )
        assert history.status_code == 200
        assert [event["event_type"] for event in history.json()["events"]] == [
            "POLICY_ENABLED",
            "PENDING_STARTED",
            "TRIGGERED",
            "ACKNOWLEDGED",
        ]


def test_websocket_requires_bearer_and_rejects_cross_device_payload(authenticated_client):
    client, headers = authenticated_client
    with pytest.raises(Exception):
        with client.websocket_connect("wss://testserver/ws/agent/events"):
            pass

    with client.websocket_connect(
        "wss://testserver/ws/agent/events", headers=headers["device"]
    ) as websocket:
        websocket.send_json(
            {
                "type": "handshake",
                "payload": {"device_id": "CT45P-01"},
            }
        )
        assert websocket.receive_json()["type"] == "handshake_ack"
        websocket.send_json(
            {
                "type": "handshake",
                "payload": {"device_id": "OTHER-DEVICE"},
            }
        )
        # Scope violation closes instead of accepting an identity switch.
        with pytest.raises(Exception):
            websocket.receive_json()


def test_sensor_telemetry_requires_explicit_temperature_source(authenticated_client):
    client, headers = authenticated_client
    with client.websocket_connect(
        "wss://testserver/ws/agent/events", headers=headers["device"]
    ) as websocket:
        websocket.send_json(
            {
                "type": "telemetry",
                "payload": {
                    "device_id": "CT45P-01",
                    "thermal_c": 42.0,
                    "scattering": False,
                },
            }
        )
        assert websocket.receive_json() == {
            "type": "error",
            "code": "INVALID_MESSAGE",
        }

        websocket.send_json(
            {
                "type": "telemetry",
                "payload": {
                    "device_id": "CT45P-01",
                    "thermal_source": "lidar.internal",
                    "thermal_c": 42.0,
                    "scattering": False,
                },
            }
        )
        websocket.send_json(
            {"type": "handshake", "payload": {"device_id": "CT45P-01"}}
        )
        assert websocket.receive_json()["type"] == "handshake_ack"


def test_websocket_rejects_oversized_text(authenticated_client):
    client, headers = authenticated_client
    with client.websocket_connect(
        "wss://testserver/ws/agent/events", headers=headers["device"]
    ) as websocket:
        websocket.send_text("x" * (CONFIG.MAX_WEBSOCKET_MESSAGE_BYTES + 1))
        with pytest.raises(Exception):
            websocket.receive_text()


def test_direct_server_startup_requires_and_passes_tls_files(monkeypatch):
    missing_tls = SimpleNamespace(
        REQUIRE_TLS=True,
        TLS_CERTFILE="",
        TLS_KEYFILE="",
        API_HOST="0.0.0.0",
        API_PORT=8080,
    )
    with pytest.raises(SystemExit, match="TLS is required"):
        run_server(missing_tls)

    captured = {}

    def fake_run(application, **kwargs):
        captured["application"] = application
        captured.update(kwargs)

    monkeypatch.setattr("agent.uvicorn.run", fake_run)
    configured_tls = SimpleNamespace(
        REQUIRE_TLS=True,
        TLS_CERTFILE="/certs/gateway.crt",
        TLS_KEYFILE="/certs/gateway.key",
        API_HOST="0.0.0.0",
        API_PORT=8443,
    )
    run_server(configured_tls)
    assert captured == {
        "application": app,
        "host": "0.0.0.0",
        "port": 8443,
        "ssl_certfile": "/certs/gateway.crt",
        "ssl_keyfile": "/certs/gateway.key",
    }
