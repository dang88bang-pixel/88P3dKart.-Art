import mqtt_bridge
from mqtt_bridge import MqttBleBridge


def test_disabled_mqtt_never_constructs_a_client(monkeypatch):
    class ForbiddenClient:
        def __init__(self):
            raise AssertionError("disabled MQTT must not construct a network client")

    monkeypatch.setattr(mqtt_bridge, "MQTT_AVAILABLE", True)
    monkeypatch.setattr(mqtt_bridge.mqtt, "Client", ForbiddenClient)
    bridge = MqttBleBridge(enabled=False)

    bridge.start()

    assert bridge.client is None
    assert bridge.running is False
    assert bridge.available is False


def test_enabled_mqtt_fails_closed_for_incomplete_tls_credentials(monkeypatch):
    class ForbiddenClient:
        def __init__(self):
            raise AssertionError("invalid MQTT configuration must not construct a client")

    monkeypatch.setattr(mqtt_bridge, "MQTT_AVAILABLE", True)
    monkeypatch.setattr(mqtt_bridge.mqtt, "Client", ForbiddenClient)

    MqttBleBridge(enabled=True, tls_ca="/ca.crt").start()
    MqttBleBridge(
        enabled=True,
        tls_ca="/ca.crt",
        tls_cert="/client.crt",
    ).start()
