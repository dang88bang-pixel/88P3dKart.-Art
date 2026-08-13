package com.example.agent.offline

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Bewegungsdetektion aus mmWave-Doppler und IMU-Beschleunigung.
 * Liefert einen Score in [0, 1] (0 = statisch, 1 = schnelle Bewegung).
 */
class MotionDetector {

    /** mmWave-basierte Bewegung (Geschwindigkeit in m/s). */
    fun detectFromMmwave(velocity: Float): Float = when {
        abs(velocity) > 1.0 -> 1.0f   // Rennen
        abs(velocity) > 0.2 -> 0.6f   // Gehen
        else -> 0.0f                  // Stehen
    }

    /** IMU-basierte Bewegung (Abweichung von der Schwerkraft ~9.81 m/s²). */
    fun detectFromImu(ax: Float, ay: Float, az: Float): Float {
        val magnitude = sqrt(ax * ax + ay * ay + az * az)
        val deviation = abs(magnitude - 9.81f)
        return when {
            deviation > 3.0 -> 1.0f
            deviation > 1.0 -> 0.5f
            else -> 0.0f
        }
    }

    /** Kombinierter Score aus mmWave und IMU. */
    fun combined(mmwaveVelocity: Float?, accel: FloatArray?): Float {
        var score = 0f
        var count = 0
        if (mmwaveVelocity != null) {
            score += detectFromMmwave(mmwaveVelocity)
            count++
        }
        if (accel != null && accel.size >= 3) {
            score += detectFromImu(accel[0], accel[1], accel[2])
            count++
        }
        return if (count > 0) score / count else 0f
    }
}
