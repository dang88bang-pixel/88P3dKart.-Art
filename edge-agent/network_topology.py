"""3D Network Topology Engine — Python-Kern (docs/NETWORK3D.md).

Portierung der sinnvollen Kernlogik aus der v7.0.0-Spezifikation:
- Topologie-Graph (Nodes + Edges, Typen/Status/Metriken),
- kürzeste Pfade (Dijkstra, heapq — dependency-frei),
- **What-If-Simulation** (Failover: Node-Ausfall → Betroffenheit →
  Rerouting → Nicht-Erreichbarkeit),
- **Time Machine** (Snapshot-Historie mit begrenztem Replay-Fenster).

Die Spec-eigene OSS-Recherche stuft Time Machine und Simulations-Modus als
„Neuland" ein (kein Vergleichsprojekt deckt sie ab) — hier implementiert.
Das Force-Directed-Layout und die Partikelvisualisierung übernimmt der
Web-Visualizer (3d-force-graph-Muster, native Three.js-Umsetzung).
"""

from __future__ import annotations

import heapq
import time
from collections import deque
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Tuple

# ─── Datenmodell ─────────────────────────────────────────────────────────


@dataclass
class TopologyNode:
    id: str
    type: str = "host"  # router|switch|firewall|vm|container|cloud|server|sensor
    name: str = ""
    status: str = "ok"  # ok|warning|critical|down
    x: float = 0.0
    y: float = 0.0
    z: float = 0.0
    metrics: Dict[str, float] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "type": self.type,
            "name": self.name,
            "status": self.status,
            "x": self.x,
            "y": self.y,
            "z": self.z,
            "metrics": dict(self.metrics),
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "TopologyNode":
        return cls(
            id=str(data["id"]),
            type=str(data.get("type", "host")),
            name=str(data.get("name", "")),
            status=str(data.get("status", "ok")),
            x=float(data.get("x", 0.0)),
            y=float(data.get("y", 0.0)),
            z=float(data.get("z", 0.0)),
            metrics={k: float(v) for k, v in (data.get("metrics") or {}).items()},
        )


