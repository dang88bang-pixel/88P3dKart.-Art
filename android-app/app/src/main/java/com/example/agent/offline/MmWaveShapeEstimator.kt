package com.example.agent.offline

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight mmWave shape hint estimator (real data only).
 *
 * Inspired by mmNorm (MIT): instead of just "point = person", try to
 * infer a very crude 3D bounding box / shape from mmWave targets + velocity.
 *
 * This is a tiny on-device approximation. Full high-accuracy 3D reconstruction
 * (mmNorm / Rad-GS) is meant for Edge / Visualizer.
 *
 * Uses real SerialManager mmWave targets.
 */
object MmWaveShapeEstimator {

    data class CrudeShape(
        val center: FloatArray,      // [x, y, z]
        val size: FloatArray,        // [width, depth, height] approximate
        val confidence: Float,       // 0..1
        val typeHint: String         // "person", "box", "unknown"
    )

    /**
     * From a list of mmWave targets (x, y, z, velocity).
     * Very simple clustering + bounding box.
     */
    fun estimateFromTargets(targets: List<FloatArray>): CrudeShape? {
        if (targets.isEmpty()) return null

        // Compute rough centroid
        var sx = 0f; var sy = 0f; var sz = 0f
        var sv = 0f
        for (t in targets) {
            sx += t[0]; sy += t[1]; sz += t[2]
            if (t.size > 3) sv += abs(t[3])
        }
        val n = targets.size
        val cx = sx / n
        val cy = sy / n
        val cz = sz / n
        val avgVel = sv / n

        // Rough extents
        var minX = Float.MAX_VALUE; var maxX = Float.MIN_VALUE
        var minY = Float.MAX_VALUE; var maxY = Float.MIN_VALUE
        var minZ = Float.MAX_VALUE; var maxZ = Float.MIN_VALUE

        for (t in targets) {
            minX = min(minX, t[0]); maxX = max(maxX, t[0])
            minY = min(minY, t[1]); maxY = max(maxY, t[1])
            minZ = min(minZ, t[2]); maxZ = max(maxZ, t[2])
        }

        val w = maxX - minX
        val d = maxY - minY
        val h = maxZ - minZ

        // Very naive classification
        val type = when {
            h in 1.4f..2.1f && w < 1.0f && d < 1.0f && avgVel > 0.3f -> "person"
            w > 0.4f && d > 0.3f && h > 0.2f -> "box"
            else -> "unknown"
        }

        val conf = when (type) {
            "person" -> 0.65f + min(avgVel * 0.3f, 0.25f)
            "box" -> 0.55f
            else -> 0.35f
        }.coerceIn(0.2f, 0.92f)

        return CrudeShape(
            center = floatArrayOf(cx, cy, cz),
            size = floatArrayOf(w, d, h),
            confidence = conf,
            typeHint = type
        )
    }
}