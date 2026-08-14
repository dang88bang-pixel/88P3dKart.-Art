"""Triangulations-Mathematik — Python-Port der Kotlin-Module
(`com.example.agent.triangulation`, docs/TRIANGULATION.md §5).

Enthält:
- `solve_trilateration`  — Trilateration (lineare Startlösung +
  Levenberg-Marquardt, gewichtet nach Messunsicherheiten),
- `rssi_to_distance`      — Log-Distance-Path-Loss-Modell,
- `calibrate_path_loss`   — lineare Regression (Distanz, RSSI) → Modellparameter.

Datenmodell und Numerik sind identisch zur Kotlin-Implementierung, sodass
CT45P-App und Edge-Agent austauschbar rechnen.
"""

from __future__ import annotations

from typing import Dict, List, Optional

import numpy as np

DEFAULT_UNCERTAINTY_M = 1.0


def solve_trilateration(
    anchors: List[dict],
    distances: Dict[str, float],
    uncertainties: Optional[Dict[str, float]] = None,
    use_z: bool = False,
    robust_iterations: int = 2,
) -> Optional[dict]:
    """Löst die Trilateration.

    Args:
        anchors: Anker mit bekannter Position, je `{"id": str, "x": float,
            "y": float, "z": float}`.
        distances: Distanzen in Metern, Schlüssel = Anker-ID.
        uncertainties: Messunsicherheiten σ in Metern (Standard 1,0 m);
            inverse Varianz = Gewichtung.
        use_z: 3D-Lösung (≥ 4 Anker) oder 2D-Lösung (≥ 3 Anker).
        robust_iterations: Reject-and-Resolve-Ausreißerbehandlung (Least
            Trimmed Squares, LTS-1): jede Leave-one-out-Lösung wird bewertet
            (Summe der m−1 kleinsten quadratischen Residuen); liegt die beste
            mindestens 40 % unter der Volllösung, wird der betreffende Anker
            als Ausreißer verworfen und neu gelöst (max. N Durchgänge,
            Mindest-Ankerzahl bleibt gewahrt).

    Returns:
        dict mit Position, Residuum-RMS, Positions-Sigma, Konfidenz — oder
        None bei zu wenigen gültigen Messungen.
    """
    uncertainties = uncertainties or {}
    entries: list = []
    for a in anchors:
        d = distances.get(a["id"])
        if d is None or not np.isfinite(d) or d < 0:
            continue
        u = uncertainties.get(a["id"], DEFAULT_UNCERTAINTY_M)
        if u is None or not np.isfinite(u) or u <= 0:
            u = DEFAULT_UNCERTAINTY_M
        entries.append((a, float(d), float(u)))

    min_anchors = 4 if use_z else 3
    if len(entries) < min_anchors:
        return None
    dim = 3 if use_z else 2

    # Reject-and-Resolve (LTS-1): Leave-one-out-Kandidaten bewerten; der
    # Anker, dessen Entfernung die Trimmed-Kosten deutlich senkt, ist ein
    # Ausreißer (robust gegen Masking, im Gegensatz zu studentisierten
    # Residuen bei kleinen Ankerzahlen).
    rejected = 0
    result = _lm_solve(entries, dim)

    def trimmed_cost(solved: dict, es: list) -> float:
        p = np.array([solved["x"], solved["y"], solved["z"]], dtype=float)[:dim]
        squared = sorted(
            (float(np.linalg.norm(np.array([a["x"], a["y"], a["z"]], dtype=float)[:dim] - p)) - d) ** 2
            for (a, d, _) in es
        )
        return float(np.sum(squared[: max(0, len(es) - 1)]))

    for _ in range(max(0, robust_iterations)):
        if result is None or len(entries) <= min_anchors:
            break
        cost_full = trimmed_cost(result, entries)
        candidates = []
        for i in range(len(entries)):
            sub = entries[:i] + entries[i + 1:]
            cand = _lm_solve(sub, dim)
            if cand is not None:
                candidates.append((i, cand))
        if not candidates:
            break
        best_i, best = min(candidates, key=lambda c: trimmed_cost(c[1], entries))
        if trimmed_cost(best, entries) < 0.6 * cost_full:
            entries = entries[:best_i] + entries[best_i + 1:]
            rejected += 1
            result = best
        else:
            break

    if result is None:
        return None
    final = _finalize(entries, dim, result)
    if rejected:
        final["rejected_anchors"] = rejected
    return final


