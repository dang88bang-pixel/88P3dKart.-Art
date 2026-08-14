package com.example.agent.radar

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Radar-/UWB-Signalverarbeitung — Kotlin-Kern (docs/PERSON_DETECTION.md).
 *
 * Spiegelung der Python-Implementierung (`edge-agent/radar_processing.py`)
 * mit identischer Numerik — die in der v13-Recherche genannten,
 * hardware-unabhängigen Mechanismen:
 *
 * - CA-CFAR (adaptiver Rauschboden, IR-UWB/RadarHPE-Mechanismus),
 * - MTI (statische Clutter-Entfernung, TI-Edge-AI-SDK-Mechanismus),
 * - Doppler-Geschwindigkeit (v = λ·Δφ / 4πT),
 * - Multi-Target-Tracker (NN-Assoziation + CV-Kalman, Zwei-Punkt-
 *   Initialisierung, Piecewise-White-Noise-Q, Gating, Coasting).
 */
object RadarProcessing {

    // ── Kleine Matrix-Helfer (zeilenweise) ────────────────────────

    internal fun matMul(a: DoubleArray, b: DoubleArray, n: Int, m: Int, k: Int): DoubleArray {
        val out = DoubleArray(n * k)
        for (i in 0 until n) {
            for (j in 0 until k) {
                var s = 0.0
                for (t in 0 until m) s += a[i * m + t] * b[t * k + j]
                out[i * k + j] = s
            }
        }
        return out
    }

    internal fun transpose(a: DoubleArray, n: Int, m: Int): DoubleArray {
        val out = DoubleArray(a.size)
        for (c in 0 until m) for (r in 0 until n) out[c * n + r] = a[r * m + c]
        return out
    }

    internal fun matAdd(a: DoubleArray, b: DoubleArray): DoubleArray =
        DoubleArray(a.size) { a[it] + b[it] }

    internal fun inv2(m: DoubleArray): DoubleArray {
        val det = m[0] * m[3] - m[1] * m[2]
        require(abs(det) >= 1e-12) { "singuläre 2×2-Matrix" }
        return doubleArrayOf(m[3] / det, -m[1] / det, -m[2] / det, m[0] / det)
    }

    // ── CA-CFAR ──────────────────────────────────────────────────

    data class CfarDetection(
        val index: Int,
        val value: Double,
        val threshold: Double,
        val snrDb: Double,
    )

    /** α = N · (PFA^(−1/N) − 1) — klassischer CA-CFAR-Schwellwertfaktor. */
    fun caCfarThresholdFactor(numTrainingCells: Int, pfa: Double): Double {
        require(numTrainingCells > 0) { "numTrainingCells muss > 0 sein" }
        require(pfa in 0.0..1.0 && pfa != 0.0 && pfa != 1.0) { "pfa muss in (0,1) liegen" }
        return numTrainingCells * (pfa.pow(-1.0 / numTrainingCells) - 1.0)
    }

    /**
     * CA-CFAR-Detektion: Schwelle = α · Mittelwert der Trainingszellen,
     * Detektionen sind lokale Maxima (Peak-Grouping im Guard-Fenster).
     */
    fun caCfar(
        signal: DoubleArray,
        guardCells: Int = 2,
        trainingCells: Int = 8,
        pfa: Double = 1e-4,
        minSnrDb: Double = 8.0,
    ): List<CfarDetection> {
        if (signal.isEmpty()) return emptyList()
        val alpha = caCfarThresholdFactor(trainingCells * 2, pfa)
        val halfWindow = guardCells + trainingCells
        val detections = ArrayList<CfarDetection>()
        var lastPeakIndex = Int.MIN_VALUE / 2

        for (i in signal.indices) {
            var windowSum = 0.0
            var windowCount = 0
            for (j in i - halfWindow until i - guardCells) {
                if (j in signal.indices) {
                    windowSum += signal[j]
                    windowCount++
                }
            }
            for (j in i + guardCells + 1..i + halfWindow) {
                if (j in signal.indices) {
                    windowSum += signal[j]
                    windowCount++
                }
            }
            if (windowCount < trainingCells) continue

            val noise = windowSum / windowCount
            val threshold = alpha * noise
            val snr = if (noise > 0) signal[i] / noise else Double.POSITIVE_INFINITY
            val snrDb = if (snr > 0) 10.0 * log10(snr) else Double.NEGATIVE_INFINITY

            if (signal[i] <= threshold || snrDb < minSnrDb) continue
            val localPeak = (maxOf(0, i - guardCells)..minOf(signal.size - 1, i + guardCells))
                .all { it == i || signal[it] <= signal[i] }
            if (!localPeak || i - lastPeakIndex <= guardCells) continue
            detections.add(CfarDetection(i, signal[i], threshold, snrDb))
            lastPeakIndex = i
        }
        return detections
    }

