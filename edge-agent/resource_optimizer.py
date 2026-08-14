"""Ressourcensparende Scan-/Fusionspolitiken — Python-Port der Kotlin-Module
(`com.example.agent.resource`, docs/RESOURCE_OPT.md).

Portiert die v11.0.0-Kernlogik (AdaptiveScanManager, ROIScanManager,
VoxelFusionOptimizer, ResourceManagementService) — mit denselben Korrekturen
wie die Kotlin-Seite (Triple/Quadruple-Problematik → Datenklassen).
"""

from __future__ import annotations

import math
import time
from dataclasses import dataclass
from enum import Enum
from typing import Dict, List, Optional

# ─── Scan-Raten-Politik ────────────────────────────────────────────────────


class MotionState(Enum):
    STATIONARY = "stationary"
    WALKING = "walking"
    RUNNING = "running"
    VEHICLE = "vehicle"


@dataclass
class ScanRates:
    lidar_rate: float
    mmwave_rate: float
    uwb_rate: float
    ble_rate: float
    mesh_rate: float
    quality: float


BASELINE_RATES = ScanRates(20.0, 20.0, 10.0, 10.0, 5.0, 1.0)

BASE_RATES = {
    MotionState.VEHICLE: (20.0, 20.0, 10.0, 5.0),
    MotionState.RUNNING: (15.0, 15.0, 8.0, 4.0),
    MotionState.WALKING: (10.0, 10.0, 5.0, 3.0),
    MotionState.STATIONARY: (2.0, 2.0, 1.0, 1.0),
}
MESH_RATES = {
    MotionState.VEHICLE: 10.0,
    MotionState.RUNNING: 5.0,
    MotionState.WALKING: 2.0,
    MotionState.STATIONARY: 0.5,
}
QUALITY_FACTORS = {
    MotionState.STATIONARY: 1.0,
    MotionState.WALKING: 0.9,
    MotionState.RUNNING: 0.7,
    MotionState.VEHICLE: 0.5,
}


def motion_state_of(velocity: float) -> MotionState:
    if velocity > 5.0:
        return MotionState.VEHICLE
    if velocity > 1.5:
        return MotionState.RUNNING
    if velocity > 0.5:
        return MotionState.WALKING
    return MotionState.STATIONARY


def battery_factor(battery_level: int) -> float:
    if battery_level > 80:
        return 1.0
    if battery_level > 50:
        return 0.8
    if battery_level > 30:
        return 0.5
    if battery_level > 15:
        return 0.3
    return 0.1


def thermal_factor(thermal_c: float) -> float:
    if thermal_c > 50.0:
        return 0.3
    if thermal_c > 40.0:
        return 0.6
    if thermal_c > 35.0:
        return 0.8
    return 1.0


def compute_scan_rates(velocity: float, battery_level: int, thermal_c: float) -> ScanRates:
    motion = motion_state_of(velocity)
    base = BASE_RATES[motion]
    factor = battery_factor(battery_level) * thermal_factor(thermal_c)

    def clamp_rate(value: float, low: float) -> float:
        return max(low, min(20.0, value))

    return ScanRates(
        lidar_rate=clamp_rate(base[0] * factor, 1.0),
        mmwave_rate=clamp_rate(base[1] * factor, 1.0),
        uwb_rate=clamp_rate(base[2] * factor, 0.5),
        ble_rate=clamp_rate(base[3] * factor, 0.5),
        mesh_rate=MESH_RATES[motion],
        quality=QUALITY_FACTORS[motion],
    )


def savings(current: ScanRates) -> Dict[str, float]:
    def _saving(cur: float, base: float) -> float:
        if base <= 0:
            return 0.0
        return max(0.0, min(1.0, 1.0 - cur / base))

    parts = {
        "lidar": _saving(current.lidar_rate, BASELINE_RATES.lidar_rate),
        "mmwave": _saving(current.mmwave_rate, BASELINE_RATES.mmwave_rate),
        "uwb": _saving(current.uwb_rate, BASELINE_RATES.uwb_rate),
        "ble": _saving(current.ble_rate, BASELINE_RATES.ble_rate),
        "mesh": _saving(current.mesh_rate, BASELINE_RATES.mesh_rate),
    }
    parts["total"] = sum(parts.values()) / 5.0
    return parts


