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

    # --- Retention ---
    RETENTION_DAYS: int = int(os.getenv("RETENTION_DAYS", "7"))
    MAX_RECORDS: int = int(os.getenv("MAX_RECORDS", "100000"))
    # Intervall des Retention-Tasks (Default 1 h). Kurze Werte nur für Tests.
    RETENTION_INTERVAL_S: float = float(os.getenv("RETENTION_INTERVAL_S", "3600"))

    # --- Georeferenzierung (v4.5.0-Geo) ---
    # Default offline-only: keine ungewollte Datenabgabe (Entscheidung E2)
    GEO_ENABLED: bool = os.getenv("GEO_ENABLED", "true").lower() == "true"
    GEO_OFFLINE_ONLY: bool = os.getenv("GEO_OFFLINE_ONLY", "true").lower() == "true"
    GEO_PROVIDER_CHAIN: str = os.getenv("GEO_PROVIDER_CHAIN", "offline_cell,beacondb")
    GEO_TIMEOUT_S: float = float(os.getenv("GEO_TIMEOUT_S", "4.0"))
    GEO_CACHE_TTL_S: int = int(os.getenv("GEO_CACHE_TTL_S", "300"))
    # 0.35 entspricht ~400 m Genauigkeit. Höher ansetzen wäre kontraproduktiv:
    # Q=0.5 verlangt <=100 m, das erreicht Zellortung (>=150 m) nie — die
    # Offline-Kette liefe dann dauerhaft leer. IP-Ortung (>=20 km, Q<=0.02)
    # bleibt auch bei 0.35 zuverlässig ausgeschlossen.
    GEO_MIN_QUALITY: float = float(os.getenv("GEO_MIN_QUALITY", "0.35"))

    # Provider-Endpunkte (überschreibbar → Providerwechsel ohne Code-Update)
    GEO_BEACONDB_URL: str = os.getenv(
        "GEO_BEACONDB_URL", "https://api.beacondb.net/v1/geolocate"
    )
    GEO_LOCAL_ICHNAEA_URL: str = os.getenv("GEO_LOCAL_ICHNAEA_URL", "")
    GEO_COMBAIN_URL: str = os.getenv("GEO_COMBAIN_URL", "")
    GEO_COMBAIN_KEY: str = os.getenv("GEO_COMBAIN_KEY", "")
    GEO_GOOGLE_KEY: str = os.getenv("GEO_GOOGLE_KEY", "")
    GEO_GOOGLE_TTL_DAYS: int = int(os.getenv("GEO_GOOGLE_TTL_DAYS", "30"))  # ToS

    # Offline-Datenbestände (extern bezogen, nicht im Repo)
    GEO_OFFLINE_CELL_DB: str = os.getenv(
        "GEO_OFFLINE_CELL_DB", "./data/opencellid.sqlite"
    )
    GEO_USER_AGENT: str = os.getenv(
        "GEO_USER_AGENT", "3dxAgent/4.5 (+kontakt@example.org)"
    )

    # --- Externe Tracking-Feeds (Stufe 1: GTFS-Realtime) ---
    EXT_ENABLED: bool = os.getenv("EXT_ENABLED", "false").lower() == "true"
    EXT_SOURCES: str = os.getenv("EXT_SOURCES", "gtfs_rt")
    # Radius um den GeoAnchor; alles darüber wird verworfen (Maßstabsproblem)
    EXT_RADIUS_M: float = float(os.getenv("EXT_RADIUS_M", "2000"))
    # Ab diesem Alter gilt eine Entität als veraltet und wird markiert
    EXT_MAX_AGE_S: float = float(os.getenv("EXT_MAX_AGE_S", "120"))
    EXT_MIN_QUALITY: float = float(os.getenv("EXT_MIN_QUALITY", "0.3"))
    EXT_MAX_ENTITIES: int = int(os.getenv("EXT_MAX_ENTITIES", "500"))

    # GTFS-Realtime VehiclePositions
    GTFS_RT_URL: str = os.getenv("GTFS_RT_URL", "")
    GTFS_RT_POLL_S: float = float(os.getenv("GTFS_RT_POLL_S", "20"))
    GTFS_RT_API_KEY: str = os.getenv("GTFS_RT_API_KEY", "")
    GTFS_RT_API_KEY_HEADER: str = os.getenv("GTFS_RT_API_KEY_HEADER", "Authorization")
    GTFS_RT_LICENSE: str = os.getenv("GTFS_RT_LICENSE", "unknown")
    GTFS_RT_ATTRIBUTION: str = os.getenv("GTFS_RT_ATTRIBUTION", "")


CONFIG = Config()
