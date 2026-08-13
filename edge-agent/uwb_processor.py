"""UWB Micro-Doppler: Extraktion der Atemfrequenz (0.15–0.6 Hz) via FFT."""
import logging
from collections import deque

import numpy as np
from scipy.fft import fft, fftfreq

logger = logging.getLogger(__name__)


class UwbDopplerProcessor:
    """Extrahiert die Atemfrequenz aus UWB-Phasen-Daten."""

    def __init__(self, fs: float = 20.0, buffer_secs: float = 5.0):
        self.fs = fs
        self.buffer_secs = buffer_secs
        self.buffer = deque(maxlen=int(fs * buffer_secs))
        self.last_respiration_hz = 0.0
        self.confidence = 0.0

    def feed_phase(self, phase: float) -> None:
        """Fügt ein neues Phasen-Sample hinzu."""
        self.buffer.append(float(phase))

    @property
    def is_ready(self) -> bool:
        return len(self.buffer) >= self.buffer.maxlen

    def detect_respiration(self) -> tuple[float, float]:
        """FFT auf dem Ringbuffer; Peak im Bereich 0.15–0.6 Hz.

        Rückgabe: (Frequenz_Hz, Konfidenz 0–1).
        """
        if not self.is_ready:
            return 0.0, 0.0

        data = np.asarray(self.buffer, dtype=np.float32)
        data = data - np.mean(data)  # DC-Offset entfernen

        window = np.hanning(len(data))
        data = data * window

        fft_vals = fft(data)
        freqs = fftfreq(len(data), 1.0 / self.fs)

        magnitude = np.abs(fft_vals[: len(fft_vals) // 2])
        freqs_pos = freqs[: len(freqs) // 2]

        mask = (freqs_pos >= 0.15) & (freqs_pos <= 0.6)
        if not np.any(mask):
            return 0.0, 0.0

        mag_roi = magnitude[mask]
        freqs_roi = freqs_pos[mask]

        peak_idx = int(np.argmax(mag_roi))
        peak_mag = float(mag_roi[peak_idx])
        peak_freq = float(freqs_roi[peak_idx])

        # Konfidenz = spektrale Energie des Peaks über Gesamtenergie im ROI
        total_energy = float(np.sum(mag_roi))
        self.confidence = min(1.0, peak_mag / total_energy) if total_energy > 1e-6 else 0.0

        # Nur bei Konfidenz > 30 % wird die Atmung als "gesichert" gemeldet
        if self.confidence > 0.3:
            self.last_respiration_hz = peak_freq
        else:
            self.last_respiration_hz = 0.0

        return self.last_respiration_hz, self.confidence
