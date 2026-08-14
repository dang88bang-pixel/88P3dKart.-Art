"""Tests für die Triangulations-Mathematik (docs/TRIANGULATION.md §5)."""

import numpy as np

from trilateration import (
    RssiKalmanFilter,
    calibrate_path_loss,
    median_filter_rssi,
    rssi_to_distance,
    solve_trilateration,
)

ANCHORS_2D = [
    {"id": "A", "x": 0.0, "y": 0.0, "z": 0.0},
    {"id": "B", "x": 10.0, "y": 0.0, "z": 0.0},
    {"id": "C", "x": 10.0, "y": 10.0, "z": 0.0},
    {"id": "D", "x": 0.0, "y": 10.0, "z": 0.0},
]


def _distances(anchors, x, y, z=0.0):
    return {
        a["id"]: float(np.linalg.norm(np.array([a["x"], a["y"], a["z"]]) - np.array([x, y, z])))
        for a in anchors
    }


def test_2d_exact():
    result = solve_trilateration(ANCHORS_2D, _distances(ANCHORS_2D, 3.5, 2.5), use_z=False)
    assert result is not None
    assert abs(result["x"] - 3.5) < 1e-6
    assert abs(result["y"] - 2.5) < 1e-6
    assert result["converged"] is True
    assert result["confidence"] > 0.9
    assert result["residual_rms_m"] < 1e-6


def test_2d_with_noise():
    rng = np.random.default_rng(42)
    distances = {
        k: d + rng.normal(0.0, 0.4)
        for k, d in _distances(ANCHORS_2D, 4.0, 6.0).items()
    }
    result = solve_trilateration(ANCHORS_2D, distances, use_z=False)
    assert result is not None
    err = np.hypot(result["x"] - 4.0, result["y"] - 6.0)
    assert err < 1.0
    assert result["confidence"] > 0.3


def test_3d_exact():
    anchors = [
        {"id": "A", "x": 0.0, "y": 0.0, "z": 0.0},
        {"id": "B", "x": 10.0, "y": 0.0, "z": 0.0},
        {"id": "C", "x": 0.0, "y": 10.0, "z": 0.0},
        {"id": "D", "x": 0.0, "y": 0.0, "z": 10.0},
    ]
    result = solve_trilateration(anchors, _distances(anchors, 2.0, 3.0, 4.0), use_z=True)
    assert result is not None
    assert abs(result["x"] - 2.0) < 1e-6
    assert abs(result["y"] - 3.0) < 1e-6
    assert abs(result["z"] - 4.0) < 1e-6


def test_insufficient_anchors():
    anchors = ANCHORS_2D[:2]
    distances = _distances(anchors, 2.0, 2.0)
    assert solve_trilateration(anchors, distances, use_z=False) is None
    assert solve_trilateration(anchors, distances, use_z=True) is None


def test_invalid_distances_ignored():
    distances = {"A": 2.0, "B": -5.0, "C": float("nan"), "D": 3.0}
    assert solve_trilateration(ANCHORS_2D, distances, use_z=False) is None


def test_uncertainties_weight_solution():
    distances = _distances(ANCHORS_2D, 4.0, 4.0)
    result = solve_trilateration(ANCHORS_2D, distances, use_z=False)
    assert result is not None
    assert np.isfinite(result["position_sigma_m"])
    # Zentrale Position mit 4 symmetrischen Ankern: σ ≈ 1,0 m (analytisch)
    assert result["position_sigma_m"] <= 1.01


def test_rssi_to_distance():
    assert abs(rssi_to_distance(-40.0, -40.0, 2.0) - 1.0) < 1e-9
    assert abs(rssi_to_distance(-60.0, -40.0, 2.0) - 10.0) < 1e-9
    assert rssi_to_distance(0.0) is None
    assert rssi_to_distance(float("nan")) is None


