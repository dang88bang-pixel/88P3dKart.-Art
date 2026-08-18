"""Mesh-Netzwerk-Synchronisation (docs/SIGNAL_POSITIONING.md).

Random-Broadcast-Consensus für Zeitsynchronisation:
    t_i ← t_i + α · (t_j − t_i)   (Nachbar j zufällig, vollvermascht)

Der Algorithmus konvergiert gegen den Mittelwert aller Knotenzeiten.
Für deterministische Tests kann ein fester Zufallsgenerator injiziert werden.
"""
from __future__ import annotations

import random
from typing import List, Optional, Sequence


def consensus_sync(
    times: Sequence[float],
    rounds: int,
    alpha: float = 0.3,
    rng: Optional[random.Random] = None,
) -> List[float]:
    """Führt `rounds` Gossip-Runden auf den Knotenzeiten aus.

    Jede Runde: jeder Knoten mittelt seine Zeit mit der eines zufälligen
    Nachbarn (vollvermaschtes Netz). Liefert die finale Zeitliste.
    """
    if not times:
        return []
    n = len(times)
    values = [float(t) for t in times]
    rnd = rng or random.Random(42)
    if n == 1:
        return values
    for _ in range(int(rounds)):
        neighbors = [rnd.randrange(n) for _ in range(n)]
        # Nachbar ≠ Selbst erzwingen (deterministisch verschoben)
        for i in range(n):
            if neighbors[i] == i:
                neighbors[i] = (i + 1) % n
        # Symmetrisches Pairwise-Averaging: Summe (und damit der Mittelwert)
        # bleibt in jeder Runde exakt erhalten.
        for i in range(n):
            j = neighbors[i]
            if i < j:
                a, b = values[i], values[j]
                step = float(alpha) * (b - a)
                values[i], values[j] = a + step, b - step
    return values


def max_time_disagreement(times: Sequence[float]) -> float:
    """Maximale Abweichung vom Mittelwert (Konvergenzmaß)."""
    if not times:
        return 0.0
    mean = sum(times) / len(times)
    return max(abs(t - mean) for t in times)


def sync_to_tolerance(
    times: Sequence[float],
    tolerance: float,
    alpha: float = 0.3,
    max_rounds: int = 500,
    rng: Optional[random.Random] = None,
) -> dict:
    """Synchronisiert bis zur Toleranz (oder max_rounds) und liefert Bericht."""
    values = list(times)
    rnd = rng or random.Random(42)
    disagreement_before = max_time_disagreement(values)
    rounds = 0
    for _ in range(max_rounds):
        if max_time_disagreement(values) <= tolerance:
            break
        values = consensus_sync(values, 1, alpha=alpha, rng=rnd)
        rounds += 1
    return {
        "rounds": rounds,
        "converged": max_time_disagreement(values) <= tolerance,
        "disagreement_before": round(disagreement_before, 6),
        "disagreement_after": round(max_time_disagreement(values), 6),
        "mean": round(sum(values) / len(values), 6),
        "times": [round(t, 6) for t in values],
    }
