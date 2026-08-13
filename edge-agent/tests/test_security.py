import sqlite3

import pytest

from security import (
    AttemptLimitExceeded,
    AuthenticationError,
    AuthenticationUnavailable,
    CredentialAttemptControls,
    CredentialStore,
    GatewaySecurity,
)


def configured_security(tmp_path, ttl=900):
    return GatewaySecurity(
        CredentialStore(str(tmp_path / "credentials.db")),
        signing_secret="signing-secret-with-at-least-32-bytes",
        admin_bootstrap_token="admin-bootstrap-token-at-least-32-bytes",
        session_ttl_s=ttl,
    )


def enroll(security, device_id="CT45P-01", now=1_000):
    enrollment = security.store.create_enrollment_code(device_id, now, 600)
    credential = security.store.claim_enrollment(
        device_id, enrollment.code, now + 1
    )
    return enrollment, credential


def test_unconfigured_security_fails_closed(tmp_path):
    security = GatewaySecurity(
        CredentialStore(str(tmp_path / "credentials.db")), "short", "short"
    )
    assert not security.available
    with pytest.raises(AuthenticationUnavailable):
        security.authenticate_bearer("anything", now_utc_s=1_000)
    with pytest.raises(AuthenticationUnavailable):
        security.require_admin("anything")


def test_enrollment_code_is_one_time_and_raw_secrets_are_not_stored(tmp_path):
    security = configured_security(tmp_path)
    enrollment, credential = enroll(security)

    with pytest.raises(AuthenticationError):
        security.store.claim_enrollment(
            enrollment.device_id, enrollment.code, 1_002
        )

    database_bytes = (tmp_path / "credentials.db").read_bytes()
    assert enrollment.code.encode() not in database_bytes
    assert credential.secret.encode() not in database_bytes
    assert (tmp_path / "credentials.db").stat().st_mode & 0o077 == 0


def test_expired_or_wrong_enrollment_code_is_rejected(tmp_path):
    security = configured_security(tmp_path)
    enrollment = security.store.create_enrollment_code("CT45P-01", 1_000, 60)
    with pytest.raises(AuthenticationError):
        security.store.claim_enrollment("CT45P-01", enrollment.code, 1_061)
    with pytest.raises(AuthenticationError):
        security.store.claim_enrollment("CT45P-01", "x" * 32, 1_001)


def test_session_signature_expiry_and_device_generation_are_verified(tmp_path):
    security = configured_security(tmp_path, ttl=60)
    _enrollment, credential = enroll(security)
    token, expires = security.issue_device_session(
        credential.device_id, credential.secret, now_utc_s=2_000
    )
    assert expires == 2_060
    principal = security.authenticate_bearer(token, now_utc_s=2_030)
    assert principal.subject == credential.device_id
    assert principal.role == "device"

    with pytest.raises(AuthenticationError, match="expired"):
        security.authenticate_bearer(token, now_utc_s=2_060)
    with pytest.raises(AuthenticationError):
        security.authenticate_bearer(token + "tampered", now_utc_s=2_030)

    # Re-enrollment rotates the verifier generation and revokes old sessions.
    second_code = security.store.create_enrollment_code("CT45P-01", 2_100, 600)
    security.store.claim_enrollment("CT45P-01", second_code.code, 2_101)
    with pytest.raises(AuthenticationError, match="revoked"):
        security.authenticate_bearer(token, now_utc_s=2_030)


def test_disabling_device_immediately_revokes_stateless_session(tmp_path):
    security = configured_security(tmp_path)
    _enrollment, credential = enroll(security)
    token, _ = security.issue_device_session(
        credential.device_id, credential.secret, now_utc_s=2_000
    )
    assert security.store.disable_device(credential.device_id)
    with pytest.raises(AuthenticationError, match="revoked"):
        security.authenticate_bearer(token, now_utc_s=2_001)


def test_admin_bootstrap_token_has_explicit_admin_principal(tmp_path):
    security = configured_security(tmp_path)
    principal = security.require_admin("admin-bootstrap-token-at-least-32-bytes")
    assert principal.role == "admin"
    assert security.authenticate_bearer(
        "admin-bootstrap-token-at-least-32-bytes", now_utc_s=1_000
    ).role == "admin"
    with pytest.raises(AuthenticationError):
        security.require_admin("wrong" * 10)


def test_attempt_controls_exhaust_reset_and_expire():
    now = [100.0]
    controls = CredentialAttemptControls(
        max_failures=2,
        window_s=60,
        max_keys=4,
        clock=lambda: now[0],
    )

    controls.ensure_allowed("session", "direct-peer", "device-a")
    controls.failure("session", "direct-peer", "device-a")
    controls.ensure_allowed("session", "direct-peer", "device-a")
    controls.failure("session", "direct-peer", "device-a")
    with pytest.raises(AttemptLimitExceeded) as limited:
        controls.ensure_allowed("session", "direct-peer", "device-a")
    assert limited.value.retry_after_s == 60

    # Window expiration makes the credential subject eligible again.
    now[0] += 61
    controls.ensure_allowed("session", "direct-peer", "device-a")
    controls.failure("session", "direct-peer", "device-a")
    controls.success("session", "direct-peer", "device-a")
    controls.ensure_allowed("session", "direct-peer", "device-a")
