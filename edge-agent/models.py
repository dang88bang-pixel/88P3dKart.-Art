"""Pydantic-Datenmodelle für REST-API & WebSocket."""
from datetime import datetime
from typing import Any, Dict, List, Literal, Optional

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

DEVICE_ID_PATTERN = r"^[A-Za-z0-9._:-]{1,160}$"
ASSET_ID_PATTERN = r"^[A-Za-z0-9._:/-]{1,160}$"
POLICY_ID_PATTERN = (
    r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-"
    r"[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"
)
MAX_POINT_COMPONENTS = 150_000


class LidarFrame(BaseModel):
    """Bounded incoming LiDAR point cloud."""

    device_id: str = Field(pattern=DEVICE_ID_PATTERN)
    timestamp: float
    points: List[float] = Field(max_length=MAX_POINT_COMPONENTS)
    scattering_detected: bool = False

    @field_validator("points")
    @classmethod
    def complete_xyz_tuples(cls, value: List[float]) -> List[float]:
        if len(value) % 3:
            raise ValueError("points must contain complete x/y/z tuples")
        return value


class MmwaveTarget(BaseModel):
    device_id: str = Field(pattern=DEVICE_ID_PATTERN)
    timestamp: float
    targets: List[Dict[str, float]] = Field(default_factory=list, max_length=4096)


class BleTokenUpdate(BaseModel):
    device_id: str = Field(pattern=DEVICE_ID_PATTERN)
    timestamp: float
    tokens: List[Dict[str, Any]] = Field(default_factory=list, max_length=4096)


class UwbPhaseData(BaseModel):
    device_id: str = Field(pattern=DEVICE_ID_PATTERN)
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
    device_id: str = Field(pattern=DEVICE_ID_PATTERN)
    points: List[float] = Field(default_factory=list, max_length=MAX_POINT_COMPONENTS)
    metadata: Dict[str, Any] = Field(default_factory=dict)

    @field_validator("points")
    @classmethod
    def complete_xyz_tuples(cls, value: List[float]) -> List[float]:
        if len(value) % 3:
            raise ValueError("points must contain complete x/y/z tuples")
        return value


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
    device_ids: List[str] = Field(min_length=2, max_length=64)
    reference: Optional[str] = None


# ─── Aura (SDR/RTI) — docs/AURA.md ────────────────────────────


class AuraLink(BaseModel):
    """Eine RTI-Messlinie: Sender, Empfänger, gemessene Dämpfung."""

    tx: List[float]  # [x, y, z]
    rx: List[float]  # [x, y, z]
    attenuation_db: float


class AuraRtiRequest(BaseModel):
    device_id: str = "CT45P-01"
    bounds_min: List[float] = [-15.0, -15.0, 0.0]
    bounds_max: List[float] = [15.0, 15.0, 3.0]
    voxel_size: float = 0.5
    ellipse_width: float = 0.05
    regularization: float = 0.1
    method: str = "tikhonov"  # "tikhonov" | "backprojection"
    links: List[AuraLink] = Field(default_factory=list)


class AuraRtiResponse(BaseModel):
    device_id: str
    method: str
    voxel_count: int
    link_count: int
    voxels: List[Dict[str, Any]] = Field(default_factory=list)
    peaks: List[Dict[str, Any]] = Field(default_factory=list)


class AuraHeatmapRequest(BaseModel):
    device_id: str = "CT45P-01"
    samples: List[Dict[str, Any]] = Field(default_factory=list)
    cell_size_m: float = 1.0


class AuraHeatmapResponse(BaseModel):
    device_id: str
    cells: List[Dict[str, Any]] = Field(default_factory=list)


# ─── Triangulation (CT45P Wi-Fi RTT / BLE) — docs/TRIANGULATION.md ───


class TriangulationRequest(BaseModel):
    """Trilaterations-Anfrage: Anker + Distanzmessungen."""

    anchors: List[Dict[str, Any]] = Field(default_factory=list)  # {id, x, y, z}
    distances: Dict[str, float] = Field(default_factory=dict)  # anchor_id → Meter
    uncertainties: Optional[Dict[str, float]] = None  # anchor_id → σ [m]
    use_z: bool = False


class TriangulationResponse(BaseModel):
    position: Optional[Dict[str, Any]] = None
    anchor_count: int = 0


class ExportRequest(BaseModel):
    """Export-Anfrage: Annotationen/Punkte → GeoJSON/KML/JSON."""

    format: str = "geojson"  # "geojson" | "kml" | "json"
    annotations: List[Dict[str, Any]] = Field(default_factory=list)
    points: List[List[float]] = Field(default_factory=list)
    device_id: str = "CT45P-01"


class TopologyRequest(BaseModel):
    """Network3D: Topologie-Ingest (Upsert von Nodes/Edges)."""

    nodes: List[Dict[str, Any]] = Field(default_factory=list)
    edges: List[Dict[str, Any]] = Field(default_factory=list)


class SimulationRequest(BaseModel):
    """Network3D: What-If-Failover-Simulation."""

    node_id: str
    flows: List[Dict[str, Any]] = Field(default_factory=list)


