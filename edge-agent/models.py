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
