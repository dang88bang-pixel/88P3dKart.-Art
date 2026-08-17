package com.example.agent.sensors

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for the real CognitiveRadarPolicy.
 * These exercise real decision logic with different live sensor contexts.
 */
class CognitiveRadarPolicyTest {

    @Test
    fun highMotion_prefersMmWave_and_increasesRate() {
        val ctx = CognitiveRadarPolicy.SensorContext(
            scatteringDetected = false,
            thermalC = 38f,
            motionIntensity = 5.5f,
            batteryPercent = 70,
            mmwaveDopplerStrength = 0.7f
        )
        val rec = CognitiveRadarPolicy.recommend(ctx)

        assertEquals("mmwave", rec.preferredPrimarySensor)
        assertTrue(rec.scanRateFactor > 1.0f)
        assertTrue(rec.reason.contains("Doppler") || rec.reason.contains("motion"))
    }

    @Test
    fun lowBattery_and_highScattering_reducesRate() {
        val ctx = CognitiveRadarPolicy.SensorContext(
            scatteringDetected = true,
            thermalC = 42f,
            motionIntensity = 1.2f,
            batteryPercent = 18
        )
        val rec = CognitiveRadarPolicy.recommend(ctx)

        assertTrue(rec.scanRateFactor < 0.6f)
        assertTrue(rec.ekfRScale > 2.0f)
    }

    @Test
    fun highUwbPhaseVariance_enablesNlos_and_prefersUwb() {
        val ctx = CognitiveRadarPolicy.SensorContext(
            scatteringDetected = false,
            thermalC = 35f,
            motionIntensity = 0.8f,
            batteryPercent = 65,
            uwbPhaseVariance = 1.2f
        )
        val rec = CognitiveRadarPolicy.recommend(ctx)

        assertTrue(rec.enableNlosMode)
        assertEquals("uwb", rec.preferredPrimarySensor)
    }

    @Test
    fun applyToEkf_doesNotCrash_and_scalesNoise() {
        val ekf = EkfFusion(dt = 0.05f)
        val ctx = CognitiveRadarPolicy.SensorContext(
            scatteringDetected = true,
            thermalC = 65f,
            motionIntensity = 3.0f,
            batteryPercent = 55
        )

        // Should not throw and should have real effect
        CognitiveRadarPolicy.applyToEkf(ekf, ctx)

        val state = ekf.getState()
        assertEquals(6, state.size)
    }

    @Test
    fun statusSummary_isNonEmpty() {
        val ctx = CognitiveRadarPolicy.SensorContext(
            scatteringDetected = true,
            thermalC = 50f,
            motionIntensity = 2.1f,
            batteryPercent = 40
        )
        val summary = CognitiveRadarPolicy.getStatusSummary(ctx)
        assertTrue(summary.isNotBlank())
        assertTrue(summary.contains("Cognitive"))
    }
}