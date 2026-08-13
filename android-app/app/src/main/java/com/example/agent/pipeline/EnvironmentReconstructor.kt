package com.example.agent.pipeline

import kotlin.math.abs
import kotlin.math.max

/**
 * Stufe 4 — 3D-Umgebungsrekonstruktion.
 *
 * Rekonstruiert Grenzen, Volumen und Bodenfläche der Umgebung.
 */
class EnvironmentReconstructor {

    data class Environment(
        val boundsMin: FloatArray,
        val boundsMax: FloatArray,
        val volume: Float,
        val floorArea: Float,
    )

    fun reconstruct(
        points: List<DataAcquisitionService.SensorDataPoint>,
        objects: List<DataInterpreter.InterpretedObject>,
    ): Environment {
        if (points.isEmpty()) {
            return Environment(FloatArray(3), FloatArray(3), 0f, 0f)
        }

        val bMin = floatArrayOf(
            points.minOf { it.x }, points.minOf { it.y }, points.minOf { it.z }
        )
        val bMax = floatArrayOf(
            points.maxOf { it.x }, points.maxOf { it.y }, points.maxOf { it.z }
        )
        val volume = (bMax[0] - bMin[0]) * (bMax[1] - bMin[1]) * (bMax[2] - bMin[2])

        // Bodenfläche über die konvexe Hülle der untersten Punkte (XY)
        val zMin = points.minOf { it.z }
        val zSpan = max(1f, bMax[2] - bMin[2])
        val floorPts = points.filter { abs(it.z - zMin) < 0.05f * zSpan }
        val floorArea = if (floorPts.size >= 3) convexHullArea2D(floorPts) else 0f

        return Environment(bMin, bMax, volume, floorArea)
    }

    /** Monotone-Chain konvexe Hülle (2D), Fläche über Shoelace-Formel. */
    private fun convexHullArea2D(pts: List<DataAcquisitionService.SensorDataPoint>): Float {
        val sorted = pts.map { it.x to it.y }.distinct().sortedWith(compareBy({ it.first }, { it.second }))
        if (sorted.size < 3) return 0f

        fun cross(o: Pair<Float, Float>, a: Pair<Float, Float>, b: Pair<Float, Float>): Float =
            (a.first - o.first) * (b.second - o.second) - (a.second - o.second) * (b.first - o.first)

        val lower = mutableListOf<Pair<Float, Float>>()
        for (p in sorted) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0) lower.removeAt(lower.size - 1)
            lower.add(p)
        }
        val upper = mutableListOf<Pair<Float, Float>>()
        for (p in sorted.reversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0) upper.removeAt(upper.size - 1)
            upper.add(p)
        }
        val hull = lower.dropLast(1) + upper.dropLast(1)

        var area = 0f
        for (i in hull.indices) {
            val (x1, y1) = hull[i]
            val (x2, y2) = hull[(i + 1) % hull.size]
            area += x1 * y2 - x2 * y1
        }
        return abs(area) / 2f
    }
}
