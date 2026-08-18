"""Positionsbestimmung (docs/SIGNAL_POSITIONING.md).

- Pfadverlustmodell (log-distance path loss) mit Kalibrierungskonstanten
- Trilateration (bestehende, robuste Implementierung in trilateration.py)
- Weighted Centroid Localization (WCL) für < 3 Anker / hohes Rauschen
- Fingerprinting: k-NN & weighted k-NN über RSSI-Vektoren
- estimate_position: automatische Methodenwahl (≥3 Anker → Trilateration,
  sonst WCL) — der Produktionspfad für die App.
"""
from __future__ import annotations

import math
from typing import Dict, List, Optional, Tuple

from trilateration import solve_trilateration

# Kalibrierungswerte aus der Spezifikation (ESP32-Feldmessung):
# A = -61.92 dBm @ 1 m, n = 1.64 (Umgebungsfaktor).
CALIBRATION_RSSI_1M = -61.92
CALIBRATION_N = 1.64
DEFAULT_TX_POWER = -59.0


def path_loss_distance(
    rssi: float,
    tx_power: float = DEFAULT_TX_POWER,
    n: float = CALIBRATION_N,
    d0: float = 1.0,
) -> float:
    """Distanz aus RSSI über das Log-Distance-Path-Loss-Modell.

    PL(d) = PL(d0) + 10·n·log10(d/d0)  →  d = d0 · 10^((Tx-RSSI)/(10·n))
    """
    ratio = (float(tx_power) - float(rssi)) / (10.0 * float(n))
    return float(d0) * (10.0 ** ratio)


def weighted_centroid(
    anchors: List[Dict[str, float]],
    epsilon: float = 0.1,
) -> Tuple[float, float]:
    """Weighted Centroid Localization.

    Position = Σ(w_i · P_i) / Σ w_i  mit  w_i = 1/(d_i + ε).
    Liefert (None, None) ohne Anker.
    """
    if not anchors:
        raise ValueError("Mindestens ein Anker benötigt")
    total_w = 0.0
    wx = 0.0
    wy = 0.0
    for a in anchors:
        w = 1.0 / (float(a["distance"]) + epsilon)
        wx += w * float(a["x"])
        wy += w * float(a["y"])
        total_w += w
    if total_w <= 0:
        raise ValueError("Ungültige Anker-Gewichte")
    return wx / total_w, wy / total_w


def estimate_position(
    anchors: List[Dict[str, float]],
    use_z: bool = False,
) -> Optional[dict]:
    """Automatische Positionsschätzung (App-Produktionspfad).

    ≥ 3 Anker (3D: ≥ 4) → robuste Trilateration (LTS-Ausreißerbehandlung),
    sonst → Weighted Centroid. Einheitliche Antwortstruktur:
    {"method", "position": {x,y,z}, "anchor_count", "confidence"}.
    """
    if not anchors:
        return None
    normalized = [{**a, "z": float(a.get("z", 0.0))} for a in anchors]
    if len(normalized) >= (4 if use_z else 3):
        result = solve_trilateration(
            normalized,
            {a["id"]: float(a["distance"]) for a in normalized},
            uncertainties={a["id"]: float(a.get("sigma") or 1.0) for a in normalized},
            use_z=use_z,
        )
        if result is not None:
            return {
                "method": "trilateration",
                "position": {
                    "x": round(result["x"], 3),
                    "y": round(result["y"], 3),
                    "z": round(result["z"], 3),
                },
                "anchor_count": result.get("anchor_count", len(normalized)),
                "converged": result.get("converged", False),
                "iterations": result.get("iterations"),
                "confidence": 0.95,
            }
    # Fallback: WCL (weniger Anker / hohes Rauschen / Singularität)
    try:
        x, y = weighted_centroid(normalized)
    except ValueError:
        return None
    return {
        "position": {"x": round(x, 3), "y": round(y, 3), "z": 0.0},
        "method": "weighted_centroid",
        "confidence": round(max(0.2, min(0.7, 1.0 / (1.0 + len(normalized) * 0.3))), 3),
        "anchor_count": len(normalized),
    }


class FingerprintDB:
    """RSSI-Fingerprinting: Offline-Sammlung + Online-Abfrage (k-NN / wk-NN).

    Distanzfunktion: euklidisch über die gemeinsamen Beacon-Keys (fehlende
    Keys zählen als sehr schwaches Signal, -100 dBm).
    """

    MISSING_RSSI = -100.0
    MAX_FINGERPRINTS = 5000

    def __init__(self) -> None:
        self._fingerprints: List[Tuple[Tuple[float, float], Dict[str, float]]] = []

    def add(self, lat: float, lon: float, rssi_map: Dict[str, int]) -> None:
        self._fingerprints.append(
            ((float(lat), float(lon)), {str(k): float(v) for k, v in rssi_map.items()})
        )
        if len(self._fingerprints) > self.MAX_FINGERPRINTS:
            self._fingerprints = self._fingerprints[-self.MAX_FINGERPRINTS:]

    def locate(
        self,
        rssi_map: Dict[str, int],
        k: int = 3,
        weighted: bool = True,
    ) -> Optional[dict]:
        if not self._fingerprints or k < 1:
            return None
        query = {str(kk): float(vv) for kk, vv in rssi_map.items()}
        ranked = sorted(
            (
                (self._euclidean(fp_rssi, query), pos)
                for pos, fp_rssi in self._fingerprints
            ),
            key=lambda item: item[0],
        )[:k]
        if not ranked:
            return None
        if weighted:
            total_w = 0.0
            lat = 0.0
            lon = 0.0
            for dist, (la, lo) in ranked:
                w = 1.0 / (dist + 0.1)
                lat += w * la
                lon += w * lo
                total_w += w
            return {
                "position": {"x": round(lat / total_w, 6), "y": round(lon / total_w, 6)},
                "method": "fingerprint_wknn",
                "k": k,
                "nearest_distance": round(ranked[0][0], 3),
            }
        la = sum(p[0] for _, p in ranked) / len(ranked)
        lo = sum(p[1] for _, p in ranked) / len(ranked)
        return {
            "position": {"x": round(la, 6), "y": round(lo, 6)},
            "method": "fingerprint_knn",
            "k": k,
            "nearest_distance": round(ranked[0][0], 3),
        }

    @staticmethod
    def _euclidean(a: Dict[str, float], b: Dict[str, float]) -> float:
        keys = set(a) | set(b)
        total = 0.0
        for key in keys:
            diff = a.get(key, FingerprintDB.MISSING_RSSI) - b.get(key, FingerprintDB.MISSING_RSSI)
            total += diff * diff
        return math.sqrt(total)
