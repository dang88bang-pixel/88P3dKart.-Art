from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


def test_compose_does_not_publish_or_privilege_cleartext_edge_agent():
    compose = (ROOT / "docker-compose.yml").read_text(encoding="utf-8")
    edge_section = compose.split("  edge-agent:", 1)[1].split("  nginx:", 1)[0]

    assert "privileged:" not in edge_section
    assert "    ports:" not in edge_section
    assert 'AGENT_REQUIRE_TLS: "false"' in edge_section
    assert "AGENT_AUTH_SIGNING_SECRET_FILE: /run/secrets/agent_signing_secret" in edge_section
    assert "AGENT_ALARM_DB_PATH: /data/alarms.db" in edge_section
    assert "AGENT_GATEWAY_ID:?Set a stable AGENT_GATEWAY_ID" in edge_section
    assert "cap_drop:\n      - ALL" in edge_section
    assert "read_only: true" in edge_section


def test_proxy_is_https_only_at_the_external_boundary():
    nginx = (ROOT / "nginx" / "nginx.conf").read_text(encoding="utf-8")

    assert "return 308 https://$host$request_uri;" in nginx
    assert "listen 443 ssl default_server;" in nginx
    assert 'Strict-Transport-Security "max-age=31536000" always' in nginx
    assert "client_max_body_size 4m;" in nginx
    assert "location /ws/agent/events" in nginx
    assert "proxy_set_header Authorization $http_authorization;" in nginx


def test_mqtt_defaults_have_no_anonymous_or_cleartext_listener():
    broker = (ROOT / "mosquitto" / "config" / "mosquitto.conf").read_text(
        encoding="utf-8"
    )
    compose = (ROOT / "docker-compose.yml").read_text(encoding="utf-8")

    assert "allow_anonymous true" not in broker
    assert "listener 1883" not in broker
    assert "listener 8883" in broker
    assert "require_certificate true" in broker
    assert "password_file /mosquitto/secrets/passwords" in broker
    assert 'profiles: ["mqtt"]' in compose
    assert '- "1883:1883"' not in compose


def test_edge_image_runs_as_unprivileged_user():
    dockerfile = (ROOT / "edge-agent" / "Dockerfile").read_text(encoding="utf-8")
    assert "USER 10001:10001" in dockerfile
