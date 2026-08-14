package com.example.agent.maintenance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveThresholdMonitorTest {

    @Test
    fun `batterie 10 prozent loest kritischen schwellwert aus`() {
        val monitor = AdaptiveThresholdMonitor()
        val anomalies = monitor.analyze(mapOf("battery" to 10.0))
        // Original-Spec-Bug: value > critical war fuer niedrige Werte falsch —
        // hier muss der kritische Batterie-Schwellwert (15 %) greifen.
        assertTrue(anomalies.any { it.metric == "battery" && it.severity == Severity.CRITICAL })
    }

    @Test
    fun `rssi -85 loest kritisch aus obwohl der wert kleiner ist`() {
        val monitor = AdaptiveThresholdMonitor()
        val anomalies = monitor.analyze(mapOf("rssi" to -85.0))
        assertTrue(anomalies.any { it.metric == "rssi" && it.severity == Severity.CRITICAL })
    }

    @Test
    fun `cpu 95 loest kritisch aus`() {
        val monitor = AdaptiveThresholdMonitor()
        val anomalies = monitor.analyze(mapOf("cpu" to 95.0))
        assertTrue(anomalies.any { it.metric == "cpu" && it.severity == Severity.CRITICAL })
    }

    @Test
    fun `batterie 40 bleibt unauffaellig`() {
        val monitor = AdaptiveThresholdMonitor()
        val anomalies = monitor.analyze(mapOf("battery" to 40.0, "rssi" to -60.0))
        assertEquals(0, anomalies.size)
    }

    @Test
    fun `spike-erkennung reagiert auf 3-sigma-ausreisser`() {
        val monitor = AdaptiveThresholdMonitor()
        repeat(25) { monitor.analyze(mapOf("latency" to 50.0)) }
        val anomalies = monitor.analyze(mapOf("latency" to 400.0))
        assertTrue(
            "Spike nicht erkannt: $anomalies",
            anomalies.any { it.type == MetricAnomaly.Type.SPIKE && it.metric == "latency" },
        )
    }

    @Test
    fun `trend-erkennung unterscheidet die richtung je metrik`() {
        val rising = AdaptiveThresholdMonitor()
        // Latenz steigt linear (HIGHER_IS_WORSE → steigend = Alarm)
        for (i in 0 until 25) rising.analyze(mapOf("latency" to 50.0 + i * 2.0))
        val latencyAnomalies = rising.analyze(mapOf("latency" to 102.0))
        assertTrue(latencyAnomalies.any { it.type == MetricAnomaly.Type.TREND })

        val falling = AdaptiveThresholdMonitor()
        // RSSI fällt linear (LOWER_IS_WORSE → fallend = Alarm)
        for (i in 0 until 25) falling.analyze(mapOf("rssi" to -50.0 - i * 1.5))
        val rssiAnomalies = falling.analyze(mapOf("rssi" to -90.0))
        assertTrue(rssiAnomalies.any { it.type == MetricAnomaly.Type.TREND && it.metric == "rssi" })
    }

    @Test
    fun `kontextregel cpu plus temperatur loest kritisch aus`() {
        val monitor = AdaptiveThresholdMonitor()
        val anomalies = monitor.analyze(mapOf("cpu" to 85.0, "temperature" to 55.0))
        assertTrue(anomalies.any { it.type == MetricAnomaly.Type.CONTEXTUAL && it.severity == Severity.CRITICAL })
    }

    @Test
    fun `kontextregel netzwerk-degradation loest high aus`() {
        val monitor = AdaptiveThresholdMonitor()
        val anomalies = monitor.analyze(mapOf("latency" to 250.0, "packetLoss" to 4.0))
        assertTrue(anomalies.any { it.type == MetricAnomaly.Type.CONTEXTUAL && it.severity == Severity.HIGH })
    }

    @Test
    fun `lernmodus zieht die schwellwerte nach`() {
        val monitor = AdaptiveThresholdMonitor(learningMode = true)
        // 60 Samples mit Mittelwert 60, σ ≈ 5: datengetriebene Warnschwelle
        // ≈ 67,5 — die statische (70) wird behutsam nachgezogen.
        repeat(60) { i ->
            monitor.analyze(mapOf("latency" to 60.0 + (i % 7) * 1.0))
        }
        val thresholds = monitor.currentThresholds()
        val warning = thresholds["latency"]!!.warningThreshold
        assertTrue(
            "Warnschwelle wurde nicht adaptiert (noch $warning)",
            warning < 70.0,
        )
        // Lernmodus aus: Schwelle bleibt statisch
        val static = AdaptiveThresholdMonitor(learningMode = false)
        repeat(60) { static.analyze(mapOf("latency" to 60.0)) }
        assertEquals(70.0, static.currentThresholds()["latency"]!!.warningThreshold, 1e-9)
    }

    @Test
    fun `ungueltige werte werden ignoriert`() {
        val monitor = AdaptiveThresholdMonitor()
        val anomalies = monitor.analyze(mapOf("cpu" to Double.NaN))
        assertEquals(0, anomalies.size)
        assertFalse(monitor.historySize("cpu") > 0)
    }
}
