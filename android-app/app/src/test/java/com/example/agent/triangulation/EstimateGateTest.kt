package com.example.agent.triangulation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EstimateGateTest {

    private fun estimate(
        source: PositionEstimate.Source,
        x: Double,
        y: Double,
        z: Double = 0.0,
        accuracyM: Double = 1.0,
        ageMs: Long = 0L,
    ) = PositionEstimate(
        timestampMs = System.currentTimeMillis() - ageMs,
        source = source,
        x = x,
        y = y,
        z = z,
        accuracyM = accuracyM,
        confidence = 0.8f,
    )

    @Test
    fun `frische-pruefung respektiert die quellen-spezifischen alter`() {
        val now = System.currentTimeMillis()
        assertTrue(EstimateGate.isFresh(estimate(PositionEstimate.Source.WIFI_RTT, 0.0, 0.0, ageMs = 4_000), now))
        assertFalse(EstimateGate.isFresh(estimate(PositionEstimate.Source.WIFI_RTT, 0.0, 0.0, ageMs = 6_000), now))
        assertTrue(EstimateGate.isFresh(estimate(PositionEstimate.Source.BLE_RSSI, 0.0, 0.0, ageMs = 2_000), now))
        assertFalse(EstimateGate.isFresh(estimate(PositionEstimate.Source.BLE_RSSI, 0.0, 0.0, ageMs = 4_000), now))
        assertTrue(EstimateGate.isFresh(estimate(PositionEstimate.Source.WIFI_FINGERPRINT, 0.0, 0.0, ageMs = 9_000), now))
        assertFalse(EstimateGate.isFresh(null, now))
    }

    @Test
    fun `mahalanobis-konsistenztest trennt konsistente von widerspruechlichen schaetzungen`() {
        val a = estimate(PositionEstimate.Source.WIFI_RTT, 0.0, 0.0, accuracyM = 1.0)
        // 1 m Abstand bei σ=1 m je Quelle: √2·3 ≈ 4,2 m > 1 m → konsistent
        val b = estimate(PositionEstimate.Source.BLE_RSSI, 1.0, 0.0, accuracyM = 1.0)
        assertTrue(EstimateGate.consistent(a, b))

        // 10 m Abstand bei σ=0,5 m: √0,5·3 ≈ 2,1 m < 10 m → inkonsistent
        val c = estimate(PositionEstimate.Source.BLE_RSSI, 10.0, 0.0, accuracyM = 0.5)
        assertFalse(EstimateGate.consistent(a, c))
    }

    @Test
    fun `invers-varianz-gewichteter mittelwert bevorzugt die genauere quelle`() {
        val a = estimate(PositionEstimate.Source.WIFI_RTT, 0.0, 0.0, accuracyM = 1.0)
        val b = estimate(PositionEstimate.Source.BLE_RSSI, 4.0, 0.0, accuracyM = 1.0)
        val fused = EstimateGate.weightedMean(a, b, timestampMs = 1234L)
        assertEquals(PositionEstimate.Source.FUSED, fused.source)
        assertEquals(2.0, fused.x, 1e-9)
        assertEquals(1.0 / kotlin.math.sqrt(2.0), fused.accuracyM, 1e-9)
        assertEquals(1234L, fused.timestampMs)

        // a (σ=1) gegen c (σ=3): wA=1, wC=1/9 → x = 4/10 · 0 + ... = 0,4
        val c = estimate(PositionEstimate.Source.BLE_RSSI, 4.0, 0.0, accuracyM = 3.0)
        val fused2 = EstimateGate.weightedMean(a, c)
        assertEquals(0.4, fused2.x, 1e-6)
        assertEquals(kotlin.math.sqrt(0.9), fused2.accuracyM, 1e-6)
    }
}
