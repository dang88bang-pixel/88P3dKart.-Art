package com.example.agent.aura

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CrossCorrelatorTest {

    @Test
    fun `FFT eines Impulses ist flach und FFT von DC liegt bei bin 0`() {
        // Impuls
        val impulse = FloatArray(256)
        impulse[0] = 1f
        val impulseSpectrum = Fft.forward(impulse)
        val mag = impulseSpectrum.magnitude()
        for (i in mag.indices) assertEquals(1f, mag[i], 1e-4f)

        // DC
        val dc = FloatArray(256) { 1f }
        val dcSpectrum = Fft.forward(dc)
        val power = dcSpectrum.power()
        assertEquals(256f * 256f, power[0], 1f)
        assertEquals(0f, power[1], 1f)
        assertEquals(0f, power[128], 1f)
    }

    @Test
    fun `FFT ist invertierbar (Parseval)`() {
        val signal = FloatArray(512) { kotlin.math.sin(it * 0.1).toFloat() }
        val spectrum = Fft.forward(signal)
        val reconstructed = Fft.inverseRe(spectrum)
        for (i in signal.indices) {
            assertEquals(signal[i], reconstructed[i], 1e-3f)
        }
    }

    @Test
    fun `korrelation findet exakte Laufzeit eines verzoegerten Chirps`() {
        val fs = 1000f
        val ref = ReferenceSignals.chirp(f0Hz = 50f, f1Hz = 300f, durationSec = 1f, sampleRateHz = fs)
        val delaySamples = 25
        val rx = ReferenceSignals.delayedCopy(ref, delaySamples)

        val result = CrossCorrelator.correlate(rx, ref, fs)
        val expectedDelaySec = delaySamples / fs
        assertEquals(expectedDelaySec, result.peakDelaySec, 1f / fs)
        assertEquals(1f, result.peakMagnitude, 1e-3f)

        // Distanz-Check: 25 ms Laufzeit ≈ 7,5 Mm (Lichtgeschwindigkeit)
        val distance = CrossCorrelator.delayToDistance(result.peakDelaySec)
        assertEquals(25e-3f * 299_792_458f, distance, 1e4f)
    }

    @Test
    fun `korrelation findet Laufzeit auch unter Rauschen`() {
        val fs = 2000f
        val ref = ReferenceSignals.pnSequence(1023)
        val delaySamples = 40
        val rx = ReferenceSignals.delayedCopy(ref, delaySamples, noiseSigma = 0.15f)

        val result = CrossCorrelator.correlate(rx, ref, fs)
        val errorSamples = abs(result.peakDelaySec * fs - delaySamples)
        assertTrue("Laufzeitfehler zu groß: $errorSamples Samples", errorSamples <= 1f)
    }

    @Test
    fun `multipath-peaks trennen direkten Pfad und Reflexion`() {
        val fs = 4000f
        val ref = ReferenceSignals.chirp(20f, 800f, 0.25f, fs)
        val delaySamples = 30
        val echoSamples = 95
        val echoAmplitude = 0.6f

        val direct = ReferenceSignals.delayedCopy(ref, delaySamples)
        val echo = ReferenceSignals.delayedCopy(ref, echoSamples)
        val rx = FloatArray(ref.size) { direct[it] + echoAmplitude * echo[it] }

        val result = CrossCorrelator.correlate(rx, ref, fs)
        val peaks = CrossCorrelator.findPeaks(
            result,
            minProminenceDb = 10f,
            minSeparationSamples = 8,
            maxPeaks = 2,
        )
        assertEquals(2, peaks.size)
        // Hauptpfad zuerst (stärkster Peak)
        assertEquals(delaySamples / fs, peaks[0].delaySec, 1f / fs)
        assertEquals(0f, peaks[0].relativeDb, 1e-3f)
        // Echo um ca. echoAmplitude (≈ −4,4 dB) schwächer
        assertEquals(echoSamples / fs, peaks[1].delaySec, 1f / fs)
        assertEquals(20f * kotlin.math.log10(echoAmplitude), peaks[1].relativeDb, 1.5f)
    }
}