# ─── Energieprofil ─────────────────────────────────────────────────────────


class PowerProfile(Enum):
    PERFORMANCE = "performance"
    BALANCED = "balanced"
    POWER_SAVE = "powersave"
    EMERGENCY = "emergency"


@dataclass
class ResourceState:
    cpu_load: float
    memory_usage: float
    battery_level: int
    battery_temperature: float
    is_charging: bool = False
    network_bandwidth_mbs: float = 0.0


def determine_power_profile(state: ResourceState) -> PowerProfile:
    if state.battery_level < 15:
        return PowerProfile.EMERGENCY
    if state.battery_level < 30 and not state.is_charging:
        return PowerProfile.POWER_SAVE
    if state.cpu_load > 0.7 or state.battery_temperature > 40.0:
        return PowerProfile.POWER_SAVE
    if state.is_charging and state.cpu_load < 0.5:
        return PowerProfile.PERFORMANCE
    return PowerProfile.BALANCED


def scan_rates_for_profile(profile: PowerProfile) -> ScanRates:
    mapping = {
        PowerProfile.PERFORMANCE: ScanRates(20.0, 20.0, 10.0, 10.0, 5.0, 1.0),
        PowerProfile.BALANCED: ScanRates(10.0, 10.0, 5.0, 5.0, 2.0, 0.7),
        PowerProfile.POWER_SAVE: ScanRates(5.0, 5.0, 2.0, 2.0, 1.0, 0.4),
        PowerProfile.EMERGENCY: ScanRates(1.0, 1.0, 0.5, 0.5, 0.0, 0.1),
    }
    return mapping[profile]


def quality_for_profile(profile: PowerProfile) -> float:
    mapping = {
        PowerProfile.PERFORMANCE: 1.0,
        PowerProfile.BALANCED: 0.7,
        PowerProfile.POWER_SAVE: 0.4,
        PowerProfile.EMERGENCY: 0.1,
    }
    return mapping[profile]


# ─── Region-of-Interest ────────────────────────────────────────────────────


@dataclass
class Roi:
    center_x: float
    center_y: float
    center_z: float
    radius: float
    priority: float


class RoiWeightMap:
    def __init__(self, max_rois: int = 10, min_priority: float = 0.3, base_weight: float = 0.5):
        self.max_rois = max_rois
        self.min_priority = min_priority
        self.base_weight = base_weight
        self._rois: List[Roi] = []

    def add(self, roi: Roi) -> None:
        if roi.priority < self.min_priority:
            return
        if any(self._distance(r, roi.center_x, roi.center_y, roi.center_z) < 1e-3 for r in self._rois):
            return
        self._rois.append(roi)
        if len(self._rois) > self.max_rois:
            self._rois.sort(key=lambda r: r.priority, reverse=True)
            self._rois = self._rois[: self.max_rois]

    def remove(self, center_x: float, center_y: float, center_z: float) -> None:
        self._rois = [
            r for r in self._rois
            if self._distance(r, center_x, center_y, center_z) >= 1e-3
        ]

    def clear(self) -> None:
        self._rois.clear()

    def size(self) -> int:
        return len(self._rois)

    def weight_at(self, x: float, y: float, z: float) -> float:
        max_weight = self.base_weight
        for roi in self._rois:
            dist = self._distance(roi, x, y, z)
            if dist < roi.radius:
                weight = roi.priority * (1.0 - dist / roi.radius)
                max_weight = max(max_weight, weight)
        return max(0.1, min(1.0, max_weight))

    @staticmethod
    def _distance(roi: Roi, x: float, y: float, z: float) -> float:
        dx = x - roi.center_x
        dy = y - roi.center_y
        dz = z - roi.center_z
        return math.sqrt(dx * dx + dy * dy + dz * dz)


