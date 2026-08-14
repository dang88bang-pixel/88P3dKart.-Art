package com.example.agent.aura

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Radix-2-FFT (Cooley–Tukey, iterativ, in-place) ohne externe Bibliotheken.
 *
 * Basis für Cross-Korrelation ([CrossCorrelator]) und Spektrumsanalyse
 * ([RfBandClassifier]). Eingabelänge muss eine Zweierpotenz sein; für beliebige
 * Längen siehe [nextPowerOfTwo] (Zero-Padding).
 */
object Fft {

    /** Ergebnis einer Vorwärts-FFT als getrennte Real-/Imaginärteile. */
    data class Spectrum(
        val real: FloatArray,
        val imag: FloatArray,
        val size: Int,
    ) {
        /** Betragsspektrum (Spannung). */
        fun magnitude(): FloatArray {
            val mag = FloatArray(size)
            for (i in 0 until size) mag[i] = kotlin.math.hypot(real[i], imag[i])
            return mag
        }

        /** Leistungsspektrum |X|². */
        fun power(): FloatArray {
            val p = FloatArray(size)
            for (i in 0 until size) p[i] = real[i] * real[i] + imag[i] * imag[i]
            return p
        }
    }

    fun isPowerOfTwo(n: Int): Boolean = n > 0 && (n and (n - 1)) == 0

    /** Nächste Zweierpotenz >= [n]. */
    fun nextPowerOfTwo(n: Int): Int {
        require(n > 0) { "n muss > 0 sein" }
        var p = 1
        while (p < n) p = p shl 1
        return p
    }

    /** Vorwärts-FFT einer reellen Sequenz (Zero-Padding auf Zweierpotenz). */
    fun forward(signal: FloatArray): Spectrum {
        val n = nextPowerOfTwo(signal.size)
        val re = FloatArray(n)
        val im = FloatArray(n)
        signal.copyInto(re)
        transform(re, im, invert = false)
        return Spectrum(re, im, n)
    }

    /** FFT eines bereits komplexen Signals (Zero-Padding auf Zweierpotenz). */
    fun forward(reIn: FloatArray, imIn: FloatArray): Spectrum {
        require(reIn.size == imIn.size) { "Real-/Imaginärteile müssen gleich lang sein" }
        val n = nextPowerOfTwo(reIn.size)
        val re = FloatArray(n)
        val im = FloatArray(n)
        reIn.copyInto(re)
        imIn.copyInto(im)
        transform(re, im, invert = false)
        return Spectrum(re, im, n)
    }

    /** In-place-FFT (n muss Zweierpotenz sein). */
    fun transform(re: FloatArray, im: FloatArray, invert: Boolean) {
        val n = re.size
        require(isPowerOfTwo(n)) { "FFT-Länge muss Zweierpotenz sein (war: $n)" }
        require(im.size == n) { "Real-/Imaginärteile müssen gleich lang sein" }

        // Bit-Reversal-Permutation
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }

        // Butterfly-Schleifen
        var len = 2
        while (len <= n) {
            val ang = 2.0 * PI / len * if (invert) -1.0 else 1.0
            val wRe = cos(ang)
            val wIm = sin(ang)
            var i = 0
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0
                val half = len shr 1
                for (k in 0 until half) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vIdx = i + k + half
                    val vRe = re[vIdx] * curRe - im[vIdx] * curIm
                    val vIm = re[vIdx] * curIm + im[vIdx] * curRe
                    re[i + k] = (uRe + vRe).toFloat()
                    im[i + k] = (uIm + vIm).toFloat()
                    re[vIdx] = (uRe - vRe).toFloat()
                    im[vIdx] = (uIm - vIm).toFloat()
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                i += len
            }
            len = len shl 1
        }

        if (invert) {
            for (i in 0 until n) {
                re[i] /= n.toFloat()
                im[i] /= n.toFloat()
            }
        }
    }

    /** Inverse FFT eines [Spectrum] — Ergebnis als Realteil-Array (Länge [Spectrum.size]). */
    fun inverseRe(spectrum: Spectrum): FloatArray {
        val re = spectrum.real.clone()
        val im = spectrum.imag.clone()
        transform(re, im, invert = true)
        return re
    }
}
