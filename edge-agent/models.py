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


class BluetoothAccessoryPayload(BaseModel):
    """Ein einzelnes Bluetooth-Zubehör aus Android App / MQTT."""

    mac: Optional[str] = None
    mac_address: Optional[str] = None
    type: str = "GENERIC_BLE"
    name: Optional[str] = None
    rssi: int = -100
    battery: Optional[int] = 100
    battery_level: Optional[int] = None
    tx_power: Optional[int] = None
    distance_m: Optional[float] = None
    protocol_version: int = 1
    flags: int = 0
    accel_x: Optional[float] = 0.0
    accel_y: Optional[float] = 0.0
    accel_z: Optional[float] = 0.0
    temperature_c: Optional[float] = None
    humidity_pct: Optional[float] = None
    pressure_hpa: Optional[float] = None
    air_quality_ppm: Optional[float] = None
    light_lux: Optional[float] = None
    heart_rate_bpm: Optional[int] = None
    steps: Optional[int] = None
    ibeacon_uuid: Optional[str] = None
    ibeacon_major: Optional[int] = None
    ibeacon_minor: Optional[int] = None
    eddystone_url: Optional[str] = None
    eddystone_namespace: Optional[str] = None
    eddystone_instance: Optional[str] = None
    button_state: int = 0
    firmware_version: Optional[str] = None
    data_quality: float = 0.9

    def normalized(self) -> Dict[str, Any]:
        # Merge fields to common dict for registry
        d = self.model_dump()
        # Map alias
        if d.get("mac_address") and not d.get("mac"):
            d["mac"] = d["mac_address"]
        if d.get("battery_level") is not None:
            d["battery"] = d["battery_level"]
        return d


class BluetoothAccessoriesUpdate(BaseModel):
    device_id: str
    timestamp: float
    accessories: List[Dict[str, Any]] = Field(default_factory=list)
    count: Optional[int] = None


class AccessoryEvent(BaseModel):
    device_id: str
    timestamp: float
    mac: str
    event_type: str  # sos, button, fall, etc.
    payload: Dict[str, Any] = Field(default_factory=dict)
