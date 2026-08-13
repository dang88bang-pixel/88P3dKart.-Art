import numpy as np

from uwb_processor import UwbDopplerProcessor


def test_detects_synthetic_respiration():
    fs = 20.0
    proc = UwbDopplerProcessor(fs=fs, buffer_secs=5.0)
    t = np.arange(0, 5.0, 1.0 / fs)
    # Atmung bei 0.4 Hz (im Bereich 0.15–0.6 Hz)
    phase = np.sin(2 * np.pi * 0.4 * t) + 0.05 * np.random.default_rng(0).standard_normal(len(t))
    for s in phase:
        proc.feed_phase(s)
    freq, conf = proc.detect_respiration()
    assert 0.3 < freq < 0.5, f"Erwartete ~0.4 Hz, bekam {freq}"
    assert conf > 0.3


def test_empty_buffer_returns_zero():
    proc = UwbDopplerProcessor(fs=20.0, buffer_secs=5.0)
    freq, conf = proc.detect_respiration()
    assert freq == 0.0 and conf == 0.0