def test_calibrate_path_loss():
    rng = np.random.default_rng(7)
    reference, exponent = -45.0, 2.5
    samples = []
    for d in (1.0, 2.0, 4.0, 8.0, 16.0):
        rssi = reference - 10.0 * exponent * np.log10(d) + rng.normal(0.0, 1.0)
        samples.append((d, float(rssi)))
    cal = calibrate_path_loss(samples)
    assert cal is not None
    assert abs(cal["path_loss_exponent"] - exponent) < 0.3
    assert abs(cal["reference_rssi_dbm"] - reference) < 3.0
    assert cal["r_squared"] > 0.95
    assert calibrate_path_loss([]) is None
    assert calibrate_path_loss([(1.0, -50.0)]) is None


def test_robust_trilateration_rejects_outlier_anchor():
    """Ein stark verfälschter Distanzwert darf die Lösung nicht ruinieren."""
    anchors = [
        {"id": "A", "x": 0.0, "y": 0.0, "z": 0.0},
        {"id": "B", "x": 12.0, "y": 0.0, "z": 0.0},
        {"id": "C", "x": 12.0, "y": 12.0, "z": 0.0},
        {"id": "D", "x": 0.0, "y": 12.0, "z": 0.0},
        {"id": "E", "x": 6.0, "y": 24.0, "z": 0.0},
    ]
    distances = _distances(anchors, 4.0, 6.0)
    # Anker D +8 m Ausreißer (z. B. Multipath/NLOS)
    distances["D"] += 8.0

    plain = solve_trilateration(anchors, distances, use_z=False, robust_iterations=0)
    robust = solve_trilateration(anchors, distances, use_z=False, robust_iterations=2)

    assert plain is not None and robust is not None
    err_plain = np.hypot(plain["x"] - 4.0, plain["y"] - 6.0)
    err_robust = np.hypot(robust["x"] - 4.0, robust["y"] - 6.0)
    assert err_robust < err_plain, f"robust={err_robust:.2f}m nicht besser als plain={err_plain:.2f}m"
    assert err_robust < 1.0, f"robuste Lösung zu ungenau: {err_robust:.2f}m"
    assert robust.get("rejected_anchors", 0) >= 1


def test_robust_trilateration_keeps_minimum_anchors():
    """Mit nur 3 Ankern (2D) darf kein Anker entfernt werden."""
    anchors = [
        {"id": "A", "x": 0.0, "y": 0.0, "z": 0.0},
        {"id": "B", "x": 10.0, "y": 0.0, "z": 0.0},
        {"id": "C", "x": 0.0, "y": 10.0, "z": 0.0},
    ]
    distances = _distances(anchors, 3.0, 3.0)
    result = solve_trilateration(anchors, distances, use_z=False, robust_iterations=3)
    assert result is not None
    assert result["anchor_count"] == 3
    assert abs(result["x"] - 3.0) < 1e-6
    assert abs(result["y"] - 3.0) < 1e-6


def test_median_filter_suppresses_spikes():
    values = [-60.0, -61.0, -59.0, -62.0, -60.0]
    assert abs(median_filter_rssi(values, window=5) - float(np.median(values[-5:]))) < 1e-9
    # Spike am Fensterrand wird ignoriert (Median ist outlier-robust)
    with_spike = values + [-200.0]
    assert abs(median_filter_rssi(with_spike, window=5) - float(np.median(with_spike[-5:]))) < 1e-9
    assert abs(median_filter_rssi(with_spike, window=5) - (-61.0)) < 1e-9
    # Erst bei kleinem Fenster (Spike-Anteil ≥ 50 %) schlägt der Spike durch
    assert median_filter_rssi(with_spike, window=2) < -100.0
    assert median_filter_rssi([], window=5) == 0.0


def test_rssi_kalman_filter_converges_and_dampens_jumps():
    kalman = RssiKalmanFilter(q=4.0, r=16.0)
    # Konstantes Signal: Filter konvergiert gegen den Wert
    value = 0.0
    for _ in range(30):
        value = kalman.filter("AA:BB", -62.0)
    assert abs(value - (-62.0)) < 1.0
    # Einzelner Sprung wird gedämpft (Gain < 1)
    prev = value
    jumped = kalman.filter("AA:BB", -80.0)
    assert abs(jumped - prev) < abs(-80.0 - prev)
    kalman.clear("AA:BB")
    assert kalman.value("AA:BB") is None