def _lm_solve(entries, dim: int) -> Optional[dict]:
    """Einzelner Levenberg-Marquardt-Durchlauf auf den gegebenen Einträgen."""
    w = np.array([1.0 / (e[2] ** 2) for e in entries])

    # 1) Startlösung: lineares LSQ (Referenz-Anker subtrahieren) oder Schwerpunkt
    p = _linear_init(entries, dim)
    if p is None:
        p = np.mean(
            [[e[0]["x"], e[0]["y"], e[0]["z"]][:dim] for e in entries], axis=0
        )

    # 2) Levenberg-Marquardt
    lam = 1e-3
    cost = np.inf
    converged = False
    iterations = 0
    for it in range(40):
        iterations = it
        resid = np.zeros(len(entries))
        jac = np.zeros((len(entries), dim))
        new_cost = 0.0
        for i, (a, d, _) in enumerate(entries):
            anchor = np.array([a["x"], a["y"], a["z"]], dtype=float)[:dim]
            delta = p - anchor
            dist = float(np.linalg.norm(delta))
            safe = dist if dist >= 1e-6 else 1e-6
            r = dist - d
            resid[i] = r
            new_cost += w[i] * r * r
            jac[i] = delta / safe

        if new_cost >= cost and np.isfinite(cost):
            lam *= 10.0
            if lam > 1e9:
                break
        else:
            lam = max(lam * 0.3, 1e-9)
            cost = new_cost

        jtwj = jac.T @ (w[:, None] * jac) + lam * np.eye(dim)
        jtwr = jac.T @ (w * resid)
        try:
            delta_p = np.linalg.solve(jtwj, jtwr)
        except np.linalg.LinAlgError:
            break
        p = p - delta_p
        if float(np.sum(delta_p**2)) < 1e-12:
            converged = True
            break

    return {
        "x": float(p[0]),
        "y": float(p[1]),
        "z": float(p[2]) if dim == 3 else 0.0,
        "converged": converged,
        "iterations": iterations,
        "anchor_count": len(entries),
    }


def _finalize(entries, dim: int, solved: dict) -> dict:
    """Residuum-RMS, Positions-Sigma und Konfidenz aus einer Lösung berechnen."""
    p = np.array([solved["x"], solved["y"], solved["z"]], dtype=float)[:dim]
    w = np.array([1.0 / (e[2] ** 2) for e in entries])

    rms_sum = 0.0
    final_jac = np.zeros((len(entries), dim))
    for i, (a, d, _) in enumerate(entries):
        anchor = np.array([a["x"], a["y"], a["z"]], dtype=float)[:dim]
        delta = p - anchor
        dist = float(np.linalg.norm(delta))
        r = dist - d
        rms_sum += r * r
        safe = dist if dist >= 1e-6 else 1e-6
        final_jac[i] = delta / safe
    rms = float(np.sqrt(rms_sum / len(entries)))

    try:
        sigma = float(
            np.sqrt(np.trace(np.linalg.inv(final_jac.T @ (w[:, None] * final_jac))))
        )
    except np.linalg.LinAlgError:
        sigma = 1e6

    confidence = float(np.clip(1.0 - rms / 3.0, 0.0, 1.0))
    if not solved["converged"]:
        confidence *= 0.6
    if sigma > 50.0:
        confidence = min(confidence, 0.3)

    return {
        "x": solved["x"],
        "y": solved["y"],
        "z": solved["z"],
        "residual_rms_m": rms,
        "position_sigma_m": sigma,
        "confidence": confidence,
        "converged": solved["converged"],
        "iterations": solved["iterations"],
        "anchor_count": len(entries),
    }


