package com.example.agent.radar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow

class RadarProcessingTest {

    // ── CA-CFAR ──────────────────────────────────────────────────

    private fun rangeProfile(peaks: IntArray, n: Int = 256, peakPower: Double = 4.0): DoubleArray {
        val rng = Random(42)
        val profile = DoubleArray(n) { 0.02 * (0.5 + rng.nextDouble()) }
        for (pos in peaks) {
            for (i in maxOf(0, pos - 3) until minOf(n, pos + 4)) {
                profile[i] += peakPower * exp(-((i - pos) * (i - pos)) / 2.0)
            }
        }
        return profile
    }

    @Test
    fun `threshold-faktor folgt der klassischen formel`() {
        val alpha = RadarProcessing.caCfarThresholdFactor(16, 1e-4)
        val expected = 16 * (1e-4.pow(-1.0 / 16) - 1.0)
        assertEquals(expected, alpha, 1e-12)
    }

    @Test
    fun `cfar detektiert peaks ueber dem adaptiven rauschboden`() {
        val detections = RadarProcessing.caCfar(rangeProfile(intArrayOf(50, 150)))
        val indices = detections.map { it.index }
        assertTrue(50 in indices && 150 in indices)
        assertTrue(detections.all { it.snrDb > 8.0 })
    }

    @Test
    fun `cfar ignoriert reines rauschen`() {
        assertTrue(RadarProcessing.caCfar(rangeProfile(intArrayOf())).isEmpty())
    }

    @Test
    fun `cfar ohne doppeltreffer im guard-fenster`() {
        val detections = RadarProcessing.caCfar(
            rangeProfile(intArrayOf(100)), guardCells = 3, trainingCells = 8
        )
        assertEquals(1, detections.size)
    }

    // ── MTI ──────────────────────────────────────────────────────

    @Test
    fun `mti entfernt statischen clutter und behaelt bewegung`() {
        val static = doubleArrayOf(1.0, 2.0, 3.0, 4.0)
        val mover = doubleArrayOf(1.0, 2.0, 5.0, 4.0)
        val filtered = RadarProcessing.mtiSingleCanceler(mover, static)
        assertEquals(2.0, filtered[2], 1e-12)
        assertEquals(0.0, filtered[0], 1e-12)
        val ratio = RadarProcessing.movingEnergyRatio(filtered, mover)
        assertTrue(ratio > 0.0 && ratio < 1.0)
    }

    @Test
    fun `mti double canceller entfernt linearen drift-clutter`() {
        val f0 = doubleArrayOf(0.0, 1.0, 2.0, 3.0)
        val f1 = doubleArrayOf(1.0, 2.0, 3.0, 4.0)
        val f2 = doubleArrayOf(2.0, 3.0, 4.0, 5.0)
        val filtered = RadarProcessing.mtiDoubleCanceler(f2, f1, f0)
        assertTrue(filtered.all { kotlin.math.abs(it) < 1e-12 })
    }

    // ── Doppler ──────────────────────────────────────────────────

    @Test
    fun `phasendifferenz wrappt korrekt`() {
        assertEquals(0.1, RadarProcessing.phaseDifference(0.1, 0.0), 1e-12)
        assertEquals(-0.2831853071795862, RadarProcessing.phaseDifference(3.0, -3.0), 1e-9)
        assertEquals(0.2831853071795862, RadarProcessing.phaseDifference(-3.0, 3.0), 1e-9)
    }

    @Test
    fun `doppler-geschwindigkeit bei bekannter bewegung`() {
        // λ = 4 mm (77 GHz), T = 50 ms, Δφ = π/2 → v = λ/(8T)
        val wavelength = 4e-3
        val frameTime = 50e-3
        val velocity = RadarProcessing.dopplerVelocity(PI / 2, 0.0, wavelength, frameTime)
        assertEquals(wavelength / (8 * frameTime), velocity, 1e-12)
    }

    // ── Multi-Target-Tracker ─────────────────────────────────────

    private fun movingTarget(
        startX: Double,
        startY: Double,
        speedX: Double,
        steps: Int,
        rng: Random,
        noiseSigma: Double = 0.1,
    ): List<Pair<Double, Double>> = List(steps) { i ->
        (startX + speedX * i + rng.nextGaussian() * noiseSigma) to
            (startY + rng.nextGaussian() * noiseSigma)
    }

    @Test
    fun `tracker verfolgt zwei ziele mit stabilen ids`() {
        val rng = Random(7)
        val targetA = movingTarget(0.0, 0.0, 0.5, 20, rng)
        val targetB = movingTarget(5.0, 5.0, -0.5, 20, rng)

        val tracker = RadarProcessing.MultiTargetTracker(gateDistance = 1.0)
        var confirmed: List<RadarProcessing.Track> = emptyList()
        for (i in 0 until 20) {
            confirmed = tracker.update(listOf(targetA[i], targetB[i]), dt = 1.0)
        }

        assertEquals(2, confirmed.size)
        assertEquals(listOf(1, 2), confirmed.map { it.id }.sorted())

        val trackA = confirmed.first { it.id == 1 }
        assertTrue("Track A Position daneben: ${trackA.x[0]}", kotlin.math.abs(trackA.x[0] - targetA.last().first) < 0.5)
        assertTrue("Track A Geschwindigkeit falsch: ${trackA.x[2]}", kotlin.math.abs(trackA.x[2] - 0.5) < 0.4)
        val trackB = confirmed.first { it.id == 2 }
        assertTrue("Track B Geschwindigkeit falsch: ${trackB.x[2]}", kotlin.math.abs(trackB.x[2] + 0.5) < 0.4)
    }

    @Test
    fun `track-bestaetigung braucht drei treffer`() {
        val tracker = RadarProcessing.MultiTargetTracker(confirmHits = 3)
        assertTrue(tracker.update(listOf(0.0 to 0.0), dt = 0.1).isEmpty())
        assertTrue(tracker.update(listOf(0.1 to 0.0), dt = 0.1).isEmpty())
        assertEquals(1, tracker.update(listOf(0.2 to 0.0), dt = 0.1).size)
    }

    @Test
    fun `tracker coastet durch fehlende detektionen`() {
        val tracker = RadarProcessing.MultiTargetTracker(maxMisses = 4)
        for (i in 0 until 5) tracker.update(listOf(0.1 * i to 0.0), dt = 0.1)
        repeat(3) { assertEquals(1, tracker.update(emptyList(), dt = 0.1).size) }
        repeat(6) { tracker.update(emptyList(), dt = 0.1) }
        assertTrue(tracker.confirmedTracks.isEmpty())
    }

    @Test
    fun `gate ignoriert weit entfernte detektionen`() {
        val tracker = RadarProcessing.MultiTargetTracker(gateDistance = 1.0)
        tracker.update(listOf(0.0 to 0.0), dt = 0.1)
        tracker.update(listOf(0.1 to 0.0), dt = 0.1)
        tracker.update(listOf(0.2 to 0.0, 10.0 to 0.0), dt = 0.1)
        assertEquals(2, tracker.trackList.size)
    }
}
