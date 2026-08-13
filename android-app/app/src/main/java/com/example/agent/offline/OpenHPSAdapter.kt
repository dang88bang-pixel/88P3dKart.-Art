package com.example.agent.offline

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Portierung der OpenHPS-Kernalgorithmen:
 * - Trilateration (BLE/UWB, Least-Squares)
 * - Madgwick-IMU-Filter (Orientierung aus Gyro + Beschleunigung)
 */
object OpenHPSAdapter {

    data class Anchor(val x: Float, val y: Float, val z: Float, val distance: Float)

    /** RSSI → Distanz (Log-Distance-Pfadverlust, vereinfacht). */
    fun distanceFromRssi(rssi: Int, measuredPower: Int = -60, n: Float = 2.0f): Float =
        10f.pow((measuredPower - rssi) / (10f * n))

    /**
     * Position aus mindestens 3 Distanzmessungen (Least-Squares).
     */
    fun trilaterate(anchors: List<Anchor>): Triple<Float, Float, Float>? {
        if (anchors.size < 3) return null
        val ref = anchors[0]
        val n = anchors.size - 1
        val A = Array(n) { FloatArray(3) }
        val b = FloatArray(n)

        for (i in 1 until anchors.size) {
            val ai = anchors[i]
            A[i - 1][0] = 2 * (ai.x - ref.x)
            A[i - 1][1] = 2 * (ai.y - ref.y)
            A[i - 1][2] = 2 * (ai.z - ref.z)
            b[i - 1] = ref.distance.pow(2) - ai.distance.pow(2) +
                (ai.x.pow(2) + ai.y.pow(2) + ai.z.pow(2)) -
                (ref.x.pow(2) + ref.y.pow(2) + ref.z.pow(2))
        }

        val AtA = Array(3) { FloatArray(3) }
        val Atb = FloatArray(3)
        for (i in 0 until n) {
            for (j in 0 until 3) {
                Atb[j] += A[i][j] * b[i]
                for (k in 0 until 3) AtA[j][k] += A[i][j] * A[i][k]
            }
        }

        val invAtA = invert3x3(AtA) ?: return null
        val result = FloatArray(3)
        for (i in 0 until 3) {
            result[i] = invAtA[i][0] * Atb[0] + invAtA[i][1] * Atb[1] + invAtA[i][2] * Atb[2]
        }
        return Triple(result[0] + ref.x, result[1] + ref.y, result[2] + ref.z)
    }

    private fun invert3x3(m: Array<FloatArray>): Array<FloatArray>? {
        val det = m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) -
            m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
            m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])
        if (abs(det) < 1e-10f) return null

        val inv = Array(3) { FloatArray(3) }
        inv[0][0] = (m[1][1] * m[2][2] - m[1][2] * m[2][1]) / det
        inv[0][1] = (m[0][2] * m[2][1] - m[0][1] * m[2][2]) / det
        inv[0][2] = (m[0][1] * m[1][2] - m[0][2] * m[1][1]) / det
        inv[1][0] = (m[1][2] * m[2][0] - m[1][0] * m[2][2]) / det
        inv[1][1] = (m[0][0] * m[2][2] - m[0][2] * m[2][0]) / det
        inv[1][2] = (m[0][2] * m[1][0] - m[0][0] * m[1][2]) / det
        inv[2][0] = (m[1][0] * m[2][1] - m[1][1] * m[2][0]) / det
        inv[2][1] = (m[0][1] * m[2][0] - m[0][0] * m[2][1]) / det
        inv[2][2] = (m[0][0] * m[1][1] - m[0][1] * m[1][0]) / det
        return inv
    }

    /**
     * Madgwick-IMU-Filter (Gradientenabstieg auf Quaternionen).
     */
    class MadgwickFilter(private val beta: Float = 0.1f) {
        private var q0 = 1f
        private var q1 = 0f
        private var q2 = 0f
        private var q3 = 0f

        fun update(gx: Float, gy: Float, gz: Float, ax: Float, ay: Float, az: Float, dt: Float) {
            val norm = sqrt(ax * ax + ay * ay + az * az)
            if (norm == 0f) return
            val axn = ax / norm; val ayn = ay / norm; val azn = az / norm

            // Gradient der Fehlerfunktion
            val s0 = 2f * (q1 * q3 - q0 * q2) - axn
            val s1 = 2f * (q0 * q1 + q2 * q3) - ayn
            val s2 = 2f * (0.5f - q1 * q1 - q2 * q2) - azn
            val s3 = 2f * (q2 * q3 - q0 * q1)

            val recipNorm = 1f / sqrt(s0 * s0 + s1 * s1 + s2 * s2 + s3 * s3)
            val sn0 = s0 * recipNorm
            val sn1 = s1 * recipNorm
            val sn2 = s2 * recipNorm
            val sn3 = s3 * recipNorm

            // Gyro-Integration
            val qDot1 = 0.5f * (-q1 * gx - q2 * gy - q3 * gz)
            val qDot2 = 0.5f * (q0 * gx + q2 * gz - q3 * gy)
            val qDot3 = 0.5f * (q0 * gy - q1 * gz + q3 * gx)
            val qDot4 = 0.5f * (q0 * gz + q1 * gy - q2 * gx)

            q0 += (qDot1 - beta * sn0) * dt
            q1 += (qDot2 - beta * sn1) * dt
            q2 += (qDot3 - beta * sn2) * dt
            q3 += (qDot4 - beta * sn3) * dt

            val normQ = sqrt(q0 * q0 + q1 * q1 + q2 * q2 + q3 * q3)
            if (normQ > 0f) {
                q0 /= normQ; q1 /= normQ; q2 /= normQ; q3 /= normQ
            }
        }

        /** Roll, Pitch, Yaw in Grad. */
        fun getOrientation(): FloatArray {
            val roll = atan2(2f * (q0 * q1 + q2 * q3), 1f - 2f * (q1 * q1 + q2 * q2))
            val pitch = asin((2f * (q0 * q2 - q3 * q1)).coerceIn(-1f, 1f))
            val yaw = atan2(2f * (q0 * q3 + q1 * q2), 1f - 2f * (q2 * q2 + q3 * q3))
            return floatArrayOf(
                (roll * 180 / PI).toFloat(),
                (pitch * 180 / PI).toFloat(),
                (yaw * 180 / PI).toFloat(),
            )
        }
    }
}
