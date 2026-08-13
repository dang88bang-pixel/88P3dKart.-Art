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
    device_ids: List[str] = Field(min_length=2, max_length=32)
    reference: Optional[str] = Field(default=None, max_length=160)


class EnrollmentCodeRequest(BaseModel):
    device_id: str = Field(pattern=DEVICE_ID_PATTERN)
    ttl_seconds: int = Field(default=600, ge=60, le=86_400)


class EnrollmentClaimRequest(BaseModel):
    device_id: str = Field(pattern=DEVICE_ID_PATTERN)
    code: str = Field(min_length=20, max_length=200)


class SessionRequest(BaseModel):
    device_id: str = Field(pattern=DEVICE_ID_PATTERN)
    device_secret: str = Field(min_length=32, max_length=256)


class AlarmPolicyRequest(BaseModel):
    """Supported range-policy subset of the versioned public contract."""

    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)

    schema_version: Literal["1.0.0"]
    policy_id: str = Field(pattern=POLICY_ID_PATTERN)
    asset_id: str = Field(pattern=ASSET_ID_PATTERN)
    revision: int = Field(ge=1)
    enabled: bool
    metric: Literal[
        "RANGE_FROM_CT45P", "RANGE_FROM_ANCHOR", "RANGE_FROM_ZONE"
    ]
    reference_id: Optional[str] = Field(default=None, min_length=1, max_length=160)
    threshold_m: float = Field(gt=0, le=100_000)
    trigger_direction: Literal["ABOVE", "BELOW"]
    decision_mode: Literal["POSSIBLE_BREACH", "CONFIRMED_BREACH"]
    minimum_confidence: float = Field(ge=0, le=1)
    maximum_age_ms: int = Field(ge=100, le=86_400_000)
    dwell_ms: int = Field(ge=0, le=86_400_000)
    clear_dwell_ms: int = Field(ge=0, le=86_400_000)
    data_loss_dwell_ms: int = Field(ge=0, le=86_400_000)
    recovery_dwell_ms: int = Field(ge=0, le=86_400_000)
    hysteresis_m: float = Field(ge=0, le=100_000)
    cooldown_ms: int = Field(ge=0, le=604_800_000)
    severity: Literal["INFO", "WARNING", "CRITICAL"]
    data_loss_behavior: Literal["SEPARATE_ALARM"]
    delivery_profile_id: str = Field(min_length=1, max_length=160)

    @model_validator(mode="after")
    def range_reference_is_present_when_required(self) -> "AlarmPolicyRequest":
        if self.metric in {"RANGE_FROM_ANCHOR", "RANGE_FROM_ZONE"} and not self.reference_id:
            raise ValueError("reference_id is required for the selected metric")
        return self


class AlarmEvidenceRequest(BaseModel):
    """Calibrated fusion estimate accepted by the authoritative reducer."""

    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)

    policy_id: str = Field(pattern=POLICY_ID_PATTERN)
    asset_id: str = Field(pattern=ASSET_ID_PATTERN)
    source_id: str = Field(min_length=1, max_length=160)
    cursor: str = Field(min_length=1, max_length=200)
    estimate_status: Literal[
        "VALID", "LOW_CONFIDENCE", "STALE", "UNOBSERVABLE", "UNSUPPORTED", "INVALID"
    ]
    method: Optional[str] = Field(default=None, max_length=80)
    value_m: Optional[float] = Field(default=None, ge=0, le=100_000)
    confidence: Optional[float] = Field(default=None, ge=0, le=1)
    lower_95_m: Optional[float] = Field(default=None, ge=0, le=100_000)
    upper_95_m: Optional[float] = Field(default=None, ge=0, le=100_000)
    observed_at: Optional[datetime] = None
    source_ids: List[str] = Field(default_factory=list, max_length=32)
    measurement_ids: List[str] = Field(min_length=1, max_length=128)
    calibration_id: Optional[str] = Field(default=None, max_length=160)
    quality_flags: List[str] = Field(default_factory=list, max_length=64)

    @field_validator("source_ids")
    @classmethod
    def bounded_unique_source_ids(cls, values: List[str]) -> List[str]:
        if len(set(values)) != len(values) or any(not value or len(value) > 160 for value in values):
            raise ValueError("source_ids must be unique bounded identifiers")
        return values

    @field_validator("measurement_ids")
    @classmethod
    def bounded_unique_measurement_ids(cls, values: List[str]) -> List[str]:
        if len(set(values)) != len(values) or any(not value or len(value) > 200 for value in values):
            raise ValueError("measurement_ids must be unique bounded identifiers")
        return values

    @field_validator("quality_flags")
    @classmethod
    def bounded_unique_quality_flags(cls, values: List[str]) -> List[str]:
        if len(set(values)) != len(values) or any(not value or len(value) > 80 for value in values):
            raise ValueError("quality_flags must be unique bounded identifiers")
        return values

    @model_validator(mode="after")
    def valid_estimate_has_complete_ordered_bounds(self) -> "AlarmEvidenceRequest":
        if self.estimate_status == "VALID":
            values = (
                self.value_m,
                self.confidence,
                self.lower_95_m,
                self.upper_95_m,
                self.observed_at,
                self.calibration_id,
            )
            if any(value is None for value in values):
                raise ValueError("VALID evidence requires value, confidence, bounds, time, and calibration")
            assert self.lower_95_m is not None
            assert self.value_m is not None
            assert self.upper_95_m is not None
            if not self.lower_95_m <= self.value_m <= self.upper_95_m:
                raise ValueError("evidence bounds must contain value_m")
        if self.observed_at is not None and self.observed_at.tzinfo is None:
            raise ValueError("observed_at must include an UTC offset")
        return self


class AlarmSnoozeRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    duration_ms: int = Field(ge=1_000, le=86_400_000)
