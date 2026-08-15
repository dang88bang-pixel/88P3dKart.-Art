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
        port: int = 1883,
        websocket_manager: Optional[Any] = None,
    ):
        self.broker_host = broker_host
        self.port = port
        self.ws_manager = websocket_manager
        self.client = None
        self.running = False
        self._thread: Optional[Thread] = None

    @property
    def available(self) -> bool:
        return MQTT_AVAILABLE

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
                    {"type": "ble_update", "payload": payload}
                )
        except Exception as e:  # noqa: BLE001
            logger.error("MQTT-Fehler: %s", e)

    def start(self) -> None:
        if not MQTT_AVAILABLE:
            logger.warning("paho-mqtt nicht installiert — MQTT-Bridge deaktiviert.")
            return
        if self.running:
            return

        self.client = mqtt.Client()
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

    def publish_json(self, topic: str, payload: Any, retain: bool = False) -> bool:
        """Veröffentlicht ein Objekt als JSON. Gibt zurück, ob es rausging.

        Wird für `3dxagent/external/entities` genutzt, damit Drittsysteme den
        externen Lagestand mitlesen können, ohne den WebSocket zu belegen.
        """
        if not MQTT_AVAILABLE or self.client is None or not self.running:
            return False
        try:
            self.client.publish(topic, json.dumps(payload, default=str), retain=retain)
            return True
        except Exception as exc:  # noqa: BLE001
            logger.warning("MQTT-Veröffentlichung auf %s fehlgeschlagen: %s", topic, exc)
            return False

    def stop(self) -> None:
        self.running = False
        if self.client is not None:
            try:
                self.client.disconnect()
            except Exception:
                pass
