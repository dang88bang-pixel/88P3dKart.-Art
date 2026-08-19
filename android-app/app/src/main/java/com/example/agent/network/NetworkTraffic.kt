package com.example.agent.network

import java.util.Random
import kotlin.math.max
import kotlin.math.min

/**
 * Aktive Netzwerkvisualisierung — Kotlin-Kern (docs/NETWORK_LIVEVIEW.md).
 *
 * Spiegelung der Python-Implementierung (`edge-agent/network_traffic.py`)
 * mit identischer Numerik: zentrale Bandbreiten-/Latenz-Farbcodierung,
 * Partikel-Mapping, Aktivitäts-Aggregation, Bandbreiten-Heatmap und
 */
object NetworkTraffic {

    // ── Schwellen & Farben (konsolidiert aus der Spec) ───────────

    const val HIGH_BANDWIDTH = 100.0
    const val MEDIUM_BANDWIDTH = 50.0
    const val LOW_BANDWIDTH = 20.0
    const val MIN_BANDWIDTH = 10.0

    const val HIGH_LATENCY = 100.0  // ms
    const val MEDIUM_LATENCY = 40.0 // ms

    const val COLOR_HIGH = 0xFF3333
    const val COLOR_MEDIUM = 0xFF8800
    const val COLOR_LOW = 0xFFFF00
    const val COLOR_NORMAL = 0x44FF88
    const val COLOR_IDLE = 0x4488FF

    data class TrafficFlow(
        val source: String,
        val target: String,
        val bandwidthMbps: Double,
        val latencyMs: Double = 5.0,
        val packetLossPct: Double = 0.0,
        val timestamp: Long = System.currentTimeMillis(),
    )

    data class NodeActivity(
        val nodeId: String,
        val totalMbps: Double,
        val flowCount: Int,
        val maxLatencyMs: Double,
    ) {
        val active: Boolean get() = flowCount > 0
    }

    // ── Farb-/Schweregrad-Kodierung (Latenz dominiert) ──────────

    fun trafficColor(bandwidthMbps: Double, latencyMs: Double = 0.0): Int = when {
        latencyMs > HIGH_LATENCY -> COLOR_HIGH
        bandwidthMbps > HIGH_BANDWIDTH -> COLOR_HIGH
        bandwidthMbps > MEDIUM_BANDWIDTH || latencyMs > MEDIUM_LATENCY -> COLOR_MEDIUM
        bandwidthMbps > LOW_BANDWIDTH -> COLOR_LOW
        bandwidthMbps > MIN_BANDWIDTH -> COLOR_NORMAL
        else -> COLOR_IDLE
    }

    fun severity(bandwidthMbps: Double, latencyMs: Double = 0.0): String = when {
        latencyMs > HIGH_LATENCY || bandwidthMbps > HIGH_BANDWIDTH -> "critical"
        latencyMs > MEDIUM_LATENCY || bandwidthMbps > MEDIUM_BANDWIDTH -> "warning"
        bandwidthMbps > MIN_BANDWIDTH -> "normal"
        else -> "idle"
    }

    // ── Partikel-Mapping (Spec-Formeln) ─────────────────────────

    fun particleCount(bandwidthMbps: Double, maxCount: Int = 5): Int {
        if (bandwidthMbps < 1.0) return 1
        return min(maxCount, max(1, (bandwidthMbps / 10.0).toInt()))
    }

    fun particleSpeed(bandwidthMbps: Double, baseSpeed: Double = 0.2): Double =
        baseSpeed + bandwidthMbps / 1000.0

    fun particleSize(bandwidthMbps: Double, baseSize: Double = 0.03): Double =
        baseSize + bandwidthMbps / 5000.0

    // ── Aktivitäts-Aggregation & Heatmap ────────────────────────

    fun aggregateActivity(flows: List<TrafficFlow>): Map<String, NodeActivity> {
        val totals = HashMap<String, Double>()
        val counts = HashMap<String, Int>()
        val latencies = HashMap<String, Double>()
        for (flow in flows) {
            for (node in listOf(flow.source, flow.target)) {
                totals[node] = (totals[node] ?: 0.0) + flow.bandwidthMbps
                counts[node] = (counts[node] ?: 0) + 1
                latencies[node] = max(latencies[node] ?: 0.0, flow.latencyMs)
            }
        }
        return totals.mapValues { (node, total) ->
            NodeActivity(node, total, counts.getValue(node), latencies.getValue(node))
        }
    }

    fun topNodes(activity: Map<String, NodeActivity>, topN: Int = 5): List<NodeActivity> =
        activity.values.sortedByDescending { it.totalMbps }.take(topN)

    fun heatmapColumns(
        activity: Map<String, NodeActivity>,
        maxHeight: Double = 1.0,
    ): Map<String, Double> {
        if (activity.isEmpty()) return emptyMap()
        val peak = activity.values.maxOf { it.totalMbps }
        if (peak <= 0) return activity.keys.associateWith { 0.0 }
        return activity.mapValues { (_, a) -> (a.totalMbps / peak) * maxHeight }
    }

    /**
     * Deterministische What-If-Simulation von Netzwerk-Traffic
 * (Was-wäre-wenn-Analyse; der Live-Pfad nutzt die Edge-Agent-API).
 */
class NetworkTrafficSimulator(
        seed: Long = 42L,
        val baseBandwidthMbps: Double = 40.0,
        val burstProbability: Double = 0.15,
        val burstFactor: Double = 3.0,
    ) {
        private val rng = Random(seed)

        fun simulate(
            edges: List<Pair<String, String>>,
            timestamp: Long = System.currentTimeMillis(),
        ): List<TrafficFlow> {
            return edges.map { (source, target) ->
                val burst = rng.nextDouble() < burstProbability
                var bandwidth = baseBandwidthMbps * (if (burst) burstFactor else 1.0)
                bandwidth += rng.nextDouble() * 10.0 - 5.0
                bandwidth = max(0.5, bandwidth)
                var latency = 2.0 + 45.0 * (bandwidth / (baseBandwidthMbps * burstFactor))
                latency += rng.nextDouble() * 2.0 - 1.0
                val loss = max(0.0, (latency - 40.0) * 0.02)
                TrafficFlow(
                    source = source,
                    target = target,
                    bandwidthMbps = Math.round(bandwidth * 100.0) / 100.0,
                    latencyMs = Math.round(max(0.5, latency) * 100.0) / 100.0,
                    packetLossPct = Math.round(min(99.0, loss) * 100.0) / 100.0,
                    timestamp = timestamp,
                )
            }
        }
    }
}
