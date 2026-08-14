package com.example.agent.wireless

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvironmentModelsTest {

    @Test
    fun `presets decken alle spezifikations-umgebungen ab`() {
        val names = EnvironmentModels.PRESETS.map { it.name }.toSet()
        assertTrue("FREIRAUM" in names && "BUERO" in names && "LAGER" in names)
        assertTrue("WERKSTATT" in names && "STADT_AUSSEN" in names && "INDUSTRIE" in names)
    }

    @Test
    fun `rssi-konfidenzstaffel entspricht der spec`() {
        assertEquals(0.95, EnvironmentModels.rssiConfidence(-49), 1e-9)
        assertEquals(0.85, EnvironmentModels.rssiConfidence(-59), 1e-9)
        assertEquals(0.70, EnvironmentModels.rssiConfidence(-69), 1e-9)
        assertEquals(0.50, EnvironmentModels.rssiConfidence(-79), 1e-9)
        assertEquals(0.30, EnvironmentModels.rssiConfidence(-89), 1e-9)
        assertEquals(0.15, EnvironmentModels.rssiConfidence(-99), 1e-9)
    }

    @Test
    fun `selector findet das richtige preset unter rauschen`() {
        val selector = AdaptiveEnvironmentSelector()
        // Messwerte gemäß LAGER-Modell (n=2.4, A=-55) mit leichtem Rauschen
        val measurements = listOf(1.0, 2.0, 4.0, 8.0, 16.0, 1.5, 3.0, 6.0, 12.0, 24.0)
            .map { d ->
                val rssi = (-55.0 - 10.0 * 2.4 * kotlin.math.log10(d) + (d % 3) * 0.5).toInt()
                EnvironmentModels.RssiMeasurement(rssi, d)
            }
        val best = selector.selectBest(measurements)
        assertEquals("LAGER", best.name)
        assertTrue(selector.confidence > 0.3)
    }

    @Test
    fun `selector bleibt bei zu wenigen samples beim default`() {
        val selector = AdaptiveEnvironmentSelector()
        val best = selector.selectBest(
            listOf(EnvironmentModels.RssiMeasurement(-50, 1.0))
        )
        assertEquals("BUERO", best.name)
        assertEquals(0.5, selector.confidence, 1e-9)
    }

    @Test
    fun `distanzformel liefert referenzdistanz bei referenz-rssi`() {
        val env = EnvironmentModels.PRESETS.first { it.name == "BUERO" }
        // RSSI = A → d = 1 m
        assertEquals(1.0, EnvironmentModels.distance(-55, env), 1e-6)
        // 10 dB schwächer bei n=2.8 → 10^(10/28) ≈ 2,28 m
        assertEquals(2.28, EnvironmentModels.distance(-65, env), 0.05)
    }
}
