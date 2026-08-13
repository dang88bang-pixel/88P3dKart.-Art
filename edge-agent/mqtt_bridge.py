"""MQTT-BLE-Bridge: leitet BLE-Token-Rohdaten externer Smartphones weiter."""
import json
import logging
import time
from threading import Thread
from typing import Any, Optional

logger = logging.getLogger(__name__)

try:
    import paho.mqtt.client as mqtt  # type: ignore

    MQTT_AVAILABLE = True
except ImportError:  # pragma: no cover - optional dependency
    mqtt = None
    MQTT_AVAILABLE = False


class MqttBleBridge:
    """Lauscht auf `ble/tokens/#` und leitet Daten an WebSocket-Clients weiter."""

    def __init__(
        self,
        broker_host: str = "mosquitto",
        port: int = 8883,
        websocket_manager: Optional[Any] = None,
        enabled: bool = False,
        username: str = "",
        password: str = "",
        tls_ca: str = "",
        tls_cert: str = "",
        tls_key: str = "",
    ):
        self.broker_host = broker_host
        self.port = port
        self.ws_manager = websocket_manager
        self.enabled = enabled
        self.username = username
        self.password = password
        self.tls_ca = tls_ca
        self.tls_cert = tls_cert
        self.tls_key = tls_key
        self.client = None
        self.running = False
        self._thread: Optional[Thread] = None

    @property
    def available(self) -> bool:
        return bool(MQTT_AVAILABLE and self.enabled and self.running)

    def _on_connect(self, client, userdata, flags, rc):
        if rc == 0:
            logger.info("MQTT verbunden mit %s:%s", self.broker_host, self.port)
            client.subscribe("ble/tokens/#")
        else:
            logger.error("MQTT-Verbindung fehlgeschlagen, Code %d", rc)

    def _on_message(self, client, userdata, msg):
        try:
            payload = json.loads(msg.payload.decode())
            device_id = msg.topic.split("/")[-1]
            payload["device_id"] = device_id
            payload["timestamp"] = time.time()

            if self.ws_manager is not None:
                self.ws_manager.broadcast_json_sync(
                    {"type": "ble_update", "payload": payload},
                    subject=device_id,
                )
        except Exception as e:  # noqa: BLE001
            logger.error("MQTT-Fehler: %s", e)

    def start(self) -> None:
        if not self.enabled:
            logger.info("MQTT bridge disabled")
            return
        if not MQTT_AVAILABLE:
            logger.warning("paho-mqtt nicht installiert — MQTT-Bridge deaktiviert.")
            return
        if not self.tls_ca:
            logger.error("MQTT requires a TLS CA; bridge disabled")
            return
        if bool(self.username) != bool(self.password):
            logger.error("MQTT username/password configuration is incomplete; bridge disabled")
            return
        if bool(self.tls_cert) != bool(self.tls_key):
            logger.error("MQTT client-certificate configuration is incomplete; bridge disabled")
            return
        if not self.username and not self.tls_cert:
            logger.error("MQTT requires password authentication or mTLS; bridge disabled")
            return
        if self.running:
            return

        self.client = mqtt.Client()
        if self.username:
            self.client.username_pw_set(self.username, self.password)
        self.client.tls_set(
            ca_certs=self.tls_ca,
            certfile=self.tls_cert or None,
            keyfile=self.tls_key or None,
        )
        self.client.tls_insecure_set(False)
        self.client.on_connect = self._on_connect
        self.client.on_message = self._on_message
        self.running = True

        def _loop():
            try:
                self.client.connect(self.broker_host, self.port, 60)
                self.client.loop_forever()
            except Exception as e:  # Broker nicht erreichbar
                logger.warning("MQTT-Broker nicht erreichbar: %s", e)
                self.running = False

        self._thread = Thread(target=_loop, daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self.running = False
        if self.client is not None:
            try:
                self.client.disconnect()
            except Exception:
                pass
