"""Tests für die Radar-Signalverarbeitung (docs/PERSON_DETECTION.md)."""

import math

import pytest

from radar_processing import (
    ca_cfar,
    ca_cfar_threshold_factor,
    doppler_velocity,
    doppler_velocity_profile,
    mti_double_canceler,
    mti_single_canceler,
    moving_energy_ratio,
    MultiTargetTracker,
    phase_difference,
)

# ─── CA-CFAR ───────────────────────────────────────────────────────────────


def _range_profile(peaks, n=256, noise=0.02, peak_power=1.0):
    """Range-Profil: Rauschboden + Gauß-Peaks an den gegebenen Positionen."""
    import random

    rng = random.Random(42)
    profile = [noise * (0.5 + rng.random()) for _ in range(n)]
    for pos in peaks:
        for i in range(max(0, pos - 3), min(n, pos + 4)):
            profile[i] += peak_power * math.exp(-((i - pos) ** 2) / 2.0)
    return profile


def test_threshold_factor_matches_classic_formula():
    alpha = ca_cfar_threshold_factor(16, 1e-4)
    expected = 16 * (1e-4 ** (-1 / 16) - 1)
    assert abs(alpha - expected) < 1e-12


def test_cfar_detects_peaks_above_adaptive_floor():
    profile = _range_profile([50, 150], peak_power=4.0)
    detections = ca_cfar(profile, guard_cells=2, training_cells=8, pfa=1e-4)
    indices = [d.index for d in detections]
    assert 50 in indices and 150 in indices
    assert all(d.snr_db > 8.0 for d in detections)


def test_cfar_ignores_pure_noise():
    profile = _range_profile([])
    assert ca_cfar(profile, pfa=1e-4) == []


def test_cfar_no_peak_grouping_duplicates():
    profile = _range_profile([100], peak_power=4.0)
    detections = ca_cfar(profile, guard_cells=3, training_cells=8)
    # Ein Peak → genau eine Detektion (kein Doppeltreffer im Fenster)
    assert len(detections) == 1


# ─── MTI ───────────────────────────────────────────────────────────────────


def test_mti_removes_static_clutter_keeps_mover():
    static = [1.0, 2.0, 3.0, 4.0]
    mover_frame = [1.0, 2.0, 5.0, 4.0]  # Bin 2 hat sich bewegt
    filtered = mti_single_canceler(mover_frame, static)
    assert filtered == [0.0, 0.0, 2.0, 0.0]
    ratio = moving_energy_ratio(filtered, mover_frame)
    assert 0.0 < ratio < 1.0


def test_mti_double_canceler_linear_ramp():
    # Linearer Rampen-Clutter (z. B. Drift) verschwindet im Double Canceler
    f0 = [0.0, 1.0, 2.0, 3.0]
    f1 = [1.0, 2.0, 3.0, 4.0]
    f2 = [2.0, 3.0, 4.0, 5.0]
    filtered = mti_double_canceler(f2, f1, f0)
    assert all(abs(v) < 1e-12 for v in filtered)


def test_moving_energy_ratio_bounds():
    assert moving_energy_ratio([0, 0, 0], [1, 2, 3]) == 0.0
    assert moving_energy_ratio([1, 2, 3], [1, 2, 3]) == 1.0
    with pytest.raises(ValueError):
        moving_energy_ratio([1], [1, 2])


# ─── Doppler ───────────────────────────────────────────────────────────────


def test_phase_difference_wraps_correctly():
    assert abs(phase_difference(0.1, 0.0) - 0.1) < 1e-12
    # 3,0 − (−3,0) = 6,0 → wrapped −0,283…
    assert abs(phase_difference(3.0, -3.0) - (-0.2831853071795862)) < 1e-9
    assert abs(phase_difference(-3.0, 3.0) - 0.2831853071795862) < 1e-9


def test_doppler_velocity_known_motion():
    # λ = 4 mm (77 GHz), T = 50 ms, Phasenverschiebung π/2 → v = λ·(π/2)/(4π·T)
    wavelength = 4e-3
    frame_time = 50e-3
    velocity = doppler_velocity(math.pi / 2, 0.0, wavelength, frame_time)
    expected = wavelength / (8 * frame_time)
    assert abs(velocity - expected) < 1e-12


def test_doppler_velocity_profile():
    profile = doppler_velocity_profile([0.5, 1.0], [0.0, 0.0], 4e-3, 50e-3)
    assert abs(profile[0] - doppler_velocity(0.5, 0.0, 4e-3, 50e-3)) < 1e-12
    with pytest.raises(ValueError):
        doppler_velocity_profile([0.1], [0.0, 0.1], 4e-3, 50e-3)


# ─── Multi-Target-Tracker ──────────────────────────────────────────────────


def _moving_target(t0, speed, steps, noise_rng, noise_sigma=0.1):
    pts = []
    for i in range(steps):
        pts.append(
            (
                t0[0] + speed[0] * i + noise_rng.gauss(0, noise_sigma),
                t0[1] + speed[1] * i + noise_rng.gauss(0, noise_sigma),
            )
        )
    return pts


def test_tracker_tracks_two_targets_with_stable_ids():
    import random

    rng = random.Random(7)
    target_a = _moving_target((0.0, 0.0), (0.5, 0.0), 20, rng)
    target_b = _moving_target((5.0, 5.0), (-0.5, 0.0), 20, rng)

    tracker = MultiTargetTracker(gate_distance=1.0)
    # Messungen liegen pro Scan-Index vor (Schrittweite 1) → dt = 1.0
    for i in range(20):
        confirmed = tracker.update([target_a[i], target_b[i]], dt=1.0)

    assert len(confirmed) == 2
    ids = sorted(t.id for t in confirmed)
    assert ids == [1, 2]

    # Track A: Position ≈ letzte Messung, Geschwindigkeit ≈ 0,5 in x
    track_a = next(t for t in confirmed if t.id == 1)
    assert abs(track_a.x[0] - target_a[-1][0]) < 0.5
    assert abs(track_a.x[2] - 0.5) < 0.4
    track_b = next(t for t in confirmed if t.id == 2)
    assert abs(track_b.x[2] - (-0.5)) < 0.4


def test_tracker_confirmation_requires_three_hits():
    tracker = MultiTargetTracker(confirm_hits=3)
    assert tracker.update([(0.0, 0.0)], dt=0.1) == []
    assert tracker.update([(0.1, 0.0)], dt=0.1) == []
    confirmed = tracker.update([(0.2, 0.0)], dt=0.1)
    assert len(confirmed) == 1


def test_tracker_coasts_through_missing_detections():
    tracker = MultiTargetTracker(max_misses=4)
    for i in range(5):
        tracker.update([(0.1 * i, 0.0)], dt=0.1)
    # 3 Fehlscans → bestätigter Track bleibt (Coasting)
    for _ in range(3):
        confirmed = tracker.update([], dt=0.1)
    assert len(confirmed) == 1
    # Mehr als 2×max_misses → Track wird verworfen
    for _ in range(6):
        confirmed = tracker.update([], dt=0.1)
    assert confirmed == []


def test_tracker_gate_ignores_far_detections():
    tracker = MultiTargetTracker(gate_distance=1.0)
    tracker.update([(0.0, 0.0)], dt=0.1)
    tracker.update([(0.1, 0.0)], dt=0.1)
    # Detektion 10 m entfernt → neuer Track statt Zuordnung
    tracker.update([(0.2, 0.0), (10.0, 0.0)], dt=0.1)
    assert len(tracker.tracks) == 2
