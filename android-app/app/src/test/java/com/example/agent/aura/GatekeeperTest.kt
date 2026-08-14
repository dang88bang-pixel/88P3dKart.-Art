package com.example.agent.aura

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

class GatekeeperTest {

    /** Gauß-Peak im Leistungsspektrum an Frequenz [peakHz] (Mittenfrequenz [centerHz]). */
    private fun spectrumWithPeak(
        n: Int,
        sampleRateHz: Float,
        centerHz: Double,
        peakHz: Double,
        peakPower: Float,
        widthBins: Int = 4,
    ): FloatArray {
        val spectrum = FloatArray(n) { 1f }
        val binHz = sampleRateHz / n
        val peakBin = ((peakHz - centerHz) / binHz + n / 2).toInt().coerceIn(0, n - 1)
        for (i in peakBin - widthBins..peakBin + widthBins) {
            if (i in spectrum.indices) {
                val d = i - peakBin
                spectrum[i] += peakPower * exp(-(d * d) / widthBins.toDouble()).toFloat()
            }
        }
        return spectrum
    }

    @Test
    fun `klassifikator erkennt 433 MHz Sender als NARROWBAND`() {
        val spectrum = spectrumWithPeak(
            n = 1024, sampleRateHz = 2.4e6f, centerHz = 433.92e6,
            peakHz = 433.92e6, peakPower = 500f,
        )
        val bands = RfBandClassifier.classify(spectrum, 2.4e6f, 433.92e6)
        assertTrue("Kein Band gefunden", bands.isNotEmpty())
        val b = bands.first { it.band == RfBandClassifier.Band.ISM_433 }
        assertEquals(RfBandClassifier.Modulation.NARROWBAND, b.modulation)
    }

    @Test
    fun `klassifikator erkennt 868 MHz und Out-of-Band-Sender`() {
        val spectrum = spectrumWithPeak(
            n = 1024, sampleRateHz = 2.4e6f, centerHz = 868.3e6,
            peakHz = 868.3e6, peakPower = 300f,
        )
        val bands = RfBandClassifier.classify(spectrum, 2.4e6f, 868.3e6)
        assertTrue(bands.any { it.band == RfBandClassifier.Band.ISM_868 })

        val outSpectrum = spectrumWithPeak(
            n = 1024, sampleRateHz = 2.4e6f, centerHz = 450e6,
            peakHz = 450.1e6, peakPower = 900f,
        )
        val outBands = RfBandClassifier.classify(outSpectrum, 2.4e6f, 450e6)
        assertTrue(outBands.any { it.band == RfBandClassifier.Band.UNKNOWN })
    }

    @Test
    fun `gatekeeper meldet starken unbekannten Sender`() {
        val gatekeeper = Gatekeeper()
        val spectrum = spectrumWithPeak(
            n = 1024, sampleRateHz = 2.4e6f, centerHz = 450e6,
            peakHz = 450.1e6, peakPower = 900f,
        )
        val alerts = collectAlerts(gatekeeper) {
            gatekeeper.onSpectrum(spectrum, 2.4e6f, 450e6, nowMs = 1_000L)
        }
        assertEquals(1, alerts.size)
        assertEquals(Gatekeeper.GatekeeperAlert.Category.RF_ANOMALY, alerts.first().category)
        assertTrue(alerts.first().message.contains("450"))
    }

    @Test
    fun `whitelist unterdrueckt Alerts bekannter Sender`() {
        val gatekeeper = Gatekeeper()
        gatekeeper.whitelistTransmitter(450.1e6, 50e3)
        val spectrum = spectrumWithPeak(
            n = 1024, sampleRateHz = 2.4e6f, centerHz = 450e6,
            peakHz = 450.1e6, peakPower = 900f,
        )
        val alerts = collectAlerts(gatekeeper) {
            gatekeeper.onSpectrum(spectrum, 2.4e6f, 450e6, nowMs = 5_000L)
        }
        assertEquals(0, alerts.size)
    }

    @Test
    fun `alert-cooldown verhindert spam`() {
        val gatekeeper = Gatekeeper()
        val spectrum = spectrumWithPeak(
            n = 1024, sampleRateHz = 2.4e6f, centerHz = 450e6,
            peakHz = 450.1e6, peakPower = 900f,
        )
        val alerts = collectAlerts(gatekeeper) {
            gatekeeper.onSpectrum(spectrum, 2.4e6f, 450e6, nowMs = 1_000L)
            gatekeeper.onSpectrum(spectrum, 2.4e6f, 450e6, nowMs = 2_000L) // < 5 s Cooldown
        }
        assertEquals(1, alerts.size)
    }

    @Test
    fun `port-scan wird blockiert`() {
        val gatekeeper = Gatekeeper()
        for (port in 1..11) {
            val v = gatekeeper.inspectEndpoint("10.0.0.2", 12345, "10.0.0.1", port, nowMs = 1000L)
            assertEquals(Gatekeeper.Verdict.ALLOW, v.verdict)
        }
        val verdict = gatekeeper.inspectEndpoint("10.0.0.2", 12345, "10.0.0.1", 12, nowMs = 1000L)
        assertEquals(Gatekeeper.Verdict.BLOCK, verdict.verdict)
    }

    @Test
    fun `dns-heuristik markiert tracking-namen`() {
        val gatekeeper = Gatekeeper()
        assertEquals(Gatekeeper.Verdict.ALLOW, gatekeeper.inspectDnsQuery("example.com").verdict)
        val warn = gatekeeper.inspectDnsQuery("api.track.example.com")
        assertEquals(Gatekeeper.Verdict.WARN, warn.verdict)
        assertTrue(warn.reason.contains("track"))
    }

    /** Registriert einen Collector und führt [block] synchron aus. */
    private fun collectAlerts(
        gatekeeper: Gatekeeper,
        block: () -> Unit,
    ): List<Gatekeeper.GatekeeperAlert> = runBlocking {
        val alerts = mutableListOf<Gatekeeper.GatekeeperAlert>()
        val job = launch { gatekeeper.alerts.collect { alerts.add(it) } }
        yield() // Collector registrieren (SharedFlow.tryEmit liefert synchron)
        block()
        yield()
        job.cancel()
        alerts.toList()
    }
}
