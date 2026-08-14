package com.example.agent.wireless

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconstructionHelpersTest {

    private fun point(id: String, x: Float, y: Float, z: Float = 0f, confidence: Float = 0.8f, source: String = "ble_token") =
        WirelessPoint(id, x, y, z, source, confidence)

    // ── DriftCorrector ───────────────────────────────────────────

    @Test
    fun `drift wird als geglaetteter offset geschaetzt`() {
        val corrector = DriftCorrector(maxCorrection = 2f, alpha = 1f)
        // Messung liegt konstant 0,5 m über der Referenz → Offset 0,5
        repeat(5) {
            corrector.observe(floatArrayOf(1.5f, 2.5f, 0.5f), floatArrayOf(1f, 2f, 0f))
        }
        val drift = corrector.currentDrift()
        assertEquals(0.5f, drift[0], 1e-3f)
        assertEquals(0.5f, drift[1], 1e-3f)
        assertEquals(0.5f, drift[2], 1e-3f)
        // Korrektur zieht den Offset ab
        val corrected = corrector.correct(floatArrayOf(1.5f, 2.5f, 0.5f))
        assertEquals(1f, corrected[0], 1e-3f)
    }

    @Test
    fun `drift ist auf das maximum begrenzt`() {
        val corrector = DriftCorrector(maxCorrection = 0.5f, alpha = 1f)
        corrector.observe(floatArrayOf(5f, 0f, 0f), floatArrayOf(0f, 0f, 0f))
        assertEquals(0.5f, corrector.currentDrift()[0], 1e-3f)
    }

    // ── LoopClosureDetector ─────────────────────────────────────

    @Test
    fun `loop-closure liefert korrektur beim wiederbesuch`() {
        val detector = LoopClosureDetector(threshold = 0.5f)
        assertNull(detector.visit(0f, 0f, 0f))
        // Rückkehr in die Nähe → Closure mit Korrektur-Offset
        val correction = detector.visit(0.1f, 0.1f, 0f)
        assertNotNull(correction)
        assertEquals(-0.01f, correction!![0], 1e-3f) // (0 − 0,1) · 0,1
        assertEquals(1, detector.loopClosureCount)
    }

    @Test
    fun `weit entfernte positionen loesen keine closure aus`() {
        val detector = LoopClosureDetector(threshold = 0.5f)
        detector.visit(0f, 0f, 0f)
        assertNull(detector.visit(5f, 5f, 0f))
        assertEquals(0, detector.loopClosureCount)
    }

    // ── PointClusterMerger ──────────────────────────────────────

    @Test
    fun `punkte innerhalb des radius verschmelzen gewichtet`() {
        val merger = PointClusterMerger(mergeRadius = 0.2f)
        val merged = merger.merge(
            listOf(
                point("a", 0f, 0f, 0f, confidence = 0.9f),
                point("b", 0.1f, 0f, 0f, confidence = 0.5f),
                point("c", 2f, 0f, 0f, confidence = 0.8f), // eigenes Cluster
            )
        )
        assertEquals(2, merged.size)
        val first = merged.first { it.pointCount == 2 }
        // Gewichtung: 0,9·(0) + 0,5·(0,1) / 1,4 ≈ 0,036
        assertTrue("Gewichteter Schwerpunkt falsch: ${first.x}", kotlin.math.abs(first.x - 0.036f) < 0.01f)
        assertEquals(0.7f, first.confidence, 1e-3f)
        assertEquals("ble_token", first.dominantSource)
    }

    @Test
    fun `punkte unter der konfidenzschwelle werden verworfen`() {
        val merger = PointClusterMerger(mergeRadius = 0.2f, confidenceThreshold = 0.3f)
        val merged = merger.merge(
            listOf(
                point("a", 0f, 0f, 0f, confidence = 0.2f), // verworfen
                point("b", 1f, 0f, 0f, confidence = 0.9f),
            )
        )
        assertEquals(1, merged.size)
        assertEquals(1, merged.first().pointCount)
    }

    @Test
    fun `dominante quelle entscheidet die semantik-zurodnung`() {
        val merger = PointClusterMerger(mergeRadius = 0.2f)
        val merged = merger.merge(
            listOf(
                point("a", 0f, 0f, 0f, source = "wifi"),
                point("b", 0.05f, 0f, 0f, source = "wifi"),
                point("c", 0.02f, 0f, 0f, source = "ble_token"),
            )
        )
        assertEquals(1, merged.size)
        assertEquals("wifi", merged.first().dominantSource)
    }
}
