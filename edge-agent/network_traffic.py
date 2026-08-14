"""Aktive Netzwerkvisualisierung — Python-Kern (docs/NETWORK_LIVEVIEW.md).

Portiert die v14.1.0-Kernlogik (NetworkTrafficSimulator, Bandbreiten-/
Latenz-Farbcodierung, Aktivitätsindikatoren, Bandbreiten-Heatmap) als pure,
testbare Bausteine — mit Korrekturen (Fehlerkatalog in der Doku):

- Der Spec-`NetworkTrafficSimulator` wurde referenziert, aber **nie
  implementiert** — hier als deterministischer (seeded) Simulator.
- Die Farbcodierung war über drei Klassen verstreut und inkonsistent
  (Partikel: Rot > 100; Linien: Rot > 100, Orange > 50, Gelb > 20 …) —
  hier zentral in [traffic_color]/[severity].
- Latenz/Farbcodierung auf Verbindungen („Nächste Schritte" der Spec)
  ist bereits enthalten: Latenz-Schwellen steuern die Linienfarbe mit.
"""

from __future__ import annotations

import math
import random
import time
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Sequence, Tuple

# ─── Modelle ────────────────────────────────────────────────────────────────


@dataclass
class TrafficFlow:
    """Ein Datenfluss zwischen zwei Geräten/Knoten."""

    source: str
    target: str
    bandwidth_mbps: float
    latency_ms: float = 5.0
    packet_loss_pct: float = 0.0
    timestamp: int = field(default_factory=lambda: int(time.time() * 1000))

    def to_dict(self) -> Dict[str, object]:
        return {
            "source": self.source,
            "target": self.target,
            "bandwidth_mbps": self.bandwidth_mbps,
            "latency_ms": self.latency_ms,
            "packet_loss_pct": self.packet_loss_pct,
            "timestamp": self.timestamp,
        }

    @classmethod
    def from_dict(cls, data: Dict[str, object]) -> "TrafficFlow":
        return cls(
            source=str(data["source"]),
            target=str(data["target"]),
            bandwidth_mbps=float(data.get("bandwidth_mbps", 0.0)),
            latency_ms=float(data.get("latency_ms", 5.0)),
            packet_loss_pct=float(data.get("packet_loss_pct", 0.0)),
            timestamp=int(data.get("timestamp", int(time.time() * 1000))),
        )


# ─── Zentrale Farb-/Schweregrad-Kodierung ───────────────────────────────────

# Schwellen nach Spec (konsolidiert: Partikel- und Linienfarben identisch)
HIGH_BANDWIDTH = 100.0   # > 100 → Rot
MEDIUM_BANDWIDTH = 50.0  # > 50  → Orange
LOW_BANDWIDTH = 20.0     # > 20  → Gelb
MIN_BANDWIDTH = 10.0     # > 10  → Grün; sonst Blau (kein Fluss)

HIGH_LATENCY = 100.0     # ms — Rot
MEDIUM_LATENCY = 40.0    # ms — Orange/Gelb

# Hex-Farben (0xRRGGBB), identisch zu den Spec-Werten
COLOR_HIGH = 0xFF3333
COLOR_MEDIUM = 0xFF8800
COLOR_LOW = 0xFFFF00
COLOR_NORMAL = 0x44FF88
COLOR_IDLE = 0x4488FF


def traffic_color(bandwidth_mbps: float, latency_ms: float = 0.0) -> int:
    """Farbe einer Verbindung/Partikel: Latenz dominiert, dann Bandbreite.

    Kohärent zu [severity]: Rot ↔ critical, Orange/Gelb ↔ warning,
    Grün ↔ normal, Blau ↔ idle. Latenz > 100 ms ist per se ein Alarm
    (der Link trägt dann nachweislich Verkehr).
    """
    if latency_ms > HIGH_LATENCY:
        return COLOR_HIGH
    if bandwidth_mbps > HIGH_BANDWIDTH:
        return COLOR_HIGH
    if bandwidth_mbps > MEDIUM_BANDWIDTH or latency_ms > MEDIUM_LATENCY:
        return COLOR_MEDIUM
    if bandwidth_mbps > LOW_BANDWIDTH:
        return COLOR_LOW
    if bandwidth_mbps > MIN_BANDWIDTH:
        return COLOR_NORMAL
    return COLOR_IDLE


def severity(bandwidth_mbps: float, latency_ms: float = 0.0) -> str:
    """Schweregrad für Status/HUD: critical | warning | normal | idle."""
    if latency_ms > HIGH_LATENCY or bandwidth_mbps > HIGH_BANDWIDTH:
        return "critical"
    if latency_ms > MEDIUM_LATENCY or bandwidth_mbps > MEDIUM_BANDWIDTH:
        return "warning"
    if bandwidth_mbps > MIN_BANDWIDTH:
        return "normal"
    return "idle"


# ─── Partikel-Mapping ───────────────────────────────────────────────────────


def particle_count(bandwidth_mbps: float, max_count: int = 5) -> int:
    """Partikelanzahl je Fluss ∝ Bandbreite (Spec: max(1, bw/10), Cap 5)."""
    if bandwidth_mbps < 1.0:
        return 1
    return min(max_count, max(1, int(bandwidth_mbps / 10.0)))


