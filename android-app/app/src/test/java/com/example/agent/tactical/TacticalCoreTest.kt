package com.example.agent.tactical

import org.junit.Assert.*
import org.junit.Test

/**
 * Basic JVM unit tests for TacticalHealthMonitoring core algorithms.
 * These run without Android runtime (pure logic).
 */
class TacticalCoreTest {

    private val monitor = TacticalHealthMonitoring()

    @Test
    fun evaluateStressLevel_low() {
        val p = TacticalHealthMonitoring.TacticalPersonnel(
            heartRate = 72,
            hrv = 55f,
            eda = 1.8f,
            spo2 = 98,
            temperature = 36.6f
        )
        assertEquals(TacticalHealthMonitoring.StressLevel.LOW, monitor.evaluateStressLevel(p))
    }

    @Test
    fun evaluateStressLevel_medium() {
        val p = TacticalHealthMonitoring.TacticalPersonnel(
            heartRate = 105,
            hrv = 32f,
            eda = 3.5f,
            spo2 = 95,
            temperature = 37.2f
        )
        assertEquals(TacticalHealthMonitoring.StressLevel.MEDIUM, monitor.evaluateStressLevel(p))
    }

    @Test
    fun evaluateStressLevel_high() {
        val p = TacticalHealthMonitoring.TacticalPersonnel(
            heartRate = 135,
            hrv = 22f,
            eda = 4.8f,
            spo2 = 93,
            temperature = 37.7f
        )
        assertEquals(TacticalHealthMonitoring.StressLevel.HIGH, monitor.evaluateStressLevel(p))
    }

    @Test
    fun evaluateStressLevel_critical() {
        val p = TacticalHealthMonitoring.TacticalPersonnel(
            heartRate = 162,
            hrv = 12f,
            eda = 5.5f,
            spo2 = 88,
            temperature = 38.9f
        )
        assertEquals(TacticalHealthMonitoring.StressLevel.CRITICAL, monitor.evaluateStressLevel(p))
    }

    @Test
    fun calculateCombatReadiness_optimal() {
        val p = TacticalHealthMonitoring.TacticalPersonnel(
            heartRate = 78,
            hrv = 48f,
            spo2 = 97,
            temperature = 36.8f
        )
        val score = monitor.calculateCombatReadiness(p)
        assertTrue("Readiness should be high", score > 0.85f)
    }

    @Test
    fun evaluatePersonnelStatus_kia() {
        val p = TacticalHealthMonitoring.TacticalPersonnel(heartRate = 0, spo2 = 65)
        assertEquals(TacticalHealthMonitoring.PersonnelStatus.KIA, monitor.evaluatePersonnelStatus(p))
    }

    @Test
    fun evaluatePersonnelStatus_casualty() {
        val p = TacticalHealthMonitoring.TacticalPersonnel(
            heartRate = 168,
            spo2 = 87,
            temperature = 40.2f
        )
        assertEquals(TacticalHealthMonitoring.PersonnelStatus.CASUALTY, monitor.evaluatePersonnelStatus(p))
    }

    @Test
    fun operationalOverview_basic() {
        // Register a couple of personnel
        val p1 = TacticalHealthMonitoring.TacticalPersonnel(
            name = "Test1", heartRate = 75, hrv = 50f, status = TacticalHealthMonitoring.PersonnelStatus.OPERATIONAL
        )
        val p2 = TacticalHealthMonitoring.TacticalPersonnel(
            name = "Test2", heartRate = 130, hrv = 18f, status = TacticalHealthMonitoring.PersonnelStatus.DEGRADED
        )

        // We use reflection-free approach: call register + get overview
        kotlinx.coroutines.runBlocking {
            monitor.registerPersonnel(p1)
            monitor.registerPersonnel(p2)
        }

        val overview = monitor.getOperationalOverview()
        assertEquals(2, overview["total"])
        assertEquals(1, overview["operational"])
        assertEquals(1, overview["degraded"])
    }
}