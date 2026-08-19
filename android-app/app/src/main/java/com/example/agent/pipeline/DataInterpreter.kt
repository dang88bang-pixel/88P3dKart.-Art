package com.example.agent.pipeline

import com.example.agent.classification.Point3D
import com.example.agent.classification.WallPersonClassifier

/**
 * Stufe 2 — Datenanalyse & Interpretation.
 *
 * Segmentiert eine Punktwolke in Boden / Decke / Wand / Dynamik:
 * Das Mittelband wird mit der dreistufigen geometrischen Pipeline
 * (WallPersonClassifier, Spezifikation "Wand vs. Mensch") klassifiziert —
 * `wall` = statisch/persistierbar, `dynamic` = Live-Only (nie speichern).
 * Numerische Parität zur Python-Pipeline des Edge-Agents.
 */
class DataInterpreter(private val wallPersonClassifier: WallPersonClassifier = WallPersonClassifier()) {

    data class InterpretedObject(
        val kind: String,           // "floor", "wall", "dynamic", "unknown"
        val centroid: FloatArray,   // [x, y, z]
        val bboxMin: FloatArray,
        val bboxMax: FloatArray,
        val persistable: Boolean,
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
            val mid = points.filter { it.z in lo..hi }
            if (mid.isNotEmpty()) {
                // Geometrische Klassifikation des Mittelbands (3 Stufen):
                // Voxel-Grid → Höhenfilter → Clustering → PCA-Planarität →
                // Zylinder-/Plausibilitätsvalidierung.
                val midPoints = mid.map { Point3D(it.x, it.y, it.z) }
                val (reports, _) = wallPersonClassifier.classify(midPoints)
                for (report in reports) {
                    val kind = if (report.persistable) "wall" else "dynamic"
                    bands.add(
                        kind to mid.filter {
                            it.x in report.bboxMin.x..report.bboxMax.x &&
                                it.y in report.bboxMin.y..report.bboxMax.y &&
                                it.z in report.bboxMin.z..report.bboxMax.z
                        }
                    )
                }
            }
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
            objects.add(
                InterpretedObject(
                    kind = kind,
                    centroid = floatArrayOf(cx, cy, cz),
                    bboxMin = bMin,
                    bboxMax = bMax,
                    persistable = kind != "dynamic",
                )
            )
        }
        return objects
    }
}
