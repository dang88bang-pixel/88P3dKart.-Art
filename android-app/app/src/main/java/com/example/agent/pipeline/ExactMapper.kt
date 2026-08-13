package com.example.agent.pipeline

import kotlin.math.sqrt

/**
 * Stufe 5 — Exakte Abbildung & Darstellung.
 *
 * Zentriert die Punktwolke auf den Weltursprung (Referenz-Frame) und liefert
 * die Transform3D-Matrix (Offset, Rotation, Skalierung) für den Export in
 * Unity/Unreal (3dxStage).
 */
class ExactMapper {

    data class ExactMapping(
        val offset: FloatArray,   // [x, y, z] Translation
        val aligned: List<FloatArray>,
        val residual: Float,
    )

    data class Transform3D(
        val offsetX: Float, val offsetY: Float, val offsetZ: Float,
        val pitch: Float, val roll: Float, val yaw: Float,
        val scale: Float = 1f,
    )

    fun map(points: List<DataAcquisitionService.SensorDataPoint>): ExactMapping {
        if (points.isEmpty()) {
            return ExactMapping(FloatArray(3), emptyList(), 0f)
        }
        val cx = points.map { it.x }.average().toFloat()
        val cy = points.map { it.y }.average().toFloat()
        val cz = points.map { it.z }.average().toFloat()

        val aligned = points.map { floatArrayOf(it.x - cx, it.y - cy, it.z - cz) }
        val residual = aligned.map { sqrt(it[0] * it[0] + it[1] * it[1] + it[2] * it[2]) }
            .average().toFloat()

        return ExactMapping(floatArrayOf(-cx, -cy, -cz), aligned, residual)
    }

    fun toTransform3D(mapping: ExactMapping): Transform3D = Transform3D(
        offsetX = mapping.offset[0],
        offsetY = mapping.offset[1],
        offsetZ = mapping.offset[2],
        pitch = 0f, roll = 0f, yaw = 0f,
    )
}
