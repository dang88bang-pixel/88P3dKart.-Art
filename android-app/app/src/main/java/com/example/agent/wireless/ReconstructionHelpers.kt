package com.example.agent.wireless

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Rekonstruktions-Helfer für den Wireless Mesh Reconstructor
 * (docs/WIRELESS_MESH.md) — Portierung der v8.1-Kernlogik mit Korrekturen.
 */

/** Wireless-Punkt (aus WiFi/BLE-Daten, v8.x-Datenmodell). */
data class WirelessPoint(
    val id: String,
    val x: Float,
    val y: Float,
    val z: Float,
    val source: String, // wifi | ble_beacon | ble_token | uwb
    val confidence: Float,
    val motionScore: Float = 0f,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * Drift-Korrektur.
 *
 * Korrektur gegenüber der Spec: dort wurde die **Steigung** der
 * Positionshistorie als „Drift" interpretiert — eine Steigung beschreibt
 * Bewegung, keinen Offset. Hier wird der Drift als geglätteter
 * **Offset** zwischen Messposition und Referenzposition geschätzt
 * (EWMA, begrenzt auf [maxCorrection]).
 */
class DriftCorrector(
    private val maxCorrection: Float = 0.5f,
    private val alpha: Float = 0.3f,
) {
    private var driftX = 0f
    private var driftY = 0f
    private var driftZ = 0f

    /** Beobachtet ein Mess-/Referenzpaar und aktualisiert den Offset. */
    fun observe(measured: FloatArray, reference: FloatArray) {
        require(measured.size >= 3 && reference.size >= 3)
        val ox = measured[0] - reference[0]
        val oy = measured[1] - reference[1]
        val oz = measured[2] - reference[2]
        driftX = (driftX * (1f - alpha) + alpha * ox).coerceIn(-maxCorrection, maxCorrection)
        driftY = (driftY * (1f - alpha) + alpha * oy).coerceIn(-maxCorrection, maxCorrection)
        driftZ = (driftZ * (1f - alpha) + alpha * oz).coerceIn(-maxCorrection, maxCorrection)
    }

    /** Korrigiert eine Messposition um den geschätzten Drift-Offset. */
    fun correct(position: FloatArray): FloatArray {
        require(position.size >= 3)
        return floatArrayOf(
            position[0] - driftX,
            position[1] - driftY,
            position[2] - driftZ,
        )
    }

    fun currentDrift(): FloatArray = floatArrayOf(driftX, driftY, driftZ)
}

/**
 * Loop-Closure-Erkennung: Wiedererkennung bereits besuchter Orte.
 * Liefert beim Schließen der Schleife den Korrektur-Offset (alt − neu).
 */
class LoopClosureDetector(
    private val threshold: Float = 0.5f,
    private val maxVisited: Int = 200,
    private val correctionWeight: Float = 0.1f,
) {
    data class VisitedPosition(val x: Float, val y: Float, val z: Float, val timestamp: Long)

    private val visited = ArrayDeque<VisitedPosition>()

    var loopClosureCount = 0
        private set

    /**
     * Meldet eine Position.
     * @return Korrektur-Offset [x, y, z] bei Loop-Closure, sonst null.
     */
    fun visit(x: Float, y: Float, z: Float): FloatArray? {
        var closure: FloatArray? = null
        for (v in visited) {
            val dx = v.x - x
            val dy = v.y - y
            val dz = v.z - z
            val dist = sqrt(dx * dx + dy * dy + dz * dz)
            if (dist < threshold && dist > 1e-6f) {
                loopClosureCount++
                closure = floatArrayOf(
                    (v.x - x) * correctionWeight,
                    (v.y - y) * correctionWeight,
                    (v.z - z) * correctionWeight,
                )
                break
            }
        }
        visited.addLast(VisitedPosition(x, y, z, System.currentTimeMillis()))
        while (visited.size > maxVisited) visited.removeFirst()
        return closure
    }

    fun reset() {
        visited.clear()
        loopClosureCount = 0
    }
}

/**
 * Punkt-Cluster-Merger: bündelt Punkte im [mergeRadius] zu gewichteten
 * Voxel-Kandidaten (v8.1: clusterPoints + calculateWeightedPosition +
 * Konfidenz-Schwelle).
 */
class PointClusterMerger(
    private val mergeRadius: Float = 0.15f,
    private val confidenceThreshold: Float = 0.3f,
) {
    data class MergedPoint(
        val x: Float,
        val y: Float,
        val z: Float,
        val confidence: Float,
        val motionScore: Float,
        val dominantSource: String,
        val pointCount: Int,
    )

    /**
     * Greedy-Clustering: jeder Punkt wird dem nächsten Cluster-Zentrum
     * innerhalb des Radius zugewiesen, sonst entsteht ein neues Cluster.
     */
    fun merge(points: List<WirelessPoint>): List<MergedPoint> {
        data class Cluster(
            var sumX: Double = 0.0, var sumY: Double = 0.0, var sumZ: Double = 0.0,
            var weightSum: Double = 0.0, var confidenceSum: Double = 0.0,
            var motionSum: Double = 0.0, var count: Int = 0,
            val sources: MutableMap<String, Int> = HashMap(),
        ) {
            val cx: Float get() = (sumX / weightSum).toFloat()
            val cy: Float get() = (sumY / weightSum).toFloat()
            val cz: Float get() = (sumZ / weightSum).toFloat()

            fun add(p: WirelessPoint) {
                val w = p.confidence.toDouble() * (1.0 + p.motionScore * 0.5)
                sumX += p.x * w
                sumY += p.y * w
                sumZ += p.z * w
                weightSum += w
                confidenceSum += p.confidence
                motionSum += p.motionScore
                sources[p.source] = (sources[p.source] ?: 0) + 1
                count++
            }
        }

        val clusters = mutableListOf<Cluster>()
        for (point in points) {
            if (point.confidence < confidenceThreshold) continue
            val target = clusters.firstOrNull { cluster ->
                val dx = cluster.cx - point.x
                val dy = cluster.cy - point.y
                val dz = cluster.cz - point.z
                sqrt(dx * dx + dy * dy + dz * dz) < mergeRadius
            }
            (target ?: Cluster().also { clusters.add(it) }).add(point)
        }

        return clusters.map { cluster ->
            val fallbackCount = cluster.count
            MergedPoint(
                x = if (cluster.weightSum > 0.0) cluster.cx else cluster.sumX / fallbackCount,
                y = if (cluster.weightSum > 0.0) cluster.cy else cluster.sumY / fallbackCount,
                z = if (cluster.weightSum > 0.0) cluster.cz else cluster.sumZ / fallbackCount,
                confidence = (cluster.confidenceSum / cluster.count).toFloat(),
                motionScore = (cluster.motionSum / cluster.count).toFloat(),
                dominantSource = cluster.sources.maxByOrNull { it.value }?.key ?: "unknown",
                pointCount = cluster.count,
            )
        }
    }
}
