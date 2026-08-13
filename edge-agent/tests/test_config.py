import importlib

import pytest

import config


def test_secret_prefers_file_over_environment(tmp_path, monkeypatch):
    secret_file = tmp_path / "secret"
    secret_file.write_text("  from-file\n", encoding="utf-8")
    monkeypatch.setenv("TEST_SECRET", "from-environment")
    monkeypatch.setenv("TEST_SECRET_FILE", str(secret_file))

    assert config._secret("TEST_SECRET") == "from-file"


def test_secret_uses_environment_without_file(monkeypatch):
    monkeypatch.delenv("TEST_SECRET_FILE", raising=False)
    monkeypatch.setenv("TEST_SECRET", "from-environment")

    assert config._secret("TEST_SECRET") == "from-environment"


def test_secret_file_missing_or_oversized_fails_closed(tmp_path, monkeypatch):
    monkeypatch.setenv("TEST_SECRET_FILE", str(tmp_path / "missing"))
    with pytest.raises(FileNotFoundError):
        config._secret("TEST_SECRET")

    oversized = tmp_path / "oversized"
    oversized.write_text("x" * (config._MAX_SECRET_CHARS + 1), encoding="utf-8")
    monkeypatch.setenv("TEST_SECRET_FILE", str(oversized))
    with pytest.raises(ValueError, match="unexpectedly large"):
        config._secret("TEST_SECRET")


def test_security_and_mqtt_defaults_fail_closed(monkeypatch):
    for name in (
        "AGENT_AUTH_SIGNING_SECRET",
        "AGENT_AUTH_SIGNING_SECRET_FILE",
        "AGENT_ADMIN_BOOTSTRAP_TOKEN",
        "AGENT_ADMIN_BOOTSTRAP_TOKEN_FILE",
        "AGENT_MQTT_ENABLED",
        "AGENT_MQTT_PASSWORD",
        "AGENT_MQTT_PASSWORD_FILE",
        "AGENT_REQUIRE_TLS",
        "AGENT_CORS_ORIGINS",
    ):
        monkeypatch.delenv(name, raising=False)

    reloaded = importlib.reload(config)
    assert reloaded.CONFIG.AUTH_SIGNING_SECRET == ""
    assert reloaded.CONFIG.ADMIN_BOOTSTRAP_TOKEN == ""
    assert reloaded.CONFIG.MQTT_PASSWORD == ""
    assert reloaded.CONFIG.MQTT_ENABLED is False
    assert reloaded.CONFIG.REQUIRE_TLS is True
    assert reloaded.CONFIG.CORS_ORIGINS == ()
