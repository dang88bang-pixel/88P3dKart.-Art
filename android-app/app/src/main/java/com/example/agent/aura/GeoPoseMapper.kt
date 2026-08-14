package com.example.agent.aura

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Sensorfusion und räumliche Kalibrierung (docs/AURA.md §4.3):
 * Transformation des Android-Sensor-Rotationsvektors
 * (`Sensor.TYPE_ROTATION_VECTOR`) in die Kamera-Pose des Google Maps 3D SDK.
 *
 * Der Quaternion→Matrix-Schritt bildet exakt die AOSP-Implementierung von
 * `SensorManager.getRotationMatrixFromVector` nach; die Euler-Winkel
 * entsprechen `SensorManager.getOrientation` (Azimut, Pitch, Roll). Die
 * Implementierung ist bewusst ohne Android-API geschrieben (JVM-testbar) —
 * im Produktivcode wird lediglich der Rotationsvektor des Sensors übergeben.
 *
 * Ergebnis ist der **„Röntgenblick"-Modus**: Kamera-Heading/Tilt/Roll folgen
 * der Blickrichtung des Anwenders, RTI-Objekte werden positionsgetreu
 * eingeblendet, wenn das Smartphone auf eine Wand gerichtet wird.
 */
object GeoPoseMapper {

    /** Kamera-Pose im Maps-3D-Koordinatensystem. */
    data class CameraPose(
        /** Kamera-Heading 0..360° (0° = Nord, im Uhrzeigersinn). */
        val headingDeg: Float,
        /** Kamera-Tilt −90..90° (0° = Draufsicht, ±90° = Horizont). */
        val tiltDeg: Float,
        /** Kamera-Roll −180..180° (Rotation um die Sichtachse). */
        val rollDeg: Float,
    )

    /**
     * Rotationsvektor (x, y, z) → Rotationsmatrix R (zeilenweise, 9 Werte,
     * Konvention wie `SensorManager.getRotationMatrixFromVector`).
     */
    fun rotationVectorToMatrix(rotationVector: FloatArray): FloatArray {
        require(rotationVector.size >= 3) { "Rotationsvektor braucht 3 Komponenten" }
        val x = rotationVector[0]
        val y = rotationVector[1]
        val z = rotationVector[2]

        val theta = sqrt(x * x + y * y + z * z)
        if (theta < 1e-9f) return floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

        // Quaternion aus Rotationsvektor (AOSP-Konvention)
        val half = theta / 2f
        val sinHalf = sin(half)
        val q0 = cos(half)
        val q1 = x / theta * sinHalf
        val q2 = y / theta * sinHalf
        val q3 = z / theta * sinHalf

        // AOSP getRotationMatrixFromVector (Äquivalenz)
        val sqQ1 = 2 * q1 * q1
        val sqQ2 = 2 * q2 * q2
        val sqQ3 = 2 * q3 * q3
        val q1q2 = 2 * q1 * q2
        val q3q0 = 2 * q3 * q0
        val q1q3 = 2 * q1 * q3
        val q2q0 = 2 * q2 * q0
        val q2q3 = 2 * q2 * q3
        val q1q0 = 2 * q1 * q0

        return floatArrayOf(
            1f - sqQ2 - sqQ3, q1q2 - q3q0, q1q3 + q2q0,
            q1q2 + q3q0, 1f - sqQ1 - sqQ3, q2q3 - q1q0,
            q1q3 - q2q0, q2q3 + q1q0, 1f - sqQ1 - sqQ2,
        )
    }

    /**
     * Euler-Winkel aus R (Konvention wie `SensorManager.getOrientation`):
     * azimuth = atan2(R[1], R[4]), pitch = asin(−R[7]), roll = atan2(−R[6], R[8]).
     */
    fun matrixToEuler(matrix: FloatArray): FloatArray {
        require(matrix.size >= 9) { "Matrix braucht 9 Werte" }
        val azimuth = atan2(matrix[1], matrix[4])
        val pitch = asin((-matrix[7]).coerceIn(-1f, 1f))
        val roll = atan2(-matrix[6], matrix[8])
        return floatArrayOf(azimuth, pitch, roll)
    }

    /**
     * Rotationsvektor → Maps-Kamerapose.
     *
     * | Parameter | Android | Maps 3D |
     * |---|---|---|
     * | Azimut (Yaw) | Magnetometer + Accel | Kamera-Heading (0° = Nord) |
     * | Pitch | Accelerometer | Kamera-Tilt (0° = Draufsicht) |
     * | Roll | Gyroscope | Kamera-Roll (Rotation um Sichtachse) |
     */
    fun rotationVectorToCameraPose(rotationVector: FloatArray): CameraPose {
        val euler = matrixToEuler(rotationVectorToMatrix(rotationVector))
        var heading = Math.toDegrees(euler[0].toDouble()).toFloat()
        heading = ((heading % 360f) + 360f) % 360f
        val tilt = Math.toDegrees(euler[1].toDouble()).toFloat()
        val roll = Math.toDegrees(euler[2].toDouble()).toFloat()
        return CameraPose(headingDeg = heading, tiltDeg = tilt, rollDeg = roll)
    }
}
