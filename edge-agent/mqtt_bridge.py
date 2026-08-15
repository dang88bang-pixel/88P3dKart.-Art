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
            client.subscribe("bluetooth/accessories/#")
            client.subscribe("bluetooth/sensors/#")
            client.subscribe("bluetooth/wearables/#")
            client.subscribe("bluetooth/events/#")
            logger.info("MQTT subscribed: ble/tokens/#, bluetooth/#")
        else:
            logger.error("MQTT-Verbindung fehlgeschlagen, Code %d", rc)

    def _on_message(self, client, userdata, msg):
        try:
            payload = json.loads(msg.payload.decode())
            device_id = msg.topic.split("/")[-1]
            payload["device_id"] = device_id
            payload["timestamp"] = time.time()

            # In Bluetooth Registry einpflegen + Weiterleitung
            topic = msg.topic.lower()

            # Lazy import to avoid circular dependencies at module load
            try:
                from bluetooth_accessories import global_accessory_registry
                if topic.startswith("ble/tokens/") or topic.startswith("bluetooth/"):
                    # Unterschiedliche Payloads: einzelnes Gerät oder Liste
                    if isinstance(payload, list):
                        global_accessory_registry.update_batch(payload)
                    elif isinstance(payload, dict) and "accessories" in payload:
                        global_accessory_registry.update_batch(payload["accessories"])
                    elif isinstance(payload, dict) and ("mac" in payload or "mac_address" in payload):
                        global_accessory_registry.update_from_payload(payload)
            except Exception as e:
                logger.debug("BT Registry update skip: %s", e)

            if self.ws_manager is not None:
                if topic.startswith("bluetooth/accessories"):
                    self.ws_manager.broadcast_json_sync(
                        {"type": "bluetooth_accessories_update", "payload": payload}
                    )
                elif topic.startswith("bluetooth/sensors"):
                    self.ws_manager.broadcast_json_sync(
                        {"type": "sensor_tag_update", "payload": payload}
                    )
                elif topic.startswith("bluetooth/wearables"):
                    self.ws_manager.broadcast_json_sync(
                        {"type": "wearable_update", "payload": payload}
                    )
                elif topic.startswith("bluetooth/events"):
                    self.ws_manager.broadcast_json_sync(
                        {"type": "accessory_event", "payload": payload}
                    )
                else:
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

    def stop(self) -> None:
        self.running = False
        if self.client is not None:
            try:
                self.client.disconnect()
            except Exception:
                pass