def _linear_init(entries, dim: int) -> Optional[np.ndarray]:
    """Lineare Startlösung: 2·(aᵢ−a₀)·p = d₀² − dᵢ² + ‖aᵢ‖² − ‖a₀‖²."""
    a0 = entries[0][0]
    d0 = entries[0][1]
    rows = len(entries) - 1
    keys = ("x", "y", "z")
    a_mat = np.zeros((rows, dim))
    b_vec = np.zeros(rows)
    for i in range(1, len(entries)):
        ai = entries[i][0]
        di = entries[i][1]
        for k in range(dim):
            a_mat[i - 1, k] = 2.0 * (ai[keys[k]] - a0[keys[k]])
        norm0 = sum(a0[keys[k]] ** 2 for k in range(dim))
        norm_i = sum(ai[keys[k]] ** 2 for k in range(dim))
        b_vec[i - 1] = d0 * d0 - di * di + norm_i - norm0
    ata = a_mat.T @ a_mat
    atb = a_mat.T @ b_vec
    if abs(np.linalg.det(ata)) < 1e-12:
        return None
    return np.linalg.solve(ata, atb)


def rssi_to_distance(
    rssi_dbm: float,
    reference_rssi_dbm: float = -59.0,
    path_loss_exponent: float = 2.8,
) -> Optional[float]:
    """RSSI → Distanz in Metern (Log-Distance-Path-Loss-Modell)."""
    if rssi_dbm is None or not np.isfinite(rssi_dbm) or rssi_dbm > -1.0:
        return None
    return 10.0 ** ((reference_rssi_dbm - rssi_dbm) / (10.0 * path_loss_exponent))


def calibrate_path_loss(samples: List[tuple]) -> Optional[dict]:
    """Kalibriert das Path-Loss-Modell aus (Distanz [m], RSSI [dBm])-Paaren.

    Regression: RSSI = RSSI₀ − 10n·log10(d).
    """
    if len(samples) < 3:
        return None
    x = np.array([10.0 * np.log10(max(d, 0.01)) for d, _ in samples])
    y = np.array([r for _, r in samples])
    # x-Achse = 10·log10(d) → Steigung = −n (dB je Dekade)
    slope, intercept = np.polyfit(x, y, 1)
    r = np.corrcoef(x, y)[0, 1] ** 2
    return {
        "reference_rssi_dbm": float(intercept),
        "path_loss_exponent": float(max(0.1, -slope)),
        "r_squared": float(np.clip(r, 0.0, 1.0)),
    }


class RssiKalmanFilter:
    """1D-Kalman-Filter je Sender-MAC zur RSSI-Glättung (vgl. avibn/
    indoor-positioning-trilateration, MDPI Sensors 2017).

    Zustandsmodell: RSSI konstant (A = 1, H = 1) mit Prozessrauschen q und
    Messrauschen r — unterdrückt kurzzeitige RSSI-Sprünge (Multipath).
    """

    def __init__(self, q: float = 4.0, r: float = 16.0):
        self.q = q
        self.r = r
        self._state: dict = {}  # key → (estimate, covariance)

    def filter(self, key: str, rssi: float) -> float:
        est, p = self._state.get(key, (rssi, 1.0))
        # Predict
        p_pred = p + self.q
        # Update
        k = p_pred / (p_pred + self.r)
        est = est + k * (rssi - est)
        p = (1.0 - k) * p_pred
        self._state[key] = (est, p)
        return est

    def value(self, key: str) -> Optional[float]:
        state = self._state.get(key)
        return state[0] if state else None

    def clear(self, key: str) -> None:
        self._state.pop(key, None)


def median_filter_rssi(values: List[float], window: int = 5) -> float:
    """Gleitender Median über die letzten [window] RSSI-Werte (Spike-
    Unterdrückung; vgl. MDPI Sensors 2025, 25(9):2834 — Median + MAF)."""
    if not values:
        return 0.0
    w = values[-window:]
    return float(np.median(w))
