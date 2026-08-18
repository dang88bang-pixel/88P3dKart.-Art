"""Signalverarbeitung & Rauschunterdrückung (docs/SIGNAL_POSITIONING.md).

- KalmanRssiFilter: 2-Zustands-Kalman [rssi, rate] mit adaptivem Messrauschen R
  (Varianz der letzten Messungen → stärkere Glättung bei Streuung).
- MedianMovingAverageFilter: Median (Ausreißer) → gleitender Mittelwert.
- HampelFilter: Ausreißerkorrektur über Median + MAD im Fenster.
"""
from __future__ import annotations

import time
from collections import deque
from typing import Deque, List, Optional


class KalmanRssiFilter:
    """2-Zustands-Kalman-Filter für RSSI-Signale.

    Zustand x = [rssi, rate]; konstante Geschwindigkeitsannahme.
    Adaptives R: Messrauschen folgt der Varianz der letzten 10 Rohwerte
    (hohe Streuung → stärkere Glättung, niedrige → Vertrauen in Messung).
    """

    def __init__(
        self,
        rssi0: float = -70.0,
        process_noise_q: float = 1.0,
        measurement_noise_r: float = 9.0,
        adaptive: bool = True,
        window: int = 10,
    ) -> None:
        self.x = [float(rssi0), 0.0]
        self.P = [[10.0, 0.0], [0.0, 10.0]]
        self.Q = float(process_noise_q)
        self.R = float(measurement_noise_r)
        self.adaptive = bool(adaptive)
        self._window: Deque[float] = deque(maxlen=max(3, int(window)))
        self._last_t: Optional[float] = None

    def update(self, rssi: float, t: Optional[float] = None) -> float:
        now = float(t) if t is not None else time.time()
        if self._last_t is None:
            dt = 0.05
        else:
            dt = now - self._last_t
            if dt <= 0 or dt > 10.0:
                dt = 0.05
        self._last_t = now

        # Prädiktion (F = [[1, dt], [0, 1]])
        x0 = self.x[0] + dt * self.x[1]
        p00 = self.P[0][0] + 2 * dt * self.P[0][1] + dt * dt * self.P[1][1] + self.Q
        p01 = self.P[0][1] + dt * self.P[1][1]
        p11 = self.P[1][1] + self.Q

        # Adaptives Messrauschen aus der Varianz des Fensters
        if self.adaptive:
            self._window.append(float(rssi))
            if len(self._window) >= 3:
                mean = sum(self._window) / len(self._window)
                var = sum((v - mean) ** 2 for v in self._window) / len(self._window)
                self.R = max(1.0, min(400.0, var * 4.0))

        # Update
        s = p00 + self.R
        k0 = p00 / s
        k1 = p01 / s
        innovation = float(rssi) - x0
        self.x[0] = x0 + k0 * innovation
        self.x[1] = self.x[1] + k1 * innovation
        self.P[0][0] = (1.0 - k0) * p00
        self.P[0][1] = (1.0 - k0) * p01
        self.P[1][0] = self.P[0][1]
        self.P[1][1] = p11 - k1 * p01
        return self.x[0]

    @property
    def rate(self) -> float:
        return self.x[1]


class MedianMovingAverageFilter:
    """Median-Filter (Ausreißer) gefolgt von gleitendem Mittelwert (Glättung).

    Median-Fenster 5, Mittelwert-Fenster 10 — wie in der Spezifikation.
    """

    def __init__(self, median_window: int = 5, moving_window: int = 10) -> None:
        self.median_window: Deque[float] = deque(maxlen=max(3, median_window))
        self.moving_window: Deque[float] = deque(maxlen=max(2, moving_window))

    def process(self, raw: float) -> float:
        self.median_window.append(float(raw))
        median = sorted(self.median_window)[len(self.median_window) // 2]
        self.moving_window.append(median)
        return sum(self.moving_window) / len(self.moving_window)

    def process_many(self, values: List[float]) -> List[float]:
        return [self.process(v) for v in values]


class HampelFilter:
    """Ausreißererkennung & -korrektur: |x - median| > 3·MAD → Median."""

    def __init__(self, window: int = 5, threshold: float = 3.0) -> None:
        self.k = max(1, int(window) // 2)
        self.threshold = float(threshold)

    def clean(self, values: List[float]) -> List[float]:
        n = len(values)
        if n == 0:
            return []
        out = list(values)
        for i in range(n):
            lo = max(0, i - self.k)
            hi = min(n, i + self.k + 1)
            window = sorted(values[lo:hi])
            median = window[len(window) // 2]
            mad = sorted(abs(v - median) for v in window)[len(window) // 2] or 1e-9
            if abs(values[i] - median) > self.threshold * mad:
                out[i] = median
        return out
