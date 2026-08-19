"""Tests für die ressourcensparenden Politiken (docs/RESOURCE_OPT.md)."""

from resource_optimizer import (
    BASELINE_RATES,
    MotionState,
    PowerProfile,
    ResourceState,
    Roi,
    RoiWeightMap,
    ScanRates,
    adapt_fusion,
    battery_factor,
    compute_scan_rates,
    determine_power_profile,
    fuse_batch,
    FusionVoxel,
    merge_weighted,
    motion_state_of,
    quality_for_profile,
    savings,
    scan_rates_for_profile,
    snap_lod,
    thermal_factor,
)


def test_motion_state_tiers():
    assert motion_state_of(0.0) == MotionState.STATIONARY
    assert motion_state_of(0.9) == MotionState.WALKING
    assert motion_state_of(2.0) == MotionState.RUNNING
    assert motion_state_of(8.0) == MotionState.VEHICLE


def test_scan_rates_scale_with_battery_and_thermal():
    fast = compute_scan_rates(2.0, 100, 25.0)
    slow = compute_scan_rates(2.0, 20, 50.0)
    assert fast.lidar_rate > slow.lidar_rate
    assert slow.lidar_rate >= 1.0  # Mindestrate bleibt erhalten
    # Stillstand reduziert auf Minimum
    stationary = compute_scan_rates(0.0, 100, 25.0)
    assert stationary.lidar_rate == 2.0
    assert stationary.mesh_rate == 0.5


def test_savings_summary_in_range():
    rates = compute_scan_rates(0.0, 100, 25.0)
    result = savings(rates)
    assert result["lidar"] > 0.5  # 2/20 → 90 % Einsparung
    assert 0.0 <= result["total"] <= 1.0


def test_power_profile_thresholds():
    assert determine_power_profile(ResourceState(0.3, 0.4, 10, 30.0)) == PowerProfile.EMERGENCY
    assert determine_power_profile(ResourceState(0.3, 0.4, 20, 30.0)) == PowerProfile.POWER_SAVE
    assert determine_power_profile(ResourceState(0.8, 0.4, 60, 30.0)) == PowerProfile.POWER_SAVE
    assert determine_power_profile(ResourceState(0.3, 0.4, 60, 45.0)) == PowerProfile.POWER_SAVE
    assert determine_power_profile(ResourceState(0.4, 0.4, 60, 30.0, is_charging=True)) == PowerProfile.PERFORMANCE
    assert determine_power_profile(ResourceState(0.4, 0.4, 60, 30.0)) == PowerProfile.BALANCED


def test_profile_rate_and_quality_mapping():
    assert scan_rates_for_profile(PowerProfile.PERFORMANCE) == BASELINE_RATES
    assert scan_rates_for_profile(PowerProfile.EMERGENCY).mesh_rate == 0.0
    assert quality_for_profile(PowerProfile.PERFORMANCE) == 1.0
    assert quality_for_profile(PowerProfile.EMERGENCY) == 0.1


def test_roi_weight_falloff_and_priority():
    roi_map = RoiWeightMap(max_rois=2, min_priority=0.3)
    roi_map.add(Roi(0.0, 0.0, 0.0, 2.0, 1.0))
    # Zentrum: volle Priorität
    assert abs(roi_map.weight_at(0.0, 0.0, 0.0) - 1.0) < 1e-6
    # Halber Radius: 1 · (1 − 1/2) = 0,5 → Basis 0,5 → bleibt 0,5
    assert abs(roi_map.weight_at(1.0, 0.0, 0.0) - 0.5) < 1e-6
    # Außerhalb: Basis-Gewichtung
    assert abs(roi_map.weight_at(10.0, 0.0, 0.0) - 0.5) < 1e-6
    # Kapazität: nur die höchste Priorität bleibt
    roi_map.add(Roi(5.0, 5.0, 0.0, 1.0, 0.5))
    roi_map.add(Roi(-5.0, -5.0, 0.0, 1.0, 0.9))
    assert roi_map.size() == 2
    assert abs(roi_map.weight_at(-5.0, -5.0, 0.0) - 0.9) < 1e-6


def test_roi_ignores_low_priority_and_duplicates():
    roi_map = RoiWeightMap(min_priority=0.3)
    roi_map.add(Roi(0.0, 0.0, 0.0, 1.0, 0.2))  # unter Schwelle
    assert roi_map.size() == 0
    roi_map.add(Roi(1.0, 1.0, 1.0, 1.0, 0.8))
    roi_map.add(Roi(1.0, 1.0, 1.0, 1.0, 0.9))  # Duplikat
    assert roi_map.size() == 1


def test_fusion_adaptation_levels():
    assert adapt_fusion(0.1, 0.2, 80).lod_level == 0
    assert adapt_fusion(0.7, 0.2, 80).lod_level == 1
    assert adapt_fusion(0.9, 0.9, 80).lod_level == 2
    assert adapt_fusion(0.9, 0.9, 80).voxel_size > adapt_fusion(0.1, 0.2, 80).voxel_size


def test_snap_lod_rounds_to_grid():
    voxel = FusionVoxel(0.13, -0.07, 0.9, 0.8)
    snapped = snap_lod(voxel, lod_level=1)
    assert snapped.x == 0.0 or abs(snapped.x - 0.0) < 1e-6
    # 0.13/2 gerundet = 0 → 0.0; −0.07 → 0.0
    assert snapped.z in (0.0, 1.0, 2.0)


def test_merge_weighted_prefers_fresh_confident():
    old = FusionVoxel(0.0, 0.0, 0.0, 0.8, last_update=0)
    new = FusionVoxel(1.0, 0.0, 0.0, 0.9, last_update=60_000)
    merged = merge_weighted(old, new, now_ms=60_000)
    # Alter Voxel (60 s) wird abgewertet → Position rückt Richtung neuem
    assert 0.5 < merged.x <= 1.0
    assert merged.confidence > 0.8
    assert merged.motion_score == max(old.motion_score, new.motion_score)


def test_fuse_batch_filters_merges_and_caps():
    config = adapt_fusion(0.1, 0.2, 80)
    # Fix: explizite last_update-Werte — der Alters-Decay in merge_weighted
    # machte den Test zeitabhängig-flaky (0.8495 vs. 0.85-Schwelle).
    now = 60_000
    voxels = [
        FusionVoxel(0.01, 0.01, 0.0, 0.9, last_update=now),   # Zelle (0,0)
        FusionVoxel(0.02, 0.01, 0.0, 0.8, last_update=now),   # gleiche Zelle → Merge
        FusionVoxel(1.0, 1.0, 0.0, 0.2, last_update=now),     # unter Konfidenzschwelle → weg
        FusionVoxel(2.0, 2.0, 0.0, 0.7, last_update=now),
    ]
    fused = fuse_batch(voxels, config, now_ms=now)
    assert len(fused) == 2
    # Merge beider Zelle-(0,0)-Voxel: Position bleibt ≈ 0,01, Konfidenz steigt
    cell = next(v for v in fused if v.confidence > 0.8)
    assert cell.confidence > 0.85