class FloorPlanGeocodeRequest(BaseModel):
    """Grundriss: Adresssuche (Nominatim/Photon)."""

    query: str


class FloorPlanBuildingsRequest(BaseModel):
    """Grundriss: Gebäudeabruf via Overpass (Radius in Metern)."""

    lat: float
    lon: float
    radius: float = 100.0


class DeviceUpsertRequest(BaseModel):
    """Geräteinteraktion: Gerät upserten (Device-Dict gemäß Registry)."""

    device: Dict[str, Any]


class DeviceActionRequest(BaseModel):
    """Geräteinteraktion: Capability-geprüfte Aktion ausführen."""

    device_id: str
    action: str
    params: Dict[str, Any] = Field(default_factory=dict)


class DeviceLayerRequest(BaseModel):
    """Geräteinteraktion: Layer-Sichtbarkeit setzen."""

    layer_id: str
    visible: bool


class NetworkTrafficRequest(BaseModel):
    """Aktive Netzwerkvisualisierung: Live-Traffic-Ingest."""

    flows: List[Dict[str, Any]] = Field(default_factory=list)


class AlarmPolicyRequest(BaseModel):
    """Gateway-autoritative Alarmrichtlinie (docs/contracts/alarm-policy.schema.json)."""

    model_config = ConfigDict(extra="forbid")

    schema_version: Literal["1.0.0"] = "1.0.0"
    policy_id: str = Field(pattern=POLICY_ID_PATTERN)
    asset_id: str = Field(pattern=ASSET_ID_PATTERN)
    revision: int = Field(ge=1)
    enabled: bool = True
    metric: Literal[
        "RANGE_FROM_CT45P",
        "RANGE_FROM_ANCHOR",
        "RANGE_FROM_ZONE",
        "GEOFENCE_EXIT",
        "CONNECTIVITY_LOSS",
    ]
    reference_id: Optional[str] = None
    threshold_m: float = Field(gt=0)
    trigger_direction: Literal["ABOVE", "BELOW", "OUTSIDE", "LOSS"]
    decision_mode: Literal["POSSIBLE_BREACH", "CONFIRMED_BREACH"] = "CONFIRMED_BREACH"
    minimum_confidence: float = Field(ge=0, le=1)
    maximum_age_ms: int = Field(ge=0)
    dwell_ms: int = Field(ge=0)
    clear_dwell_ms: int = Field(ge=0)
    data_loss_dwell_ms: int = Field(ge=0)
    recovery_dwell_ms: int = Field(ge=0)
    hysteresis_m: float = Field(ge=0)
    cooldown_ms: int = Field(ge=0)
    severity: Literal["INFO", "WARNING", "CRITICAL"] = "WARNING"
    data_loss_behavior: Literal[
        "SEPARATE_ALARM", "FAIL_CLOSED", "WARN_ONLY"
    ] = "SEPARATE_ALARM"
    delivery_profile_id: str = "operators"

    @model_validator(mode="after")
    def anchor_metrics_require_reference(self) -> "AlarmPolicyRequest":
        if self.metric in ("RANGE_FROM_ANCHOR", "RANGE_FROM_ZONE") and not self.reference_id:
            raise ValueError(f"metric {self.metric} requires reference_id")
        return self


class AlarmEvidenceRequest(BaseModel):
    """Kalibrierte Fusions-Evidenz für Distanz-Alarme (authoritative Gateway)."""

    model_config = ConfigDict(extra="forbid")

    policy_id: str = Field(pattern=POLICY_ID_PATTERN)
    asset_id: str = Field(pattern=ASSET_ID_PATTERN)
    source_id: str = Field(min_length=1, max_length=160)
    cursor: str = Field(min_length=1, max_length=160)
    estimate_status: Literal["VALID", "UNOBSERVABLE"]
    method: str = Field(min_length=1, max_length=160)
    value_m: float
    confidence: float = Field(ge=0, le=1)
    lower_95_m: float
    upper_95_m: float
    observed_at: Optional[datetime] = None
    source_ids: List[str] = Field(min_length=1, max_length=64)
    measurement_ids: List[str] = Field(min_length=1, max_length=512)
    calibration_id: Optional[str] = None
    quality_flags: List[str] = Field(default_factory=list, max_length=64)


# ─── Geolokalisierung (docs/GEOLOCATION_PROVIDERS.md) ────────────

class WifiAccessPoint(BaseModel):
    """WLAN-Zugangspunkt (Mozilla-Geolocate-Format)."""

    macAddress: str
    signalStrength: Optional[int] = None
    age: Optional[int] = None
    channel: Optional[int] = None
    ssid: Optional[str] = None
    signalToNoiseRatio: Optional[int] = None


class BluetoothBeacon(BaseModel):
    """Bluetooth-Beacon (Mozilla-Geolocate-Format)."""

    macAddress: str
    signalStrength: Optional[int] = None
    age: Optional[int] = None
    name: Optional[str] = None


