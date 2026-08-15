"""Zentrale, ENV-gesteuerte Konfiguration des Edge-Agents."""
import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Config:
    # --- Pfade ---
    # Docker übersteuert via ENV AGENT_DB_PATH=/data/agent.db
    DB_PATH: str = os.getenv("AGENT_DB_PATH", "./data/agent.db")
    LOG_DIR: str = os.getenv("AGENT_LOG_DIR", "./logs")

    # --- Timing ---
    LOOP_HZ: float = float(os.getenv("AGENT_LOOP_HZ", "20"))
    WEBSOCKET_PING_INTERVAL: int = int(os.getenv("WEBSOCKET_PING_INTERVAL", "10"))

    # --- Sensor-Grenzen (adaptiv) ---
    LIDAR_SCATTER_THRESHOLD: float = float(os.getenv("LIDAR_SCATTER_THRESHOLD", "0.3"))
    THERMAL_CRITICAL_C: float = float(os.getenv("THERMAL_CRITICAL_C", "75.0"))
    THERMAL_WARNING_C: float = float(os.getenv("THERMAL_WARNING_C", "60.0"))

    # --- UWB ---
    UWB_FS: float = float(os.getenv("UWB_FS", "20.0"))  # Sample-Rate [Hz]
    UWB_BUFFER_SECS: float = float(os.getenv("UWB_BUFFER_SECS", "5.0"))

    # --- Netzwerk ---
    API_HOST: str = os.getenv("API_HOST", "0.0.0.0")
    API_PORT: int = int(os.getenv("API_PORT", "8080"))
    MQTT_HOST: str = os.getenv("MQTT_HOST", "mosquitto")
    MQTT_PORT: int = int(os.getenv("MQTT_PORT", "1883"))

    # --- Bluetooth Zubehör ---
    BT_MAX_DEVICES: int = int(os.getenv("BT_MAX_DEVICES", "150"))
    BT_EXPIRY_SECS: float = float(os.getenv("BT_EXPIRY_SECS", "60"))
    BT_MQTT_TOPICS: str = os.getenv(
        "BT_MQTT_TOPICS",
        "ble/tokens/#,bluetooth/accessories/#,bluetooth/sensors/#,bluetooth/wearables/#,bluetooth/events/#",
    )
    BT_ENABLE_CLASSIC: bool = os.getenv("BT_ENABLE_CLASSIC", "true").lower() in ("1", "true", "yes")
    BT_ENABLE_GATT: bool = os.getenv("BT_ENABLE_GATT", "true").lower() in ("1", "true", "yes")

    # --- Retention ---
    RETENTION_DAYS: int = int(os.getenv("RETENTION_DAYS", "7"))
    MAX_RECORDS: int = int(os.getenv("MAX_RECORDS", "100000"))


CONFIG = Config()
