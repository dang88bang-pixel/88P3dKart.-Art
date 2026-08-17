package com.example.agent.offline

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Lightweight NLOS / Ghost Geometry helper (real data only).
 *
 * Uses existing UWB phase data (from UwbManager / UwbDoppler) to estimate
 * possible multipath / NLOS conditions and produce a very simple "ghost offset".
 *
 * This is the first practical step toward HoloRadar-style reconstruction:
 * detect that a signal is likely reflected and compute a crude correction.
 *
 * Runs on-device, very cheap. Full physics-based reconstruction stays on Edge.
 */
object NLOSGeometry {

    data class NlosEstimate(
        val isLikelyNlos: Boolean,
        val confidence: Float,           // 0..1
        val estimatedExtraDistance: Float, // meters (positive = behind wall)
        val suggestedCorrection: FloatArray // [dx, dy, dz] crude offset
    )

    /**
     * Analyze a window of UWB phase samples.
     * High variance + specific frequency content → likely reflection.
     */
    fun analyzeUwbPhases(phases: List<Float>, fs: Float = 20f): NlosEstimate {
        if (phases.size < 8) {
            return NlosEstimate(false, 0f, 0f, floatArrayOf(0f, 0f, 0f))
        }

        // Simple variance + peak-to-mean
        val mean = phases.average().toFloat()
        var sumSq = 0f
        for (p in phases) sumSq += (p - mean) * (p - mean)
        val variance = sumSq / phases.size

        // Very rough "reflection strength"
        val reflectionStrength = (variance / (2f * kotlin.math.PI)).coerceIn(0f, 1f)

        val isNlos = reflectionStrength > 0.55f
        val conf = reflectionStrength.coerceIn(0f, 0.95f)

        // Crude extra path length estimate (λ ≈ 0.03m at 10GHz, simplified)
        val extraPath = if (isNlos) (reflectionStrength * 1.8f) else 0f

        // Very naive direction assumption (we don't have angle here yet)
        // In real use this would come from AoA or multiple anchors.
        val correction = if (isNlos) {
            floatArrayOf(0f, extraPath * 0.7f, 0f) // assume mostly in Y for demo
        } else floatArrayOf(0f, 0f, 0f)

        return NlosEstimate(
            isLikelyNlos = isNlos,
            confidence = conf,
            estimatedExtraDistance = extraPath,
            suggestedCorrection = correction
        )
    }

    /**
     * Convenience: feed from existing UwbDoppler buffer or raw phase list.
     */
    fun fromPhaseBuffer(buffer: List<Float>): NlosEstimate =
        analyzeUwbPhases(buffer)
}