    // ── MTI (statische Clutter-Entfernung) ───────────────────────

    fun mtiSingleCanceler(frame: DoubleArray, previous: DoubleArray): DoubleArray {
        require(frame.size == previous.size) { "Frames müssen gleich lang sein" }
        return DoubleArray(frame.size) { frame[it] - previous[it] }
    }

    fun mtiDoubleCanceler(
        frame: DoubleArray,
        previous: DoubleArray,
        prePrevious: DoubleArray,
    ): DoubleArray {
        require(frame.size == previous.size && frame.size == prePrevious.size) {
            "Frames müssen gleich lang sein"
        }
        return DoubleArray(frame.size) { frame[it] - 2.0 * previous[it] + prePrevious[it] }
    }

    /** Anteil bewegter Energie nach MTI (0 = alles statisch, 1 = alles bewegt). */
    fun movingEnergyRatio(filtered: DoubleArray, original: DoubleArray): Double {
        require(filtered.size == original.size && original.isNotEmpty()) {
            "Frames müssen gleich lang und nicht leer sein"
        }
        val energyFiltered = filtered.sumOf { it * it }
        val energyOriginal = original.sumOf { it * it }
        if (energyOriginal <= 0) return 0.0
        return energyFiltered / energyOriginal
    }

    // ─── Doppler ─────────────────────────────────────────────────

    /** Vorzeichenrichtige Phasendifferenz in [−π, π]. */
    fun phaseDifference(phaseCurrent: Double, phasePrevious: Double): Double =
        (phaseCurrent - phasePrevious + PI) % (2.0 * PI) - PI

    /** v = λ·Δφ / (4π·T) — radiale Geschwindigkeit aus der Phasendifferenz. */
    fun dopplerVelocity(
        phaseCurrent: Double,
        phasePrevious: Double,
        wavelength: Double,
        frameTime: Double,
    ): Double {
        require(frameTime > 0) { "frameTime muss > 0 sein" }
        return wavelength * phaseDifference(phaseCurrent, phasePrevious) / (4.0 * PI * frameTime)
    }

    fun dopplerVelocityProfile(
        phasesCurrent: DoubleArray,
        phasesPrevious: DoubleArray,
        wavelength: Double,
        frameTime: Double,
    ): DoubleArray {
        require(phasesCurrent.size == phasesPrevious.size) { "Phasenvektoren müssen gleich lang sein" }
        return DoubleArray(phasesCurrent.size) {
            dopplerVelocity(phasesCurrent[it], phasesPrevious[it], wavelength, frameTime)
        }
    }

    // ── Multi-Target-Tracker ─────────────────────────────────────

    /** Ein Track: Zustand [x, y, vx, vy] + Kovarianz 4×4 (zeilenweise). */
    class Track(val id: Int, initialX: Double, initialY: Double) {
        var x: DoubleArray = doubleArrayOf(initialX, initialY, 0.0, 0.0)
        var p: DoubleArray = doubleArrayOf(
            1.0, 0.0, 0.0, 0.0,
            0.0, 1.0, 0.0, 0.0,
            0.0, 0.0, 0.5, 0.0,
            0.0, 0.0, 0.0, 0.5,
        )
        var hits: Int = 1
        var misses: Int = 0

        val confirmed: Boolean get() = hits >= 3

