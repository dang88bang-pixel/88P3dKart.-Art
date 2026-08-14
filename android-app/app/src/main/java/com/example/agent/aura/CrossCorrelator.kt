package com.example.agent.aura

import kotlin.math.log10
import kotlin.math.pow

/**
 * Kreuzkorrelation im Frequenzbereich (docs/AURA.md §4.2):
 *
 *     R(τ) = F⁻¹ { F{S_rx} · F{S_ref}* }
 *
 * Spitzen in der Korrelationsfunktion markieren den direkten Pfad (erste,
 * stärkste Spitze) und Reflexionen (Multipath). Anwendung: präzise
 * Distanzmessung (Laufzeit × c) und Dämpfungsschätzung je Link für die RTI.
 */
object CrossCorrelator {

    /** Ergebnis der Korrelation. */
    data class CorrelationResult(
        /** Korrelationswerte R(τ), Länge = FFT-Länge. */
        val correlation: FloatArray,
        /** Verzögerung je Sample in Sekunden: τ_i = (i − (refLen − 1)) / fs. */
        val delaysSec: FloatArray,
        val peakIndex: Int,
        /** Laufzeit des direkten Pfads (Sekunden, negativ = rx eilt vor). */
        val peakDelaySec: Float,
        /** Normalisierte Peak-Magnitude 0..1. */
        val peakMagnitude: Float,
        /** Abtastrate, mit der gerechnet wurde. */
        val sampleRateHz: Float,
    )

    /** Ein identifizierter Multipath-Pfad. */
    data class MultipathPeak(
        val delaySec: Float,
        val magnitude: Float,
        /** Relative Stärke zum Hauptpfad in dB (negativ). */
        val relativeDb: Float,
    )

    /**
     * FFT-basierte Kreuzkorrelation von [rx] mit dem Referenzsignal [ref].
     * Zero-Padding auf die nächste Zweierpotenz ≥ rx.size + ref.size − 1
     * (lineare Korrelation, kein Wrap-around).
     */
    fun correlate(
        rx: FloatArray,
        ref: FloatArray,
        sampleRateHz: Float,
    ): CorrelationResult {
        require(sampleRateHz > 0f) { "sampleRateHz muss > 0 sein" }
        val n = Fft.nextPowerOfTwo(rx.size + ref.size - 1)

        val rxRe = FloatArray(n)
        val rxIm = FloatArray(n)
        rx.copyInto(rxRe)
        Fft.transform(rxRe, rxIm, invert = false)

        val refRe = FloatArray(n)
        val refIm = FloatArray(n)
        ref.copyInto(refRe)
        Fft.transform(refRe, refIm, invert = false)

        // R = F{rx} · conj(F{ref})
        for (i in 0 until n) {
            val a = rxRe[i]
            val b = rxIm[i]
            val c = refRe[i]
            val d = -refIm[i]
            rxRe[i] = a * c - b * d
            rxIm[i] = a * d + b * c
        }
        Fft.transform(rxRe, rxIm, invert = true)

        var peak = 0
        var peakVal = 0f
        for (i in 0 until n) {
            if (rxRe[i] > peakVal) {
                peakVal = rxRe[i]
                peak = i
            }
        }

        val delays = FloatArray(n) { i -> (i - (ref.size - 1)) / sampleRateHz }
        val norm = if (peakVal > 0f) peakVal else 1f
        return CorrelationResult(
            correlation = rxRe,
            delaysSec = delays,
            peakIndex = peak,
            peakDelaySec = delays[peak],
            peakMagnitude = if (peakVal > 0f) 1f else 0f,
            sampleRateHz = sampleRateHz,
        ).also { r ->
            // Magnitude je Sample normalisieren (für findPeaks)
            for (i in 0 until n) r.correlation[i] = r.correlation[i] / norm
        }
    }

    /**
     * Extrahiert Multipath-Spitzen aus einem Korrelationsergebnis.
     * @param minProminenceDb Spitzen müssen mindestens so stark wie
     *        max − prominence sein (Standard 12 dB unter dem Maximum)
     * @param minSeparationSamples Mindestabstand zwischen Spitzen
     * @param maxPeaks Begrenzung der Rückgabe (1 = nur Hauptpfad)
     */
    fun findPeaks(
        result: CorrelationResult,
        minProminenceDb: Float = 12f,
        minSeparationSamples: Int = 8,
        maxPeaks: Int = 4,
    ): List<MultipathPeak> {
        val r = result.correlation
        if (r.isEmpty()) return emptyList()
        val maxVal = r.maxOrNull() ?: return emptyList()
        if (maxVal <= 0f) return emptyList()

        val threshold = maxVal * 10f.pow(-minProminenceDb / 20f)

        // Lokale Maxima
        data class Peak(val idx: Int, val mag: Float)

        val candidates = mutableListOf<Peak>()
        var i = 0
        while (i < r.size) {
            if (r[i] < threshold) {
                i++
                continue
            }
            // Anstieg bis zum Maximum verfolgen
            var j = i
            while (j + 1 < r.size && r[j + 1] >= r[j]) j++
            candidates.add(Peak(j, r[j]))
            i = j + 1
        }

        candidates.sortByDescending { it.mag }
        val peaks = mutableListOf<MultipathPeak>()
        for (c in candidates) {
            val tooClose = peaks.any {
                kotlin.math.abs(it.delaySec * result.sampleRateHz -
                    result.delaysSec[c.idx] * result.sampleRateHz) < minSeparationSamples
            }
            if (!tooClose) {
                peaks.add(
                    MultipathPeak(
                        delaySec = result.delaysSec[c.idx],
                        magnitude = c.mag,
                        relativeDb = 20f * log10(c.mag / maxVal),
                    )
                )
                if (peaks.size >= maxPeaks) break
            }
        }
        return peaks
    }

    /** Entfernung aus Laufzeit: d = τ · c. */
    fun delayToDistance(delaySec: Float, speedOfLight: Float = 299_792_458f): Float =
        delaySec * speedOfLight
}
