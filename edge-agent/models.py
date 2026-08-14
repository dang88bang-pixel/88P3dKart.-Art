"""Pydantic-Datenmodelle für REST-API & WebSocket."""
from typing import Any, Dict, List, Optional

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