        fun predict(dt: Double, processNoise: Double) {
            x = doubleArrayOf(
                x[0] + x[2] * dt,
                x[1] + x[3] * dt,
                x[2],
                x[3],
            )
            // P' = F·P·Fᵀ + Q (Piecewise-White-Noise, Beschleunigungsrauschen qa)
            val f = doubleArrayOf(
                1.0, 0.0, dt, 0.0,
                0.0, 1.0, 0.0, dt,
                0.0, 0.0, 1.0, 0.0,
                0.0, 0.0, 0.0, 1.0,
            )
            val qa = processNoise
            val dt2 = dt * dt
            val dt3 = dt2 * dt
            val dt4 = dt2 * dt2
            val q = doubleArrayOf(
                qa * dt4 / 4.0, 0.0, qa * dt3 / 2.0, 0.0,
                0.0, qa * dt4 / 4.0, 0.0, qa * dt3 / 2.0,
                qa * dt3 / 2.0, 0.0, qa * dt2, 0.0,
                0.0, qa * dt3 / 2.0, 0.0, qa * dt2,
            )
            p = matAdd(
                matMul(matMul(f, p, 4, 4, 4), transpose(f, 4, 4), 4, 4, 4),
                q,
            )
        }

        fun update(zx: Double, zy: Double, measurementNoise: Double, dt: Double) {
            if (hits == 1) {
                // Zwei-Punkt-Initialisierung: v aus den ersten beiden Messungen
                if (dt > 0) {
                    x = doubleArrayOf(zx, zy, (zx - x[0]) / dt, (zy - x[1]) / dt)
                } else {
                    x = doubleArrayOf(zx, zy, 0.0, 0.0)
                }
                p = doubleArrayOf(
                    1.0, 0.0, 0.0, 0.0,
                    0.0, 1.0, 0.0, 0.0,
                    0.0, 0.0, 0.25, 0.0,
                    0.0, 0.0, 0.0, 0.25,
                )
                hits = 2
                misses = 0
                return
            }
            val h = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0)
            val r = doubleArrayOf(measurementNoise, 0.0, 0.0, measurementNoise)
            val s = matAdd(matMul(matMul(h, p, 2, 4, 4), transpose(h, 2, 4), 2, 4, 2), r)
            val sInv = inv2(s)
            val k = matMul(matMul(p, transpose(h, 2, 4), 4, 4, 2), sInv, 4, 2, 2)
            val innovation = doubleArrayOf(zx - x[0], zy - x[1])
            for (i in 0 until 4) {
                x[i] += k[i * 2] * innovation[0] + k[i * 2 + 1] * innovation[1]
            }
            val kh = matMul(k, h, 4, 2, 4)
            val identity = DoubleArray(16) { if (it % 5 == 0) 1.0 else 0.0 }
            p = matMul(DoubleArray(16) { identity[it] - kh[it] }, p, 4, 4, 4)
            hits++
            misses = 0
        }
    }

    /**
     * Nearest-Neighbor-Multi-Target-Tracking: Predict → Assoziation
     * (Gating) → Kalman-Update → Coasting.
     */
    class MultiTargetTracker(
        private val gateDistance: Double = 1.0,
        private val maxMisses: Int = 4,
        private val confirmHits: Int = 3,
        private val measurementNoise: Double = 0.25,
        private val processNoise: Double = 0.1,
    ) {
        private val tracks = ArrayList<Track>()
        private var nextId = 1

        val trackList: List<Track> get() = tracks.toList()
        val confirmedTracks: List<Track> get() = tracks.filter { it.confirmed }

        /** Detektionen als (x, y)-Paare je Scan. */
        fun update(detections: List<Pair<Double, Double>>, dt: Double): List<Track> {
            tracks.forEach { it.predict(dt, processNoise) }

            val unmatched = detections.toMutableList()
            if (unmatched.isEmpty()) {
                tracks.forEach { it.misses++ }
            } else {
                for (track in tracks) {
                    if (unmatched.isEmpty()) break
                    var bestIndex = -1
                    var bestDistance = Double.POSITIVE_INFINITY
                    for (i in unmatched.indices) {
                        val d = hypot(unmatched[i].first - track.x[0], unmatched[i].second - track.x[1])
                        if (d < bestDistance) {
                            bestDistance = d
                            bestIndex = i
                        }
                    }
                    if (bestIndex >= 0 && bestDistance <= gateDistance) {
                        val (zx, zy) = unmatched.removeAt(bestIndex)
                        track.update(zx, zy, measurementNoise, dt)
                    } else {
                        track.misses++
                    }
                }
            }

            for ((zx, zy) in unmatched) {
                tracks.add(Track(nextId++, zx, zy))
            }

            // Coasting: verwaiste Tracks entfernen
            tracks.removeAll { track ->
                track.misses > (if (track.confirmed) maxMisses * 2 else maxMisses)
            }
            return confirmedTracks
        }
    }
}
