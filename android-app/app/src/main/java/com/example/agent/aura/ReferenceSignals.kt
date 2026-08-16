package com.example.agent.aura

import kotlin.math.PI
import kotlin.math.sin

/**
 * Referenzsignale für die Cross-Korrelation (docs/AURA.md §4.2).
 *
 * Der Sender strahlt ein bekanntes Referenzsignal ab (Chirp oder PN-Sequenz);
 * der Empfänger korreliert das Empfangssignal damit, um Laufzeit und
 * Multipath-Effekte zu bestimmen.
 */
object ReferenceSignals {

    /**
     * Linearer Chirp: sin(2π (f0·t + ½·k·t²)).
     * @param f0Hz Startfrequenz
     * @param f1Hz Endfrequenz
     * @param durationSec Signaldauer
     * @param sampleRateHz Abtastrate
     */
    fun chirp(
        f0Hz: Float,
        f1Hz: Float,
        durationSec: Float,
        sampleRateHz: Float,
    ): FloatArray {
        val n = (durationSec * sampleRateHz).toInt()
        require(n > 0) { "Dauer/Abtastrate ergibt leeres Signal" }
        val k = (f1Hz - f0Hz) / durationSec // Chirp-Rate [Hz/s]
        return FloatArray(n) { i ->
            val t = i / sampleRateHz
            sin((2.0 * PI * (f0Hz * t + 0.5 * k * t * t)).toFloat())
        }
    }

    /**
     * Pseudo-Noise-Sequenz (±1) über einen 15-Bit-LFSR (m-Sequenz, Maximalfolge).
     * @param lengthBits Länge in Chips
     */
    fun pnSequence(lengthBits: Int = 255, seed: Int = 0x5A5A): FloatArray {
        require(lengthBits in 1..32767) { "Länge unzulässig: $lengthBits" }
        var lfsr = seed and 0x7FFF
        if (lfsr == 0) lfsr = 1
        return FloatArray(lengthBits) {
            // Taps 14, 13 → x^15 + x^14 + 1 (Maximalfolge 32767)
            val bit = ((lfsr ushr 14) xor (lfsr ushr 13)) and 1
            lfsr = ((lfsr shl 1) or bit) and 0x7FFF
            if (((lfsr ushr 0) and 1) == 1) 1f else -1f
        }
    }

    /**
     * rx[n] = ref[n − delaySamples] (+ optionales Rauschen), sonst 0.
     */
    fun delayedCopy(
        ref: FloatArray,
        delaySamples: Int,
        noiseSigma: Float = 0f,
        seed: Long = 42L,
    ): FloatArray {
        val rx = FloatArray(ref.size)
        val rng = java.util.Random(seed)
        for (i in rx.indices) {
            val src = i - delaySamples
            val v = if (src in ref.indices) ref[src] else 0f
            rx[i] = v + if (noiseSigma > 0f) (rng.nextGaussian().toFloat() * noiseSigma) else 0f
        }
        return rx
    }
}
