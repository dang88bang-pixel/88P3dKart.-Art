"""Pydantic-Datenmodelle für REST-API & WebSocket."""
import math
from typing import Any, Dict, List, Literal, Optional, Tuple

from pydantic import BaseModel, Field


class LidarFrame(BaseModel):
    """Eingehende LiDAR-Punktwolke (von CT45P)."""

    device_id: str
    timestamp: float
    points: List[float]  # [x1,y1,z1, x2,y2,z2, ...]
    scattering_detected: bool = False


class MmwaveTarget(BaseModel):
    device_id: str
    timestamp: float
    targets: List[Dict[str, float]] = Field(default_factory=list)


class BleTokenUpdate(BaseModel):
    device_id: str
    timestamp: float
    tokens: List[Dict[str, Any]] = Field(default_factory=list)


class UwbPhaseData(BaseModel):
    device_id: str
    timestamp: float
    phase: float  # Radians


class EkfState(BaseModel):
    x: float
    y: float
    z: float
    vx: float
    vy: float
    vz: float
    covariance: List[List[float]]
    kalman_gain_lidar: float
    mode: str  # "FULL", "DEGRADED", "MINIMAL"


class Transform3D(BaseModel):
    offset_x: float = 0.0
    offset_y: float = 0.0
    offset_z: float = 0.0
    pitch: float = 0.0
    roll: float = 0.0
    yaw: float = 0.0
    scale: float = 1.0


class ScenarioConfig(BaseModel):
    type: str  # "tactical", "evacuation", "architecture", "temp", "research"
    params: Dict[str, Any] = Field(default_factory=dict)


class PipelineRequest(BaseModel):
    device_id: str
    points: List[float] = Field(default_factory=list)  # [x1,y1,z1, ...]
    metadata: Dict[str, Any] = Field(default_factory=dict)


class PipelineResult(BaseModel):
    device_id: str
    status: str
    num_points: int
    num_mesh_vertices: int
    num_mesh_faces: int
    num_objects: int
    confidence: float
    transform: Transform3D
    evaluation: Dict[str, Any] = Field(default_factory=dict)


class MergeRequest(BaseModel):
    device_ids: List[str]
    reference: Optional[str] = None


# ══════════════════════════════════════════════════════════════
#  Georeferenzierung (v4.5.0-Geo, Phase 1)
#  Siehe docs/GEOLOCATION_CHANGE_PLAN.md §1.1
# ══════════════════════════════════════════════════════════════

MAC_PATTERN = r"^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$"


class WifiAccessPoint(BaseModel):
    """Ichnaea-kompatibler WLAN-Scan-Eintrag."""

    macAddress: str = Field(..., pattern=MAC_PATTERN)
    signalStrength: Optional[int] = Field(None, ge=-120, le=0)  # dBm
    age: Optional[int] = Field(None, ge=0)  # ms
    channel: Optional[int] = None
    signalToNoiseRatio: Optional[int] = None


class CellTower(BaseModel):
    radioType: Optional[Literal["gsm", "wcdma", "lte", "nr"]] = None
    mobileCountryCode: Optional[int] = Field(None, ge=0, le=999)
    mobileNetworkCode: Optional[int] = Field(None, ge=0, le=999)
    locationAreaCode: Optional[int] = None
    cellId: Optional[int] = None
    signalStrength: Optional[int] = None
    age: Optional[int] = None


class BluetoothBeacon(BaseModel):
    macAddress: str = Field(..., pattern=MAC_PATTERN)
    name: Optional[str] = None
    signalStrength: Optional[int] = None
    age: Optional[int] = None


class GeolocateRequest(BaseModel):
    """Ichnaea /v1/geolocate Request-Body.

    ``considerIp`` ist bewusst standardmässig ``False``: sonst fällt ein
    Provider still auf IP-Ortung zurück und liefert einen 20-km-Fix als
    vermeintliche Position.
    """

    considerIp: bool = False
    homeMobileCountryCode: Optional[int] = None
    homeMobileNetworkCode: Optional[int] = None
    radioType: Optional[str] = None
    carrier: Optional[str] = None
    wifiAccessPoints: List[WifiAccessPoint] = Field(default_factory=list)
    cellTowers: List[CellTower] = Field(default_factory=list)
    bluetoothBeacons: List[BluetoothBeacon] = Field(default_factory=list)
    fallbacks: Optional[Dict[str, bool]] = None

    def is_empty(self) -> bool:
        return not (self.wifiAccessPoints or self.cellTowers or self.bluetoothBeacons)


def accuracy_to_quality(accuracy_m: float) -> float:
    """Leitet Q_conf aus dem Accuracy-Radius ab (docs/CLIENT_RULES.md).

    1 m -> 1.00 | 10 m -> 0.75 | 100 m -> 0.50 | 1 km -> 0.25 | >=10 km -> 0.00
    Damit werden IP-Fixes durch die bestehende Q<0.5-Schwelle automatisch
    verworfen — ohne Sonderfall im Code.
    """
    if accuracy_m <= 0:
        return 0.0
    q = 1.0 - math.log10(max(accuracy_m, 1.0)) / 4.0
    return max(0.0, min(1.0, q))


