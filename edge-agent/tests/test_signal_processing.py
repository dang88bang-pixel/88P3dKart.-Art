"""Signalverarbeitung: Kalman (adaptiv), Median+MA, Hampel."""
import random

import pytest

from signal_processing import HampelFilter, KalmanRssiFilter, MedianMovingAverageFilter


def _noisy_signal(n=300, seed=7, base=-65.0, noise_sigma=4.0):
    rng = random.Random(seed)
    return [base + rng.gauss(0, noise_sigma) for _ in range(n)]


def _std(values):
    mean = sum(values) / len(values)
    return (sum((v - mean) ** 2 for v in values) / len(values)) ** 0.5


def test_kalman_reduces_noise_variance():
    raw = _noisy_signal()
    kalman = KalmanRssiFilter(rssi0=raw[0])
    filtered = [kalman.update(v, t=i * 0.05) for i, v in enumerate(raw)]
    # Erwartung: deutliche Varianzreduktion (Spezifikation: ~57 %)
    assert _std(filtered) < _std(raw) * 0.6


def test_kalman_tracks_true_value():
    """Gefilterter Wert konvergiert gegen den wahren RSSI trotz Rauschen."""
    raw = _noisy_signal(base=-70.0, noise_sigma=6.0)
    kalman = KalmanRssiFilter(rssi0=-80.0)
    for i, v in enumerate(raw):
        kalman.update(v, t=i * 0.05)
    assert abs(kalman.x[0] - (-70.0)) < 2.0


def test_kalman_adaptive_r_reacts_to_variance():
    """Adaptives R steigt bei streuenden Messungen."""
    kalman = KalmanRssiFilter(rssi0=-60.0, adaptive=True)
    r_quiet = kalman.R
    for v in _noisy_signal(n=50, base=-60.0, noise_sigma=0.5):
        kalman.update(v)
    r_low = kalman.R
    for v in _noisy_signal(n=50, base=-60.0, noise_sigma=15.0):
        kalman.update(v)
    r_high = kalman.R
    assert r_high > r_low
    assert r_quiet >= 1.0


def test_median_moving_average_removes_spikes():
    values = [-65.0] * 20 + [-40.0, -90.0] + [-65.0] * 20
    out = MedianMovingAverageFilter().process_many(values)
    # Spikes werden gedämpft: Ausreißer-Rest < 8 dBm
    assert abs(out[20] - (-65.0)) < 8.0
    assert abs(out[21] - (-65.0)) < 8.0


def test_hampel_replaces_outliers():
    clean = [-65.0] * 15
    values = clean[:5] + [-25.0] + clean[5:]
    out = HampelFilter(window=5, threshold=3.0).clean(values)
    # Der einzelne Ausreißer wird durch den Median ersetzt
    assert out[5] == -65.0
    assert out[0:5] == clean[:5]