# ─── Voxel-Fusion ──────────────────────────────────────────────────────────

BASE_VOXEL_SIZE = 0.05
MIN_CONFIDENCE = 0.3
MAX_VOXELS = 50_000


@dataclass
class FusionConfig:
    voxel_size: float
    confidence_threshold: float
    max_voxels: int
    lod_level: int


@dataclass
class FusionVoxel:
    x: float
    y: float
    z: float
    confidence: float
    semantic_type: str = "unknown"
    motion_score: float = 0.0
    last_update: int = int(time.time() * 1000)


def adapt_fusion(cpu_load: float, memory_usage: float, battery_level: int) -> FusionConfig:
    if cpu_load > 0.7 or memory_usage > 0.7 or battery_level < 20:
        size_factor = 2.0
    elif cpu_load > 0.5 or memory_usage > 0.5 or battery_level < 40:
        size_factor = 1.5
    else:
        size_factor = 1.0

    if cpu_load > 0.8 or memory_usage > 0.8:
        lod_level = 2
    elif cpu_load > 0.6 or memory_usage > 0.6:
        lod_level = 1
    else:
        lod_level = 0

    if cpu_load > 0.7 or battery_level < 20:
        threshold = 0.5
    elif cpu_load > 0.5 or battery_level < 40:
        threshold = 0.4
    else:
        threshold = MIN_CONFIDENCE

    return FusionConfig(BASE_VOXEL_SIZE * size_factor, threshold, MAX_VOXELS, lod_level)


def snap_lod(voxel: FusionVoxel, lod_level: int) -> FusionVoxel:
    if lod_level <= 0:
        return voxel
    factor = float(2 ** lod_level)
    return FusionVoxel(
        x=round(voxel.x / factor) * factor,
        y=round(voxel.y / factor) * factor,
        z=round(voxel.z / factor) * factor,
        confidence=voxel.confidence,
        semantic_type=voxel.semantic_type,
        motion_score=voxel.motion_score,
        last_update=voxel.last_update,
    )


def merge_weighted(existing: FusionVoxel, new: FusionVoxel, now_ms: Optional[int] = None) -> FusionVoxel:
    now_ms = now_ms if now_ms is not None else int(time.time() * 1000)
    age_ms = max(0, now_ms - existing.last_update)
    age_weight = math.exp(-(age_ms / 60_000.0))
    weight_existing = existing.confidence * (0.5 + 0.5 * age_weight)
    weight_new = new.confidence
    total = weight_existing + weight_new
    if total <= 0:
        return new
    return FusionVoxel(
        x=(existing.x * weight_existing + new.x * weight_new) / total,
        y=(existing.y * weight_existing + new.y * weight_new) / total,
        z=(existing.z * weight_existing + new.z * weight_new) / total,
        confidence=(existing.confidence * weight_existing + new.confidence * weight_new) / total,
        semantic_type=new.semantic_type if new.confidence > existing.confidence else existing.semantic_type,
        motion_score=max(existing.motion_score, new.motion_score),
        last_update=now_ms,
    )


def _grid_key(voxel: FusionVoxel, size: float) -> tuple:
    return (
        round(voxel.x / size),
        round(voxel.y / size),
        round(voxel.z / size),
    )


def fuse_batch(
    voxels: List[FusionVoxel],
    config: FusionConfig,
    now_ms: Optional[int] = None,
) -> List[FusionVoxel]:
    snapped = [snap_lod(v, config.lod_level) for v in voxels if v.confidence > config.confidence_threshold]
    merged: Dict[tuple, FusionVoxel] = {}
    for voxel in snapped:
        key = _grid_key(voxel, config.voxel_size)
        existing = merged.get(key)
        merged[key] = voxel if existing is None else merge_weighted(existing, voxel, now_ms)
        if len(merged) >= config.max_voxels:
            break
    return list(merged.values())