class GeoFix(BaseModel):
    """Normalisiertes Ergebnis eines beliebigen Geo-Providers."""

    lat: float = Field(..., ge=-90, le=90)
    lon: float = Field(..., ge=-180, le=180)
    accuracy_m: float = Field(..., gt=0)
    altitude_m: Optional[float] = None
    source: str
    license: str
    attribution: Optional[str] = None
    ttl_days: Optional[int] = None  # None = unbegrenzt; 30 bei Google (ToS)
    timestamp: float
    quality: float = Field(..., ge=0.0, le=1.0)


class GeoAnchor(BaseModel):
    """Verknüpfung lokaler metrischer Frame <-> WGS84.

    Das ist die im Prüfbericht (docs/API_INTEGRATION_REVIEW.md, Blocker A)
    identifizierte fehlende Koordinatenbrücke. Ohne Anker ist keine externe
    lat/lon-Entität in der lokalen Szene platzierbar.
    """

    fix: GeoFix
    local_origin: Tuple[float, float, float] = (0.0, 0.0, 0.0)
    heading_deg: Optional[float] = Field(None, ge=-360.0, le=360.0)
    frame_id: str = "map"


class GeoAnchorRequest(BaseModel):
    """Anker setzen — entweder aus einem Fix oder direkt aus lat/lon."""

    lat: float = Field(..., ge=-90, le=90)
    lon: float = Field(..., ge=-180, le=180)
    accuracy_m: float = Field(10.0, gt=0)
    altitude_m: Optional[float] = None
    source: str = "manual"
    license: str = "n/a"
    local_origin: Tuple[float, float, float] = (0.0, 0.0, 0.0)
    heading_deg: Optional[float] = Field(None, ge=-360.0, le=360.0)
    frame_id: str = "map"


# ══════════════════════════════════════════════════════════════
#  Externe Entitäten (v4.5.0-Geo, Stufe 1 — GTFS-Realtime)
#  Siehe docs/API_INTEGRATION_REVIEW.md §6
# ══════════════════════════════════════════════════════════════

EntityType = Literal[
    "vehicle", "vessel", "aircraft", "micromobility", "incident", "unknown"
]


class ExternalEntity(BaseModel):
    """Gemeinsames Normalisierungsschema für alle externen Tracking-Quellen.

    Gegenüber dem ursprünglichen Konzeptschema um vier Felder ergänzt:

    * ``quality``   — Einordnung in die bestehende CLIENT_RULES-Qualitätsformel
    * ``crs``       — explizites Bezugssystem, verhindert stillen Frame-Mix
    * ``local``     — projizierte lokale Koordinaten (erst mit GeoAnchor gesetzt)
    * ``stale``     — Feed-Alter überschreitet die Quellen-Toleranz
    """

    source: str  # 'gtfs_rt' | 'gbfs' | ...
    entity_type: EntityType = "unknown"
    entity_id: str  # feed-eigene ID; NICHT zwingend eine Hardware-ID
    id_is_stable: bool = False  # GBFS rotiert IDs; GTFS-RT garantiert nichts
    label: Optional[str] = None
    lat: float = Field(..., ge=-90, le=90)
    lon: float = Field(..., ge=-180, le=180)
    altitude_m: Optional[float] = None
    bearing_deg: Optional[float] = None
    speed_mps: Optional[float] = None
    timestamp: float  # Zeitstempel der Quelle (nicht des Abrufs)
    received_at: float
    age_s: float = 0.0
    stale: bool = False
    quality: float = Field(1.0, ge=0.0, le=1.0)
    crs: str = "EPSG:4326"
    local: Optional[Tuple[float, float, float]] = None  # x,y,z im Agent-Frame
    distance_m: Optional[float] = None  # Distanz zum GeoAnchor
    license: Optional[str] = None
    attribution: Optional[str] = None
    metadata: Dict[str, Any] = Field(default_factory=dict)


class ExternalEntitySnapshot(BaseModel):
    """Antwort von GET /api/v1/external/entities."""

    generated_at: float
    anchor_set: bool
    sources: List[str] = Field(default_factory=list)
    count: int = 0
    entities: List[ExternalEntity] = Field(default_factory=list)


class ExternalSourceStatus(BaseModel):
    """Betriebszustand eines Adapters — Grundlage der Staleness-Anzeige."""

    name: str
    enabled: bool
    healthy: bool
    entity_type: EntityType
    license: str
    attribution: Optional[str] = None
    poll_interval_s: float = 0.0
    last_success: Optional[float] = None
    last_error: Optional[str] = None
    consecutive_errors: int = 0
    entity_count: int = 0