class CellTower(BaseModel):
    """Funkzelle (Mozilla-Geolocate-Format)."""

    mobileCountryCode: int
    mobileNetworkCode: int
    locationAreaCode: int
    cellId: int
    signalStrength: Optional[int] = None
    radioType: Optional[str] = None
    age: Optional[int] = None


class GeolocateRequest(BaseModel):
    """Scan-Signatur für die Geolokalisierungs-Kette (WLAN/Zelle/BLE)."""

    wifiAccessPoints: List[WifiAccessPoint] = Field(default_factory=list, max_length=64)
    cellTowers: List[CellTower] = Field(default_factory=list, max_length=32)
    bluetoothBeacons: List[BluetoothBeacon] = Field(default_factory=list, max_length=64)

    def is_empty(self) -> bool:
        return not (self.wifiAccessPoints or self.cellTowers or self.bluetoothBeacons)


def accuracy_to_quality(accuracy_m: float) -> float:
    """Übersetzt Genauigkeit in Qualität [0, 1]: 1 m → 1.0, 10 km → 0.0."""
    import math

    bounded = max(float(accuracy_m), 0.01)
    quality = 1.0 - math.log10(bounded) / 4.0
    return max(0.0, min(1.0, quality))


class GeoFix(BaseModel):
    """Positionsbestimmung eines Geo-Providers."""

    lat: float
    lon: float
    accuracy_m: float = Field(ge=0)
    altitude_m: Optional[float] = None
    source: str = "unknown"
    license: str = "unknown"
    attribution: Optional[str] = None
    ttl_days: Optional[int] = None
    timestamp: float
    quality: float = Field(default=0.0, ge=0, le=1)


class GeoAnchor(BaseModel):
    """Referenzpunkt, auf den externe Entitäten projiziert werden."""

    fix: GeoFix
    local_origin: List[float] = Field(default_factory=lambda: [0.0, 0.0, 0.0], alias="local_local_origin")
    heading_deg: float = 0.0

    model_config = ConfigDict(populate_by_name=True)


# ─── Externe Tracking-Quellen (docs/API_INTEGRATION_REVIEW.md) ───

EntityType = Literal["unknown", "vehicle", "micromobility", "transit"]


class ExternalEntity(BaseModel):
    """Externe Entität (z. B. Fahrzeug aus GTFS-RT) mit Projektionsfeldern."""

    source: str
    entity_type: EntityType
    entity_id: str
    id_is_stable: bool = False
    label: Optional[str] = None
    lat: float
    lon: float
    altitude_m: Optional[float] = None
    bearing_deg: Optional[float] = None
    speed_mps: Optional[float] = None
    timestamp: float
    received_at: float
    age_s: float = 0.0
    license: str = "unknown"
    attribution: Optional[str] = None
    metadata: Dict[str, Any] = Field(default_factory=dict)
    distance_m: Optional[float] = None
    local: Optional[List[float]] = None
    stale: bool = False
    quality: float = 0.0


class ExternalSourceStatus(BaseModel):
    """Gesundheitszustand einer externen Quelle."""

    name: str
    enabled: bool
    healthy: bool
    entity_type: EntityType
    license: str
    attribution: Optional[str] = None
    poll_interval_s: float
    last_success: Optional[float] = None
    last_error: Optional[str] = None
    consecutive_errors: int
    entity_count: int


class ExternalEntitySnapshot(BaseModel):
    """Aggregierter Stand aller externen Quellen (für API/MQTT/Visualizer)."""

    generated_at: float
    anchor_set: bool
    sources: List[str]
    count: int
    entities: List[ExternalEntity]


# ─── Enrollment & Sessions (docs/CT45P_MASTER_ARCHITECTURE.md) ───

class EnrollmentCodeRequest(BaseModel):
    """Admin-Anfrage: einmaligen Enrollment-Code für ein Gerät ausstellen."""

    device_id: str = Field(pattern=DEVICE_ID_PATTERN)
    ttl_seconds: int = Field(default=600, ge=60, le=86_400)


class EnrollmentClaimRequest(BaseModel):
    """Gerät beansprucht einen Enrollment-Code und erhält das Device-Secret."""

    device_id: str = Field(pattern=DEVICE_ID_PATTERN)
    code: str = Field(min_length=20, max_length=200)


class SessionRequest(BaseModel):
    """Gerät authentisiert sich mit dem Device-Secret und erhält ein JWT."""

    device_id: str = Field(pattern=DEVICE_ID_PATTERN)
    device_secret: str = Field(min_length=20, max_length=200)


class AlarmSnoozeRequest(BaseModel):
    """Alarm-Snooze: Dauer in Millisekunden (max. 24 h)."""

    duration_ms: int = Field(ge=1, le=86_400_000)


class BluetoothAccessoryUpdateRequest(BaseModel):
    """Batch-Update eigener Bluetooth-Zubehörgeräte (CT45P → Gateway)."""

    device_id: str = Field(pattern=DEVICE_ID_PATTERN)
    timestamp: float
    accessories: List[Dict[str, Any]] = Field(default_factory=list, max_length=64)
