package com.example.agent.aura

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorsAndHeatmapTest {

    // ── TagVelocityTracker ───────────────────────────────────────────

    @Test
    fun `tag-geschwindigkeit wird aus positionsdifferenzen berechnet`() {
        val tracker = TagVelocityTracker(alpha = 0.6f)
        assertNull(tracker.updatePosition("AA:BB", 0f, 0f, 0f, nowMs = 0L))
        // 3-4-5-Dreieck: 5 m in 1 s → |v| = 5 m/s
        val tracked = tracker.updatePosition("AA:BB", 3f, 4f, 0f, nowMs = 1000L)
        assertEquals(3.0f, tracked!!.speedMs, 0.15f) // EMA: 0.6 · 5 = 3
        assertEquals(1.8f, tracked.velocity[0], 0.1f)
        assertEquals(2.4f, tracked.velocity[1], 0.1f)
    }

    @Test
    fun `tag-tracker reset bei langen messluecken`() {
        val tracker = TagVelocityTracker(alpha = 0.6f)
        tracker.updatePosition("AA:BB", 0f, 0f, 0f, nowMs = 0L)
        tracker.updatePosition("AA:BB", 1f, 0f, 0f, nowMs = 500L)
        // > 2 s Lücke → Reset, keine Geschwindigkeits-Fortschreibung
        assertNull(tracker.updatePosition("AA:BB", 10f, 0f, 0f, nowMs = 4000L))
        assertTrue(tracker.snapshots().isNotEmpty())
    }

    // ── GeoPoseMapper ────────────────────────────────────────────────

    @Test
    fun `null-rotationsvektor ergibt identitaetsmatrix und nord-heading`() {
        val matrix = GeoPoseMapper.rotationVectorToMatrix(floatArrayOf(0f, 0f, 0f))
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        for (i in identity.indices) assertEquals(identity[i], matrix[i], 1e-6f)

        val pose = GeoPoseMapper.rotationVectorToCameraPose(floatArrayOf(0f, 0f, 0f))
        assertEquals(0f, pose.headingDeg, 1e-3f)
        assertEquals(0f, pose.tiltDeg, 1e-3f)
        assertEquals(0f, pose.rollDeg, 1e-3f)
    }

    @Test
    fun `rotation um die z-achse dreht das kamera-heading`() {
        // Rotationsvektor (0, 0, π/2) → AOSP-Konvention: Azimut = −π/2 → 270°
        val pose = GeoPoseMapper.rotationVectorToCameraPose(
            floatArrayOf(0f, 0f, (Math.PI / 2).toFloat())
        )
        assertEquals(270f, pose.headingDeg, 1e-2f)
        assertEquals(0f, pose.tiltDeg, 1e-2f)
        assertEquals(0f, pose.rollDeg, 1e-2f)
    }

    @Test
    fun `rotationsmatrix ist orthogonal mit determinante 1`() {
        val rv = floatArrayOf(0.1f, 0.2f, 0.3f)
        val r = GeoPoseMapper.rotationVectorToMatrix(rv)
        // R · Rᵀ = I
        for (i in 0 until 3) {
            for (j in 0 until 3) {
                var dot = 0f
                for (k in 0 until 3) dot += r[i * 3 + k] * r[j * 3 + k]
                assertEquals(if (i == j) 1f else 0f, dot, 1e-5f)
            }
        }
        val det =
            r[0] * (r[4] * r[8] - r[5] * r[7]) -
                r[1] * (r[3] * r[8] - r[5] * r[6]) +
                r[2] * (r[3] * r[7] - r[4] * r[6])
        assertEquals(1f, det, 1e-5f)
    }

    // ── RfHeatmapBuilder ─────────────────────────────────────────────

    @Test
    fun `heatmap aggregiert zellen und normalisiert die extrusionshoehe`() {
        val samples = listOf(
            RfHeatmapBuilder.RfSample(0L, 0.3f, 0.3f, 0f, -50f, 433.92e6),
            RfHeatmapBuilder.RfSample(0L, 0.7f, 0.6f, 0f, -40f, 433.92e6),
            RfHeatmapBuilder.RfSample(0L, 2.3f, 2.3f, 0f, -90f, 433.92e6),
        )
        val cells = RfHeatmapBuilder.build(samples, cellSizeM = 1f, maxHeightM = 12f)
        assertEquals(2, cells.size)

        val cellA = cells.first { it.centerX == 0.5f && it.centerY == 0.5f }
        assertEquals(-45f, cellA.dbm, 1e-3f) // Mittelwert −50/−40
        // (−45 + 90) / 60 = 0.75 → Höhe 9 m
        assertEquals(9f, cellA.heightM, 1e-3f)

        val cellB = cells.first { it.centerX == 2.5f && it.centerY == 2.5f }
        assertEquals(0f, cellB.heightM, 1e-3f) // Minimum → keine Extrusion
    }

    @Test
    fun `dbm-schaetzung aus 8-bit IQ ist kalibrierbar`() {
        // Konstantes IQ-Signal 100/100: mean|IQ|² = 20000 → 43,01 dB digital
        val iq = ByteArray(1408)
        for (i in iq.indices) iq[i] = 100
        val dbm = RfHeatmapBuilder.estimateDbm(iq)
        assertEquals(-49.6f + 43.01f, dbm, 0.1f)

        // Kalibrierungs-Offset verschiebt das Ergebnis linear
        val shifted = RfHeatmapBuilder.estimateDbm(iq, calibrationOffsetDbm = -20f)
        assertEquals(dbm + 29.6f, shifted, 0.1f)

        // Leeres Signal → Rauschboden
        assertEquals(-120f, RfHeatmapBuilder.estimateDbm(ByteArray(0)), 1e-6f)
    }
}
