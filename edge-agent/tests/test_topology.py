"""Tests für die 3D-Network-Topology-Engine (docs/NETWORK3D.md)."""

from network_topology import TopologyEdge, TopologyGraph, TopologyHistory, TopologyNode


def _mesh():
    """Mesh: A—B—D und A—C—D (D über zwei Pfade erreichbar), E hängt nur an B."""
    g = TopologyGraph()
    for nid, x in (("A", 0.0), ("B", 1.0), ("C", 1.0), ("D", 2.0), ("E", 1.0)):
        g.upsert_node(TopologyNode(id=nid, type="router", x=x, y=x, z=0.0))
    g.upsert_edge(TopologyEdge(id="AB", source="A", target="B", latency_ms=1.0))
    g.upsert_edge(TopologyEdge(id="AC", source="A", target="C", latency_ms=1.0))
    g.upsert_edge(TopologyEdge(id="BD", source="B", target="D", latency_ms=1.0))
    g.upsert_edge(TopologyEdge(id="CD", source="C", target="D", latency_ms=1.0))
    g.upsert_edge(TopologyEdge(id="BE", source="B", target="E", latency_ms=1.0))
    return g


def test_shortest_path_prefers_low_latency():
    g = _mesh()
    assert g.shortest_path("A", "D") == ["A", "B", "D"]
    # C-D-Umweg: C—D teurer machen
    g.edges["CD"].latency_ms = 10.0
    assert g.shortest_path("A", "D") == ["A", "B", "D"]


def test_failover_reroutes_flow():
    g = _mesh()
    result = g.simulate_failover(
        "B",
        [{"id": "f1", "source": "A", "target": "D"}],
    )
    flow = result["results"][0]
    assert flow["affected"] is True
    assert flow["old_path"] == ["A", "B", "D"]
    assert flow["new_path"] == ["A", "C", "D"]
    assert flow["rerouted"] is True and flow["reachable"] is True
    assert result["rerouted_flows"] == 1


def test_failover_marks_unreachable_flows():
    g = _mesh()
    result = g.simulate_failover(
        "B",
        [{"id": "f2", "source": "A", "target": "E"}],
    )
    flow = result["results"][0]
    assert flow["affected"] is True
    assert flow["reachable"] is False
    assert result["unreachable_flows"] == 1


def test_failover_does_not_touch_unaffected_flows():
    g = _mesh()
    result = g.simulate_failover(
        "E",
        [{"id": "f3", "source": "A", "target": "D"}],
    )
    flow = result["results"][0]
    assert flow["affected"] is False
    assert flow["rerouted"] is False
    assert flow["old_path"] == ["A", "B", "D"]


def test_remove_node_cascades_edges():
    g = _mesh()
    removed = g.remove_node("B")
    assert set(removed) == {"AB", "BD", "BE"}
    assert "B" not in g.nodes
    assert "E" not in {e.source for e in g.edges.values()}


def test_history_snapshot_and_replay():
    g = _mesh()
    history = TopologyHistory(capacity=3)
    history.snapshot(g)
    g.upsert_node(TopologyNode(id="F", type="sensor"))
    history.snapshot(g)
    g.upsert_node(TopologyNode(id="G", type="sensor"))
    history.snapshot(g)

    assert len(history) == 3
    first = history.replay()
    assert first is not None and len(first["graph"]["nodes"]) == 5  # A..E
    latest = history.latest()
    assert latest is not None and len(latest["graph"]["nodes"]) == 7
    low, high = history.range()
    assert (low, high) == (0, 2)


def test_history_capacity_evicts_oldest():
    g = _mesh()
    history = TopologyHistory(capacity=2)
    history.snapshot(g)
    history.snapshot(g)
    history.snapshot(g)
    assert len(history) == 2
    assert history.range() == (1, 2)


def test_dict_roundtrip_preserves_topology():
    g = _mesh()
    restored = TopologyGraph.from_dict(g.to_dict())
    assert set(restored.nodes) == set(g.nodes)
    assert set(restored.edges) == set(g.edges)
    assert restored.shortest_path("A", "D") == g.shortest_path("A", "D")
