package com.example.agent.maintenance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryHealthTrackerTest {

    private fun state(
        level: Double,
        temp: Double = 25.0,
        charging: Boolean = false,
        tMs: Long,
    ) = BatteryHealthTracker.BatteryState(level, temp, charging, timestampMs = tMs)

    @Test
    fun `erster aufruf setzt nur die baseline`() {
        val tracker = BatteryHealthTracker()
        assertNull(tracker.onBatteryState(state(100.0, tMs = 0L)))
    }

    @Test
    fun `50 prozent entladung ergibt ein halbes zyklusaequivalent`() {
        val tracker = BatteryHealthTracker(cycleDegradationPct = 0.01)
        tracker.onBatteryState(state(100.0, tMs = 0L))
        tracker.onBatteryState(state(80.0, tMs = 600_000L))
        tracker.onBatteryState(state(60.0, tMs = 1_200_000L))
        val report = tracker.onBatteryState(state(50.0, tMs = 1_800_000L))
        assertEquals(0.5, report!!.cycleCount, 1e-9)
        // Health: 100 − 0,5·0,01 ≈ 100 (gerundet unverändert)
        assertEquals(99.995, report.healthPct, 1e-6)
    }

    @Test
    fun `ladezyklen verschlechtern den health-wert`() {
        val tracker = BatteryHealthTracker(cycleDegradationPct = 0.01)
        // 200 Zyklenäquivalente direkt setzen (z. B. aus Gerätehistorie)
        tracker.setCycleCount(200.0)
        tracker.onBatteryState(state(90.0, tMs = 0L))
        val report = tracker.onBatteryState(state(89.0, tMs = 60_000L))
        // Health = 100 − 200·0,01 − kalendarisch ≈ 98
        assertTrue("Health zu hoch: ${report!!.healthPct}", report.healthPct <= 98.01)
        assertTrue(report.remainingCyclesToEol > 0.0)
    }

    @Test
    fun `laden wird nicht als entladung gezaehlt`() {
        val tracker = BatteryHealthTracker()
        tracker.onBatteryState(state(50.0, tMs = 0L))
        tracker.onBatteryState(state(60.0, charging = true, tMs = 60_000L))
        tracker.onBatteryState(state(70.0, charging = true, tMs = 120_000L))
        val report = tracker.onBatteryState(state(75.0, charging = true, tMs = 180_000L))
        assertEquals(0.0, report!!.cycleCount, 1e-9)
    }

    @Test
    fun `restlaufzeit wird aus der entladerate geschaetzt`() {
        val tracker = BatteryHealthTracker()
        tracker.onBatteryState(state(100.0, tMs = 0L))
        // 20 % Entladung in 1 Stunde → 20 %/h → bei 80 % Rest: 4 h
        tracker.onBatteryState(state(90.0, tMs = 1_800_000L))
        val report = tracker.onBatteryState(state(80.0, tMs = 3_600_000L))
        val runtimeMin = report!!.remainingRuntimeMin
        assertTrue("Restlaufzeit unplausibel: ${runtimeMin}min", runtimeMin in 220.0..260.0)
    }

    @Test
    fun `empfehlungen erscheinen an den richtigen schwellen`() {
        val tracker = BatteryHealthTracker()
        tracker.onBatteryState(state(60.0, tMs = 0L))
        // kritisch niedrig (< 20 %) + überhitzt (> 45 °C)
        val report = tracker.onBatteryState(state(19.0, temp = 50.0, tMs = 60_000L))
        assertTrue(report!!.recommendations.any { it.contains("kritisch niedrig") })
        assertTrue(report.recommendations.any { it.contains("überhitzt") })

        // Ladestopp-Empfehlung bei > 80 % während des Ladens
        tracker.onBatteryState(state(20.0, tMs = 120_000L))
        val charging = tracker.onBatteryState(state(85.0, charging = true, tMs = 180_000L))
        assertTrue(charging!!.recommendations.any { it.contains("Ladestopp") })
    }
}
