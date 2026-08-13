package com.example.agent.offline

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * UWB-Micro-Doppler (DFT) — Atemfrequenz aus Phasen-Daten.
 * Extrahiert den Peak im Bereich 0.15–0.6 Hz ohne externe Bibliotheken.
 */
class UwbDoppler(
    private val fs: Float = 20.0f,      // Sample-Rate [Hz]
    private val bufferSecs: Float = 5.0f, // Fensterlänge [s]
) {
    private val buffer = mutableListOf<Float>()
    private val maxSize = (fs * bufferSecs).toInt()

    fun feedPhase(phase: Float) {
        buffer.add(phase)
        if (buffer.size > maxSize) buffer.removeAt(0)
    }

    /**
     * DFT auf dem Buffer, Peak im Atembereich.
     * Rückgabe: (Frequenz_Hz, Konfidenz 0–1).
     */
    fun detectRespiration(): Pair<Float, Float> {
        if (buffer.size < maxSize) return 0f to 0f

        val n = buffer.size
        val data = buffer.toFloatArray()

        // DC-Offset entfernen
        val mean = data.average().toFloat()
        for (i in data.indices) data[i] -= mean

        // Hanning-Fensterung
        for (i in data.indices) {
            data[i] *= (0.5f * (1f - cos(2.0 * PI * i / (n - 1))).toFloat())
        }

        val freqMin = 0.15f
        val freqMax = 0.6f
        val numBins = 20
        val step = (freqMax - freqMin) / numBins

        var maxMag = 0f
        var peakFreq = 0f
        var totalEnergy = 0f

        for (k in 0 until numBins) {
            val f = freqMin + k * step
            var real = 0f
            var imag = 0f
            val omega = 2.0 * PI * f / fs
            for (i in data.indices) {
                real += data[i] * cos(omega * i).toFloat()
                imag += data[i] * sin(omega * i).toFloat()
            }
            val mag = sqrt(real * real + imag * imag)
            totalEnergy += mag
            if (mag > maxMag) {
                maxMag = mag
                peakFreq = f
            }
        }

        val confidence = if (totalEnergy > 0f) (maxMag / totalEnergy).coerceIn(0f, 1f) else 0f
        val validFreq = if (confidence > 0.3f) peakFreq else 0f
        return validFreq to confidence
    }

    fun reset() = buffer.clear()
}
