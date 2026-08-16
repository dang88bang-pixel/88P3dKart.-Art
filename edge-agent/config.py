"""Zentrale, ENV-gesteuerte Konfiguration des Edge-Agents."""
import os
from dataclasses import dataclass
from pathlib import Path


_MAX_SECRET_CHARS = 4096


def _secret(name: str) -> str:
    """Load a bounded secret, preferring the conventional ``*_FILE`` source."""
    file_path = os.getenv(f"{name}_FILE", "")
    if file_path:
        with Path(file_path).open("r", encoding="utf-8") as secret_file:
            value = secret_file.read(_MAX_SECRET_CHARS + 1)
        if len(value) > _MAX_SECRET_CHARS:
            raise ValueError(f"{name}_FILE is unexpectedly large")
        return value.strip()
    return os.getenv(name, "")


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
    REQUIRE_TLS: bool = os.getenv("AGENT_REQUIRE_TLS", "true").lower() == "true"
    TLS_CERTFILE: str = os.getenv("AGENT_TLS_CERTFILE", "")
    TLS_KEYFILE: str = os.getenv("AGENT_TLS_KEYFILE", "")
    MQTT_ENABLED: bool = os.getenv("AGENT_MQTT_ENABLED", "false").lower() == "true"
    MQTT_HOST: str = os.getenv("MQTT_HOST", "mosquitto")
    MQTT_PORT: int = int(os.getenv("MQTT_PORT", "8883"))
    MQTT_USERNAME: str = os.getenv("AGENT_MQTT_USERNAME", "")
    MQTT_PASSWORD: str = _secret("AGENT_MQTT_PASSWORD")
    MQTT_TLS_CA: str = os.getenv("AGENT_MQTT_TLS_CA", "")
    MQTT_TLS_CERT: str = os.getenv("AGENT_MQTT_TLS_CERT", "")
    MQTT_TLS_KEY: str = os.getenv("AGENT_MQTT_TLS_KEY", "")
    CORS_ORIGINS: tuple[str, ...] = tuple(
        origin.strip()
        for origin in os.getenv("AGENT_CORS_ORIGINS", "").split(",")
        if origin.strip()
    )
    TRUSTED_HOSTS: tuple[str, ...] = tuple(
        host.strip()
        for host in os.getenv(
            "AGENT_TRUSTED_HOSTS", "localhost,127.0.0.1,testserver"
        ).split(",")
        if host.strip()
    )
    MAX_WEBSOCKET_MESSAGE_BYTES: int = int(
        os.getenv("AGENT_MAX_WEBSOCKET_MESSAGE_BYTES", "1048576")
    )
    MAX_HTTP_BODY_BYTES: int = int(os.getenv("AGENT_MAX_HTTP_BODY_BYTES", "4194304"))

    # --- Authentifizierung ---
    AUTH_DB_PATH: str = os.getenv("AGENT_AUTH_DB_PATH", "./data/credentials.db")
    AUTH_SIGNING_SECRET: str = _secret("AGENT_AUTH_SIGNING_SECRET")
    ADMIN_BOOTSTRAP_TOKEN: str = _secret("AGENT_ADMIN_BOOTSTRAP_TOKEN")
    SESSION_TTL_SECONDS: int = int(os.getenv("AGENT_SESSION_TTL_SECONDS", "900"))
    AUTH_MAX_FAILURES: int = int(os.getenv("AGENT_AUTH_MAX_FAILURES", "5"))
    AUTH_FAILURE_WINDOW_SECONDS: int = int(
        os.getenv("AGENT_AUTH_FAILURE_WINDOW_SECONDS", "60")
    )
    AUTH_ATTEMPT_MAX_KEYS: int = int(
        os.getenv("AGENT_AUTH_ATTEMPT_MAX_KEYS", "4096")
    )

    # --- Autoritative Alarmzustände / Outbox ---
    ALARM_DB_PATH: str = os.getenv("AGENT_ALARM_DB_PATH", "./data/alarms.db")
    GATEWAY_ID: str = os.getenv("AGENT_GATEWAY_ID", "gateway-1")
    ALARM_TICK_MS: int = int(os.getenv("AGENT_ALARM_TICK_MS", "250"))
    ALARM_OUTBOX_LEASE_MS: int = int(os.getenv("AGENT_ALARM_OUTBOX_LEASE_MS", "30000"))
    ALARM_OUTBOX_RETRY_MS: int = int(os.getenv("AGENT_ALARM_OUTBOX_RETRY_MS", "5000"))
    ALARM_OUTBOX_BATCH_SIZE: int = int(os.getenv("AGENT_ALARM_OUTBOX_BATCH_SIZE", "100"))

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

    def __post_init__(self) -> None:
        if not 50 <= self.ALARM_TICK_MS <= 60_000:
            raise ValueError("AGENT_ALARM_TICK_MS must be between 50 and 60000")
        if not 1_000 <= self.ALARM_OUTBOX_LEASE_MS <= 86_400_000:
            raise ValueError("AGENT_ALARM_OUTBOX_LEASE_MS is outside accepted bounds")
        if not 100 <= self.ALARM_OUTBOX_RETRY_MS <= 86_400_000:
            raise ValueError("AGENT_ALARM_OUTBOX_RETRY_MS is outside accepted bounds")
        if not 1 <= self.ALARM_OUTBOX_BATCH_SIZE <= 500:
            raise ValueError("AGENT_ALARM_OUTBOX_BATCH_SIZE is outside accepted bounds")


CONFIG = Config()
