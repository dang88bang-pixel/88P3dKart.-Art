package com.example.agent.pipeline

import kotlin.math.max
import kotlin.math.min

/**
 * Stufe 2 — Datenanalyse & Interpretation.
 *
 * Segmentiert eine Punktwolke heuristisch in Boden / Wand / Person/Objekt
 * anhand der Z-Höhenverteilung.
 */
class DataInterpreter {

    data class InterpretedObject(
        val kind: String,           // "floor", "wall", "person", "unknown"
        val centroid: FloatArray,   // [x, y, z]
        val bboxMin: FloatArray,
        val bboxMax: FloatArray,
    )

    fun interpret(points: List<DataAcquisitionService.SensorDataPoint>): List<InterpretedObject> {
        if (points.isEmpty()) return emptyList()

        val z = points.map { it.z }
        val zMin = z.minOrNull() ?: 0f
        val zMax = z.maxOrNull() ?: 0f
        val span = zMax - zMin

        val bands = mutableListOf<Pair<String, List<DataAcquisitionService.SensorDataPoint>>>()
        if (span < 1e-6f) {
            bands.add("floor" to points)
        } else {
            val lo = zMin + 0.15f * span
            val hi = zMax - 0.15f * span
            bands.add("floor" to points.filter { it.z < lo })
            bands.add("wall" to points.filter { it.z > hi })
            bands.add("person" to points.filter { it.z in lo..hi })
        }

        val objects = mutableListOf<InterpretedObject>()
        for ((kind, pts) in bands) {
            if (pts.isEmpty()) continue
            val cx = pts.map { it.x }.average().toFloat()
            val cy = pts.map { it.y }.average().toFloat()
            val cz = pts.map { it.z }.average().toFloat()
            val bMin = floatArrayOf(
                pts.minOf { it.x }, pts.minOf { it.y }, pts.minOf { it.z }
            )
            val bMax = floatArrayOf(
                pts.maxOf { it.x }, pts.maxOf { it.y }, pts.maxOf { it.z }
            )
            objects.add(InterpretedObject(kind, floatArrayOf(cx, cy, cz), bMin, bMax))
        }
        return objects
    }
}
