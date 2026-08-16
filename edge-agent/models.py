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
    device_ids: List[str]
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
