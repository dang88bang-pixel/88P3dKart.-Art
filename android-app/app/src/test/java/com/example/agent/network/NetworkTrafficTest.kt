package com.example.agent.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkTrafficTest {

    // ── Farb-/Schweregrad-Kodierung ─────────────────────────────

    @Test
    fun `bandbreiten-schwellen ergeben die spec-farben`() {
        assertEquals(NetworkTraffic.COLOR_HIGH, NetworkTraffic.trafficColor(200.0))
        assertEquals(NetworkTraffic.COLOR_MEDIUM, NetworkTraffic.trafficColor(80.0))
        assertEquals(NetworkTraffic.COLOR_LOW, NetworkTraffic.trafficColor(30.0))
        assertEquals(NetworkTraffic.COLOR_NORMAL, NetworkTraffic.trafficColor(15.0))
        assertEquals(NetworkTraffic.COLOR_IDLE, NetworkTraffic.trafficColor(5.0))
    }

    @Test
    fun `latenz dominiert die farbcodierung`() {
        assertEquals(NetworkTraffic.COLOR_HIGH, NetworkTraffic.trafficColor(10.0, latencyMs = 150.0))
        assertEquals(NetworkTraffic.COLOR_HIGH, NetworkTraffic.trafficColor(5.0, latencyMs = 150.0))
        assertEquals(NetworkTraffic.COLOR_MEDIUM, NetworkTraffic.trafficColor(15.0, latencyMs = 50.0))
        // MIN-Grenze ohne Verkehr/Latenz bleibt idle (kein Fehlalarm)
        assertEquals(NetworkTraffic.COLOR_IDLE, NetworkTraffic.trafficColor(10.0, latencyMs = 0.0))
    }

    @Test
    fun `severity ist kohaerent zur farbe`() {
        assertEquals("critical", NetworkTraffic.severity(200.0))
        assertEquals("critical", NetworkTraffic.severity(10.0, latencyMs = 150.0))
        assertEquals("warning", NetworkTraffic.severity(60.0))
        assertEquals("normal", NetworkTraffic.severity(15.0))
        assertEquals("idle", NetworkTraffic.severity(5.0))
    }

    // ── Partikel-Mapping ────────────────────────────────────────

    @Test
    fun `partikel-mapping folgt den spec-formeln`() {
        assertEquals(1, NetworkTraffic.particleCount(5.0))
        assertEquals(5, NetworkTraffic.particleCount(55.0))
        assertEquals(5, NetworkTraffic.particleCount(500.0)) // Cap
        assertEquals(0.3, NetworkTraffic.particleSpeed(100.0), 1e-12)
        assertEquals(0.05, NetworkTraffic.particleSize(100.0), 1e-12)
    }

    // ── Aggregation & Heatmap ───────────────────────────────────

    @Test
    fun `aktivitaets-aggregation summiert durchsatz und flusszahl`() {
        val flows = listOf(
            NetworkTraffic.TrafficFlow("a", "b", 10.0, latencyMs = 5.0),
            NetworkTraffic.TrafficFlow("b", "c", 20.0, latencyMs = 30.0),
            NetworkTraffic.TrafficFlow("a", "c", 40.0, latencyMs = 80.0),
        )
        val activity = NetworkTraffic.aggregateActivity(flows)
        assertEquals(50.0, activity.getValue("a").totalMbps, 1e-9)
        assertEquals(2, activity.getValue("a").flowCount)
        assertEquals(80.0, activity.getValue("a").maxLatencyMs, 1e-9)
        assertEquals(60.0, activity.getValue("c").totalMbps, 1e-9)
        assertTrue(activity.values.all { it.active })
    }

    @Test
    fun `top-nodes sortieren nach durchsatz`() {
        val flows = listOf(
            NetworkTraffic.TrafficFlow("a", "b", 10.0),
            NetworkTraffic.TrafficFlow("c", "d", 100.0),
            NetworkTraffic.TrafficFlow("e", "f", 50.0),
        )
        val top = NetworkTraffic.topNodes(NetworkTraffic.aggregateActivity(flows), topN = 2)
        assertEquals(setOf("c", "d"), top.map { it.nodeId }.toSet())
    }

    @Test
    fun `heatmap normalisiert auf den peak`() {
        val flows = listOf(
            NetworkTraffic.TrafficFlow("a", "b", 100.0),
            NetworkTraffic.TrafficFlow("a", "c", 50.0),
        )
        val activity = NetworkTraffic.aggregateActivity(flows)
        val columns = NetworkTraffic.heatmapColumns(activity, maxHeight = 1.0)
        assertEquals(1.0, columns.getValue("a"), 1e-9)
        assertEquals(100.0 / 150.0, columns.getValue("b"), 1e-9)
        assertEquals(50.0 / 150.0, columns.getValue("c"), 1e-9)
        assertTrue(NetworkTraffic.heatmapColumns(emptyMap()).isEmpty())
    }

    // ── Simulator ───────────────────────────────────────────────

    @Test
    fun `simulator ist mit seed deterministisch`() {
        val edges = listOf("a" to "b", "b" to "c", "c" to "d")
        val first = NetworkTraffic.TrafficSimulator(seed = 42).simulate(edges)
        val second = NetworkTraffic.TrafficSimulator(seed = 42).simulate(edges)
        assertEquals(first.map { it.bandwidthMbps }, second.map { it.bandwidthMbps })
        assertTrue(first.all { it.bandwidthMbps > 0 && it.latencyMs > 0 })
    }

    @Test
    fun `simulator erzeugt bursts bei burst-wahrscheinlichkeit 1`() {
        val sim = NetworkTraffic.TrafficSimulator(
            seed = 1, burstProbability = 1.0, burstFactor = 3.0
        )
        val flows = sim.simulate(List(20) { "a" to "b" })
        val avg = flows.map { it.bandwidthMbps }.average()
        assertTrue("Kein Burst: avg=$avg", avg > 2.0 * sim.baseBandwidthMbps)
    }
}