@dataclass
class TopologyEdge:
    id: str
    source: str
    target: str
    bandwidth_mbps: float = 1000.0
    latency_ms: float = 1.0
    utilization: float = 0.0  # 0..1

    def to_dict(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "source": self.source,
            "target": self.target,
            "bandwidth_mbps": self.bandwidth_mbps,
            "latency_ms": self.latency_ms,
            "utilization": self.utilization,
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "TopologyEdge":
        return cls(
            id=str(data["id"]),
            source=str(data["source"]),
            target=str(data["target"]),
            bandwidth_mbps=float(data.get("bandwidth_mbps", 1000.0)),
            latency_ms=float(data.get("latency_ms", 1.0)),
            utilization=float(data.get("utilization", 0.0)),
        )


# ─── Graph-Engine ────────────────────────────────────────────────────────


class TopologyGraph:
    """Nodes + Edges mit Upsert, Kaskaden-Entfernung und Dijkstra."""

    def __init__(self) -> None:
        self.nodes: Dict[str, TopologyNode] = {}
        self.edges: Dict[str, TopologyEdge] = {}
        self.timestamp: float = time.time()

    # ── Mutationen ──────────────────────────────────────────────

    def upsert_node(self, node: TopologyNode) -> None:
        self.nodes[node.id] = node
        self.timestamp = time.time()

    def upsert_edge(self, edge: TopologyEdge) -> None:
        if edge.source not in self.nodes or edge.target not in self.nodes:
            raise KeyError(f"Edge {edge.id} referenziert unbekannte Nodes")
        self.edges[edge.id] = edge
        self.timestamp = time.time()

    def remove_node(self, node_id: str) -> List[str]:
        """Entfernt Node + alle inzidenten Kanten (Kaskade)."""
        removed_edges: List[str] = []
        self.nodes.pop(node_id, None)
        for edge_id, edge in list(self.edges.items()):
            if edge.source == node_id or edge.target == node_id:
                self.edges.pop(edge_id, None)
                removed_edges.append(edge_id)
        self.timestamp = time.time()
        return removed_edges

    # ── Pfade ────────────────────────────────────────────────────

    def dijkstra(self, src: str) -> Tuple[Dict[str, float], Dict[str, Optional[str]]]:
        """Dijkstra (Latenz als Kantengewicht) → (Distanzen, Vorgänger)."""
        if src not in self.nodes:
            raise KeyError(f"Node {src} nicht im Graph")
        dist: Dict[str, float] = {n: float("inf") for n in self.nodes}
        prev: Dict[str, Optional[str]] = {n: None for n in self.nodes}
        dist[src] = 0.0
        heap: List[Tuple[float, str]] = [(0.0, src)]
        while heap:
            d, node = heapq.heappop(heap)
            if d > dist[node]:
                continue
            for edge in self.edges.values():
                if edge.source == node or edge.target == node:
                    nbr = edge.target if edge.source == node else edge.source
                    nd = d + edge.latency_ms
                    if nd < dist[nbr]:
                        dist[nbr] = nd
                        prev[nbr] = node
                        heapq.heappush(heap, (nd, nbr))
        return dist, prev

    def shortest_path(self, src: str, dst: str) -> Optional[List[str]]:
        """Kürzester Pfad als Node-Liste (None wenn unerreichbar)."""
        if src not in self.nodes or dst not in self.nodes:
            return None
        _, prev = self.dijkstra(src)
        if prev.get(dst) is None and dst != src:
            return None
        path: List[str] = []
        cur: Optional[str] = dst
        while cur is not None:
            path.append(cur)
            if cur == src:
                break
            cur = prev.get(cur)
        path.reverse()
        return path

    # ── What-If: Failover ───────────────────────────────────────

    def simulate_failover(
        self,
        failing_node: str,
        flows: List[Dict[str, Any]],
    ) -> Dict[str, Any]:
        """Failover-Simulation: Node-Ausfall → Betroffenheit → Rerouting.

        flows: [{id, source, target}] — Bandbreiten optional (Bericht).
        Rückgabe: betroffene Flows mit altem/neuem Pfad und Erreichbarkeit.
        """
        results: List[Dict[str, Any]] = []
        for flow in flows:
            flow_id = str(flow.get("id", flow.get("source", "?")))
            src = str(flow["source"])
            dst = str(flow["target"])
            old_path = self.shortest_path(src, dst) or []
            affected = failing_node in old_path

            if not affected:
                results.append(
                    {
                        "id": flow_id,
                        "source": src,
                        "target": dst,
                        "affected": False,
                        "old_path": old_path,
                        "new_path": old_path,
                        "rerouted": False,
                        "reachable": True,
                    }
                )
                continue

            # Degradierter Graph: Node temporär entfernen (Kaskade), danach exakt restaurieren
            node_backup = self.nodes.get(failing_node)
            edge_backups = [
                (eid, self.edges[eid])
                for eid, e in self.edges.items()
                if e.source == failing_node or e.target == failing_node
            ]
            self.nodes.pop(failing_node, None)
            for eid, _ in edge_backups:
                self.edges.pop(eid, None)

            new_path = self.shortest_path(src, dst)

            # Restaurieren
            if node_backup is not None:
                self.nodes[failing_node] = node_backup
            for eid, edge in edge_backups:
                self.edges[eid] = edge

            reachable = new_path is not None and len(new_path) > 0
            results.append(
                {
                    "id": flow_id,
                    "source": src,
                    "target": dst,
                    "affected": True,
                    "old_path": old_path,
                    "new_path": new_path or [],
                    "rerouted": reachable and new_path != old_path,
                    "reachable": reachable,
                }
            )

        affected = [r for r in results if r["affected"]]
        rerouted = [r for r in affected if r["reachable"]]
        unreachable = [r for r in affected if not r["reachable"]]
        return {
            "failing_node": failing_node,
            "flow_count": len(results),
            "affected_flows": len(affected),
            "rerouted_flows": len(rerouted),
            "unreachable_flows": len(unreachable),
            "results": results,
        }

    # ── Serialisierung ───────────────────────────────────────────

    def to_dict(self) -> Dict[str, Any]:
        return {
            "timestamp": self.timestamp,
            "nodes": [n.to_dict() for n in self.nodes.values()],
            "edges": [e.to_dict() for e in self.edges.values()],
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "TopologyGraph":
        graph = cls()
        for node_data in data.get("nodes", []):
            graph.upsert_node(TopologyNode.from_dict(node_data))
        for edge_data in data.get("edges", []):
            edge = TopologyEdge.from_dict(edge_data)
            if edge.source in graph.nodes and edge.target in graph.nodes:
                graph.edges[edge.id] = edge
        graph.timestamp = float(data.get("timestamp", time.time()))
        return graph


# ─── Time Machine ────────────────────────────────────────────────────────


class TopologyHistory:
    """Snapshot-Historie (Replay-Fenster) für die „Time Machine"."""

    def __init__(self, capacity: int = 600) -> None:
        self._snapshots: deque = deque(maxlen=capacity)
        self.capacity = capacity

    def snapshot(self, graph: TopologyGraph) -> int:
        """Speichert den aktuellen Zustand; Rückgabe: Index."""
        self._snapshots.append({"index": self._next_index(), "graph": graph.to_dict()})
        return self._last_index()

    def latest(self) -> Optional[Dict[str, Any]]:
        return self._snapshots[-1] if self._snapshots else None

    def replay(self, index: Optional[int] = None) -> Optional[Dict[str, Any]]:
        """Liefert einen Snapshot (Standard: ältester)."""
        if not self._snapshots:
            return None
        if index is None:
            return self._snapshots[0]
        for snap in self._snapshots:
            if snap["index"] == index:
                return snap
        return None

    def range(self) -> Tuple[Optional[int], Optional[int]]:
        if not self._snapshots:
            return None, None
        return self._snapshots[0]["index"], self._snapshots[-1]["index"]

    def __len__(self) -> int:
        return len(self._snapshots)

    def _next_index(self) -> int:
        return self._snapshots[-1]["index"] + 1 if self._snapshots else 0

    def _last_index(self) -> int:
        return self._snapshots[-1]["index"]