def particle_speed(bandwidth_mbps: float, base_speed: float = 0.2) -> float:
    """Partikelgeschwindigkeit (Spec: 0,2 + bw/1000)."""
    return base_speed + bandwidth_mbps / 1000.0


def particle_size(bandwidth_mbps: float, base_size: float = 0.03) -> float:
    """Partikelgröße ∝ Bandbreite (Spec: 0,03 + bw/5000)."""
    return base_size + bandwidth_mbps / 5000.0


# ─── Aktivitäts-Aggregation ─────────────────────────────────────────────────


@dataclass
class NodeActivity:
    node_id: str
    total_mbps: float
    flow_count: int
    max_latency_ms: float

    @property
    def active(self) -> bool:
        return self.flow_count > 0

    def to_dict(self) -> Dict[str, object]:
        return {
            "node_id": self.node_id,
            "total_mbps": self.total_mbps,
            "flow_count": self.flow_count,
            "max_latency_ms": self.max_latency_ms,
            "active": self.active,
        }


def aggregate_activity(flows: Sequence[TrafficFlow]) -> Dict[str, NodeActivity]:
    """Je Knoten: Gesamtdurchsatz, Flusszahl, maximale Latenz.

    (Spec: `updateActivityIndicators` — Gerät aktiv, wenn es in einem Fluss
    vorkommt; hier als testbare Aggregation mit Durchsatz-Ranking.)
    """
    totals: Dict[str, float] = {}
    counts: Dict[str, int] = {}
    latencies: Dict[str, float] = {}
    for flow in flows:
        for node in (flow.source, flow.target):
            totals[node] = totals.get(node, 0.0) + flow.bandwidth_mbps
            counts[node] = counts.get(node, 0) + 1
            latencies[node] = max(latencies.get(node, 0.0), flow.latency_ms)
    return {
        node: NodeActivity(node, totals[node], counts[node], latencies[node])
        for node in totals
    }


def top_nodes(activity: Dict[str, NodeActivity], top_n: int = 5) -> List[NodeActivity]:
    """Durchsatz-Ranking (für die Bandbreiten-Heatmap des LiveView)."""
    return sorted(activity.values(), key=lambda a: a.total_mbps, reverse=True)[:top_n]


def heatmap_columns(
    activity: Dict[str, NodeActivity],
    max_height: float = 1.0,
) -> Dict[str, float]:
    """Bandbreiten-Heatmap: relative Höhe je Knoten (0..max_height).

    Normalisierung auf den stärksten Knoten; die Visualisierung extrudiert
    daraus 3D-Säulen unter den Geräte-Markern.
    """
    if not activity:
        return {}
    peak = max(a.total_mbps for a in activity.values())
    if peak <= 0:
        return {node: 0.0 for node in activity}
    return {node: (a.total_mbps / peak) * max_height for node, a in activity.items()}


# ─── Deterministischer Traffic-Simulator ─────────────────────────────────────


class NetworkTrafficSimulator:
    """Seeded Echtzeit-Flusssimulation zwischen Knotenpaaren.

    Erzeugt pro Schritt Bandbreite/Latenz/Paketverlust je Kante — für
    Demo-Feeds, Tests und Last-Szenarien (der Spec referenzierte diesen
    Simulator, implementierte ihn aber nie).
    """

    def __init__(
        self,
        seed: int = 42,
        base_bandwidth_mbps: float = 40.0,
        burst_probability: float = 0.15,
        burst_factor: float = 3.0,
    ) -> None:
        self._rng = random.Random(seed)
        self.base_bandwidth = base_bandwidth_mbps
        self.burst_probability = burst_probability
        self.burst_factor = burst_factor

    def simulate(
        self,
        edges: Sequence[Tuple[str, str]],
        timestamp: Optional[int] = None,
    ) -> List[TrafficFlow]:
        """Simuliert einen Fluss je Kante (mit Bursts und Latenz-Kopplung)."""
        flows: List[TrafficFlow] = []
        for source, target in edges:
            burst = self._rng.random() < self.burst_probability
            bandwidth = self.base_bandwidth * (self.burst_factor if burst else 1.0)
            bandwidth += self._rng.uniform(-5.0, 5.0)
            bandwidth = max(0.5, bandwidth)
            # Latenz wächst mit der Auslastung (sättigende Kopplung)
            latency = 2.0 + 45.0 * (bandwidth / (self.base_bandwidth * self.burst_factor))
            latency += self._rng.uniform(-1.0, 1.0)
            loss = max(0.0, (latency - 40.0) * 0.02)
            flows.append(
                TrafficFlow(
                    source=source,
                    target=target,
                    bandwidth_mbps=round(bandwidth, 2),
                    latency_ms=round(max(0.5, latency), 2),
                    packet_loss_pct=round(min(99.0, loss), 2),
                    timestamp=timestamp if timestamp is not None else int(time.time() * 1000),
                )
            )
        return flows

    def simulate_steps(
        self,
        edges: Sequence[Tuple[str, str]],
        steps: int,
        step_ms: int = 1000,
    ) -> List[List[TrafficFlow]]:
        """Mehrere Simulationsschritte (Zeitreihe für Diagramme/Tests)."""
        base_time = int(time.time() * 1000)
        return [
            self.simulate(edges, timestamp=base_time + i * step_ms)
            for i in range(steps)
        ]
