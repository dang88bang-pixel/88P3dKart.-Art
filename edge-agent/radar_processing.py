"""Radar-/UWB-Signalverarbeitung — Python-Kern (docs/PERSON_DETECTION.md).

Portiert die in der v13-Recherche genannten, **hardware-unabhängigen**
Mechanismen als pure, testbare Algorithmen:

- **CA-CFAR** (Cell-Averaging Constant False Alarm Rate) — Objektdetektion
  über adaptiven Rauschboden (IR-UWB Radar Human Detection, RadarHPE),
- **MTI-Filter** (statische Clutter-Entfernung) — Moving Target Indication
  per Single-/Double-Canceler (TI Edge AI SDK-Mechanismus),
- **Doppler-Geschwindigkeit** — v = λ·Δφ / (4π·T) aus Phasendifferenzen
  aufeinanderfolgender Frames (FMCW-Doppler-Mechanismus),
- **Multi-Target-Tracker** — Nearest-Neighbor-Assoziation + CV-Kalman-Filter
  je Track (4 Zustände [x, y, vx, vy], Positionsmessung), Gating, Coasting,
  Track-Bestätigung („Objekt-Clustering und -Tracking", „Echtzeit-Tracking
  mit Kalman-Filter").

Die Matrix-Operationen sind explizit (ohne numpy) implementiert, damit die
Numerik 1:1 zur Kotlin-Spiegelung (`com.example.agent.radar`) passt. Die
tiefen Lernmodelle (Pose-Estimation, Gang-ID) benötigen Modell-Assets und
bleiben Roadmap (siehe Doku).
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import List, Optional, Sequence, Tuple

# ─── Kleine Matrix-Helfer (zeilenweise, float) ─────────────────────────────


def _mat_mul(a: Sequence[float], b: Sequence[float], n: int, m: int, k: int) -> List[float]:
    """a (n×m) · b (m×k) → n×k, zeilenweise."""
    out = [0.0] * (n * k)
    for i in range(n):
        for j in range(k):
            s = 0.0
            for t in range(m):
                s += a[i * m + t] * b[t * k + j]
            out[i * k + j] = s
    return out


def _transpose(a: Sequence[float], n: int, m: int) -> List[float]:
    return [a[r * m + c] for c in range(m) for r in range(n)]


def _mat_add(a: Sequence[float], b: Sequence[float]) -> List[float]:
    return [x + y for x, y in zip(a, b)]


def _inv2(m: Sequence[float]) -> List[float]:
    det = m[0] * m[3] - m[1] * m[2]
    if abs(det) < 1e-12:
        raise ValueError("singuläre 2×2-Matrix")
    return [m[3] / det, -m[1] / det, -m[2] / det, m[0] / det]


# ─── CA-CFAR ───────────────────────────────────────────────────────────────


@dataclass
class CfarDetection:
    index: int
    value: float
    threshold: float
    snr_db: float


def ca_cfar_threshold_factor(num_training_cells: int, pfa: float) -> float:
    """α = N · (PFA^(−1/N) − 1) — klassischer CA-CFAR-Schwellwertfaktor."""
    if num_training_cells <= 0:
        raise ValueError("num_training_cells muss > 0 sein")
    if not 0.0 < pfa < 1.0:
        raise ValueError("pfa muss in (0,1) liegen")
    return num_training_cells * (pfa ** (-1.0 / num_training_cells) - 1.0)


def ca_cfar(
    signal: Sequence[float],
    guard_cells: int = 2,
    training_cells: int = 8,
    pfa: float = 1e-4,
    min_snr_db: float = 8.0,
) -> List[CfarDetection]:
    """CA-CFAR-Detektion über ein Range-Profil/Leistungsspektrum.

    Für jede Zelle: Schwelle = α · Mittelwert der Trainingszellen
    (Guard-Zellen beidseitig ausgespart). Detektionen sind lokale Maxima
    über der Schwelle; Peak-Grouping verhindert Mehrfachtreffer im
    Guard-Fenster.
    """
    if len(signal) == 0:
        return []
    alpha = ca_cfar_threshold_factor(training_cells * 2, pfa)
    half_window = guard_cells + training_cells
    detections: List[CfarDetection] = []
    last_peak_index = -10**9

    for i, value in enumerate(signal):
        window: List[float] = []
        for j in range(i - half_window, i - guard_cells):
            if 0 <= j < len(signal):
                window.append(signal[j])
        for j in range(i + guard_cells + 1, i + half_window + 1):
            if 0 <= j < len(signal):
                window.append(signal[j])
        if len(window) < training_cells:
            continue

        noise = sum(window) / len(window)
        threshold = alpha * noise
        snr = value / noise if noise > 0 else float("inf")
        snr_db = 10.0 * math.log10(snr) if snr > 0 else -math.inf

        if value <= threshold or snr_db < min_snr_db:
            continue
        local_peak = all(
            signal[j] <= value
            for j in range(max(0, i - guard_cells), min(len(signal), i + guard_cells + 1))
            if j != i
        )
        if not local_peak or i - last_peak_index <= guard_cells:
            continue
        detections.append(CfarDetection(index=i, value=value, threshold=threshold, snr_db=snr_db))
        last_peak_index = i

    return detections


# ─── MTI (statische Clutter-Entfernung) ────────────────────────────────────


def mti_single_canceler(frame: Sequence[float], previous: Sequence[float]) -> List[float]:
    """Single Canceler: y[n] = x[n] − x[n−1] (entfernt statischen Clutter)."""
    if len(frame) != len(previous):
        raise ValueError("Frames müssen gleich lang sein")
    return [float(a) - float(b) for a, b in zip(frame, previous)]


def mti_double_canceler(
    frame: Sequence[float],
    previous: Sequence[float],
    pre_previous: Sequence[float],
) -> List[float]:
    """Double Canceler: y[n] = x[n] − 2·x[n−1] + x[n−2]."""
    if not (len(frame) == len(previous) == len(pre_previous)):
        raise ValueError("Frames müssen gleich lang sein")
    return [
        float(a) - 2.0 * float(b) + float(c)
        for a, b, c in zip(frame, previous, pre_previous)
    ]


def moving_energy_ratio(filtered: Sequence[float], original: Sequence[float]) -> float:
    """Anteil bewegter Energie nach MTI (0 = alles statisch, 1 = alles bewegt)."""
    if len(filtered) != len(original) or len(original) == 0:
        raise ValueError("Frames müssen gleich lang und nicht leer sein")
    energy_filtered = sum(v * v for v in filtered)
    energy_original = sum(v * v for v in original)
    if energy_original <= 0:
        return 0.0
    return energy_filtered / energy_original


# ─── Doppler-Geschwindigkeit ───────────────────────────────────────────────


def phase_difference(phase_current: float, phase_previous: float) -> float:
    """Vorzeichenrichtige Phasendifferenz in [−π, π]."""
    return (phase_current - phase_previous + math.pi) % (2.0 * math.pi) - math.pi


def doppler_velocity(
    phase_current: float,
    phase_previous: float,
    wavelength: float,
    frame_time: float,
) -> float:
    """v = λ·Δφ / (4π·T) — radiale Geschwindigkeit aus der Phasendifferenz."""
    if frame_time <= 0:
        raise ValueError("frame_time muss > 0 sein")
    return wavelength * phase_difference(phase_current, phase_previous) / (4.0 * math.pi * frame_time)


def doppler_velocity_profile(
    phases_current: Sequence[float],
    phases_previous: Sequence[float],
    wavelength: float,
    frame_time: float,
) -> List[float]:
    """Vektorisierte Variante über alle Range-Bins."""
    if len(phases_current) != len(phases_previous):
        raise ValueError("Phasenvektoren müssen gleich lang sein")
    return [
        doppler_velocity(c, p, wavelength, frame_time)
        for c, p in zip(phases_current, phases_previous)
    ]


# ─── Multi-Target-Tracker (NN + CV-Kalman) ─────────────────────────────────


@dataclass
class Track:
    id: int
    x: List[float]  # Zustand [x, y, vx, vy]
    p: List[float]  # Kovarianz 4×4, zeilenweise
    hits: int = 1
    misses: int = 0

    @property
    def confirmed(self) -> bool:
        return self.hits >= 3

    def predict(self, dt: float, process_noise: float) -> None:
        # x' = F·x (konstante Geschwindigkeit)
        x, y, vx, vy = self.x
        self.x = [x + vx * dt, y + vy * dt, vx, vy]
        # P' = F·P·Fᵀ + Q — Q als Piecewise-White-Noise-Modell
        # (Beschleunigungsrauschen q_a): korrekte Einheiten, damit die
        # Geschwindigkeit konvergieren kann und der Track nicht überkonfident
        # hinterherhinkt.
        f = [
            1.0, 0.0, dt, 0.0,
            0.0, 1.0, 0.0, dt,
            0.0, 0.0, 1.0, 0.0,
            0.0, 0.0, 0.0, 1.0,
        ]
        qa = process_noise  # Intensität des Beschleunigungsrauschens
        dt2 = dt * dt
        dt3 = dt2 * dt
        dt4 = dt2 * dt2
        q = [
            qa * dt4 / 4.0, 0.0, qa * dt3 / 2.0, 0.0,
            0.0, qa * dt4 / 4.0, 0.0, qa * dt3 / 2.0,
            qa * dt3 / 2.0, 0.0, qa * dt2, 0.0,
            0.0, qa * dt3 / 2.0, 0.0, qa * dt2,
        ]
        self.p = _mat_add(_mat_mul(_mat_mul(f, self.p, 4, 4, 4), _transpose(f, 4, 4), 4, 4, 4), q)

    def update(self, zx: float, zy: float, measurement_noise: float, dt: float = 0.1) -> None:
        if self.hits == 1:
            # Zwei-Punkt-Initialisierung: Geschwindigkeit direkt aus den
            # ersten beiden Messungen schätzen (eliminiert den Startup-Lag
            # einer v=0-Initialisierung, der Tracks aus dem Gate driften lässt).
            if dt > 0:
                self.x = [zx, zy, (zx - self.x[0]) / dt, (zy - self.x[1]) / dt]
            else:
                self.x = [zx, zy, 0.0, 0.0]
            self.p = [
                1.0, 0.0, 0.0, 0.0,
                0.0, 1.0, 0.0, 0.0,
                0.0, 0.0, 0.25, 0.0,
                0.0, 0.0, 0.0, 0.25,
            ]
            self.hits = 2
            self.misses = 0
            return
        h = [1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0]
        r = [measurement_noise, 0.0, 0.0, measurement_noise]
        s = _mat_add(_mat_mul(_mat_mul(h, self.p, 2, 4, 4), _transpose(h, 2, 4), 2, 4, 2), r)
        s_inv = _inv2(s)
        k = _mat_mul(_mat_mul(self.p, _transpose(h, 2, 4), 4, 4, 2), s_inv, 4, 2, 2)
        innovation = [zx - self.x[0], zy - self.x[1]]
        for i in range(4):
            self.x[i] += k[i * 2] * innovation[0] + k[i * 2 + 1] * innovation[1]
        kh = _mat_mul(k, h, 4, 2, 4)
        identity = [1.0 if i == j else 0.0 for i in range(4) for j in range(4)]
        self.p = _mat_mul([identity[i] - kh[i] for i in range(16)], self.p, 4, 4, 4)
        self.hits += 1
        self.misses = 0


class MultiTargetTracker:
    """Nearest-Neighbor-Multi-Target-Tracking (CV-Kalman je Track)."""

    def __init__(
        self,
        gate_distance: float = 1.0,
        max_misses: float = 4.0,
        confirm_hits: int = 3,
        measurement_noise: float = 0.25,
        process_noise: float = 0.1,
    ) -> None:
        self.gate_distance = gate_distance
        self.max_misses = max_misses
        self.confirm_hits = confirm_hits
        self.measurement_noise = measurement_noise
        self.process_noise = process_noise
        self._tracks: List[Track] = []
        self._next_id = 1

    @property
    def tracks(self) -> List[Track]:
        return list(self._tracks)

    @property
    def confirmed_tracks(self) -> List[Track]:
        return [t for t in self._tracks if t.confirmed]

    def update(self, detections: Sequence[Tuple[float, float]], dt: float) -> List[Track]:
        """Predict → Assoziation (NN mit Gating) → Update → Coasting."""
        for track in self._tracks:
            track.predict(dt, self.process_noise)

        unmatched = list(detections)
        if not unmatched:
            # Keine Detektionen → alle Tracks coasten (Miss zählen)
            for track in self._tracks:
                track.misses += 1
        else:
            for track in self._tracks:
                if not unmatched:
                    break
                best_index = -1
                best_distance = float("inf")
                for i, (zx, zy) in enumerate(unmatched):
                    d = math.hypot(zx - track.x[0], zy - track.x[1])
                    if d < best_distance:
                        best_distance = d
                        best_index = i
                if best_index >= 0 and best_distance <= self.gate_distance:
                    zx, zy = unmatched.pop(best_index)
                    track.update(zx, zy, self.measurement_noise, dt)
                else:
                    track.misses += 1

        for zx, zy in unmatched:
            self._tracks.append(
                Track(
                    id=self._next_id,
                    x=[float(zx), float(zy), 0.0, 0.0],
                    p=[
                        1.0, 0.0, 0.0, 0.0,
                        0.0, 1.0, 0.0, 0.0,
                        0.0, 0.0, 0.5, 0.0,
                        0.0, 0.0, 0.0, 0.5,
                    ],
                )
            )
            self._next_id += 1

        # Coasting: verwaiste Tracks entfernen (unbestätigte nach
        # max_misses, bestätigte erst nach 2×max_misses)
        self._tracks = [
            t for t in self._tracks
            if t.misses <= (self.max_misses * 2 if t.confirmed else self.max_misses)
        ]
        return self.confirmed_tracks
