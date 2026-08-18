"""Positionierung: Pfadverlust, WCL, Auto-Schätzung, Fingerprinting."""
import pytest

from positioning import (
    CALIBRATION_N,
    FingerprintDB,
    estimate_position,
    path_loss_distance,
    weighted_centroid,
)


def test_path_loss_matches_model():
    # d = 10^((Tx-RSSI)/(10·n)) mit Kalibrierung n=1.64
    assert path_loss_distance(-61.92, tx_power=-59.0) == pytest.approx(
        10 ** ((-59.0 + 61.92) / (10 * CALIBRATION_N)), rel=1e-9
    )
    # stärkeres Signal → kürzere Distanz
    assert path_loss_distance(-50.0) < path_loss_distance(-70.0)


def test_weighted_centroid_prefers_closest_anchor():
    anchors = [
        {"x": 0.0, "y": 0.0, "distance": 1.0},
        {"x": 10.0, "y": 0.0, "distance": 9.0},
    ]
    x, y = weighted_centroid(anchors)
    assert 0.0 < x < 1.5  # nah am näheren Anker
    assert y == pytest.approx(0.0, abs=1e-6)


def test_weighted_centroid_rejects_empty():
    with pytest.raises(ValueError):
        weighted_centroid([])


def test_estimate_position_uses_trilateration_with_enough_anchors():
    anchors = [
        {"id": "a1", "x": 0.0, "y": 0.0, "distance": 5.0},
        {"id": "a2", "x": 10.0, "y": 0.0, "distance": 5.0},
        {"id": "a3", "x": 5.0, "y": 7.07, "distance": 7.07},
    ]
    result = estimate_position(anchors)
    assert result is not None
    assert result["method"] == "trilateration"
    assert abs(result["position"]["x"] - 5.0) < 0.5
    assert abs(result["position"]["y"] - 0.0) < 1.0


def test_estimate_position_falls_back_to_wcl():
    result = estimate_position([{"id": "a1", "x": 0.0, "y": 0.0, "distance": 2.0}])
    assert result is not None
    assert result["method"] == "weighted_centroid"
    assert result["position"]["x"] == pytest.approx(0.0, abs=1e-3)


def test_estimate_position_none_without_anchors():
    assert estimate_position([]) is None


def test_fingerprint_knn_and_wknn():
    db = FingerprintDB()
    db.add(52.5160, 13.3770, {"beacon-1": -50, "beacon-2": -60})
    db.add(52.5200, 13.3900, {"beacon-1": -80, "beacon-2": -75})
    near = db.locate({"beacon-1": -51, "beacon-2": -61}, k=1)
    assert near["method"] == "fingerprint_wknn"
    assert near["position"]["x"] == pytest.approx(52.5160, abs=0.001)
    far = db.locate({"beacon-1": -81, "beacon-2": -76}, k=1)
    assert far["position"]["x"] == pytest.approx(52.5200, abs=0.001)
    # k-NN ungewichtet
    knn = db.locate({"beacon-1": -50, "beacon-2": -60}, k=1, weighted=False)
    assert knn["method"] == "fingerprint_knn"
    # ohne Daten
    assert FingerprintDB().locate({}) is None


def test_fingerprint_missing_keys_penalized():
    db = FingerprintDB()
    db.add(1.0, 1.0, {"b1": -50, "b2": -55})
    db.add(2.0, 2.0, {"b1": -70})
    # Query passt exakt auf den ersten Fingerprint (beide Keys vorhanden)
    result = db.locate({"b1": -50, "b2": -55}, k=1)
    assert result["position"]["x"] == pytest.approx(1.0, abs=0.001)
