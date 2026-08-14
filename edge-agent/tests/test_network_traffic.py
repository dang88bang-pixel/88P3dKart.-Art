"""Tests für die aktive Netzwerkvisualisierung (docs/NETWORK_LIVEVIEW.md)."""

from network_traffic import (
    COLOR_HIGH,
    COLOR_IDLE,
    COLOR_MEDIUM,
    COLOR_NORMAL,
    NetworkTrafficSimulator,
    TrafficFlow,
    aggregate_activity,
    heatmap_columns,
    particle_count,
    particle_size,
    particle_speed,
    severity,
    top_nodes,
    traffic_color,
)


def test_traffic_color_thresholds():
    assert traffic_color(200.0) == COLOR_HIGH
    assert traffic_color(80.0) == COLOR_MEDIUM
    assert traffic_color(30.0) == 0xFFFF00
    assert traffic_color(15.0) == COLOR_NORMAL
    assert traffic_color(5.0) == COLOR_IDLE


def test_latency_overrides_color():
    # 80 Mbit/s wäre Orange — 150 ms Latenz macht es Rot (Latenz-Alarm)
    assert traffic_color(80.0, latency_ms=150.0) == COLOR_HIGH
    # Latenz dominiert unabhängig von der Bandbreite (Link trägt nachweislich Verkehr)
    assert traffic_color(10.0, latency_ms=150.0) == COLOR_HIGH
    assert traffic_color(5.0, latency_ms=150.0) == COLOR_HIGH
    # Moderate Latenz → Orange (konsistent zu severity "warning")
    assert traffic_color(15.0, latency_ms=50.0) == COLOR_MEDIUM
    # Ohne Verkehr/Latenz bleibt die MIN-Grenze idle (kein Fehlalarm)
    assert traffic_color(10.0, latency_ms=0.0) == COLOR_IDLE


def test_severity_mapping():
    assert severity(200.0) == "critical"
    assert severity(10.0, latency_ms=150.0) == "critical"
    assert severity(60.0) == "warning"
    assert severity(15.0) == "normal"
    assert severity(5.0) == "idle"


def test_particle_mapping():
    import pytest

    assert particle_count(5.0) == 1
    assert particle_count(55.0) == 5
    assert particle_count(500.0) == 5  # Cap
    assert particle_speed(100.0) == pytest.approx(0.3)
    assert particle_size(100.0) == pytest.approx(0.05)


def test_aggregate_activity():
    flows = [
        TrafficFlow("a", "b", bandwidth_mbps=10.0, latency_ms=5.0),
        TrafficFlow("b", "c", bandwidth_mbps=20.0, latency_ms=30.0),
        TrafficFlow("a", "c", bandwidth_mbps=40.0, latency_ms=80.0),
    ]
    activity = aggregate_activity(flows)
    assert activity["a"].total_mbps == 50.0
    assert activity["a"].flow_count == 2
    assert activity["a"].max_latency_ms == 80.0
    assert activity["b"].total_mbps == 30.0
    assert activity["c"].total_mbps == 60.0
    assert all(a.active for a in activity.values())


def test_top_nodes_rank_by_throughput():
    flows = [
        TrafficFlow("a", "b", bandwidth_mbps=10.0),
        TrafficFlow("c", "d", bandwidth_mbps=100.0),
        TrafficFlow("e", "f", bandwidth_mbps=50.0),
    ]
    activity = aggregate_activity(flows)
    top = top_nodes(activity, top_n=2)
    assert [node.node_id for node in top] == ["c", "d"] or [node.node_id for node in top] == ["d", "c"]


def test_heatmap_normalizes_to_peak():
    flows = [
        TrafficFlow("a", "b", bandwidth_mbps=100.0),
        TrafficFlow("a", "c", bandwidth_mbps=50.0),
    ]
    activity = aggregate_activity(flows)
    columns = heatmap_columns(activity, max_height=1.0)
    # a: 150 (Peak) → 1.0; b/c: 100 bzw. 50
    assert columns["a"] == 1.0
    assert abs(columns["b"] - 100.0 / 150.0) < 1e-9
    assert abs(columns["c"] - 50.0 / 150.0) < 1e-9
    assert heatmap_columns({}) == {}


def test_simulator_is_deterministic_with_seed():
    edges = [("a", "b"), ("b", "c"), ("c", "d")]
    first = NetworkTrafficSimulator(seed=42).simulate(edges)
    second = NetworkTrafficSimulator(seed=42).simulate(edges)
    assert [f.bandwidth_mbps for f in first] == [f.bandwidth_mbps for f in second]
    # Werte in plausiblen Bereichen
    for flow in first:
        assert flow.bandwidth_mbps > 0
        assert flow.latency_ms > 0
        assert 0.0 <= flow.packet_loss_pct <= 99.0


def test_simulator_produces_bursts():
    sim = NetworkTrafficSimulator(seed=1, burst_probability=1.0, burst_factor=3.0)
    flows = sim.simulate([("a", "b") for _ in range(20)])
    # Burst-Wahrscheinlichkeit 1 → Bandbreite ≈ 3× Basis + Rauschen
    avg = sum(f.bandwidth_mbps for f in flows) / len(flows)
    assert avg > 2.0 * sim.base_bandwidth


def test_simulator_steps_timestamps_increase():
    sim = NetworkTrafficSimulator(seed=7)
    steps = sim.simulate_steps([("a", "b")], steps=3, step_ms=1000)
    assert len(steps) == 3
    assert steps[1][0].timestamp - steps[0][0].timestamp == 1000


def test_flow_roundtrip():
    flow = TrafficFlow("a", "b", 12.5, latency_ms=8.0, packet_loss_pct=0.5)
    restored = TrafficFlow.from_dict(flow.to_dict())
    assert restored == flow
