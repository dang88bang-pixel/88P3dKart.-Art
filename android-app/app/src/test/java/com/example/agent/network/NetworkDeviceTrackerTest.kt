package com.example.agent.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkDeviceTrackerTest {

    private fun device(id: String, rssi: Double) =
        NetworkDeviceTracker.NetworkDevice(id = id, type = "ble_device", name = "dev-$id", rssi = rssi)

    @Test
    fun `hinzugekommene und verschwundene geraete werden erkannt`() {
        val tracker = NetworkDeviceTracker()
        val first = tracker.update(listOf(device("a", -50.0), device("b", -60.0)))
        assertEquals(setOf("a", "b"), first.added.map { it.id }.toSet())
        assertTrue(first.removed.isEmpty())

        val second = tracker.update(listOf(device("a", -50.0), device("c", -70.0)))
        assertEquals(listOf("c"), second.added.map { it.id })
        assertEquals(listOf("b"), second.removed.map { it.id })
    }

    @Test
    fun `signalsprünge ueber der schwelle werden gemeldet`() {
        val tracker = NetworkDeviceTracker(signalChangeThresholdDbm = 10.0)
        tracker.update(listOf(device("a", -50.0)))
        val quiet = tracker.update(listOf(device("a", -55.0))) // 5 dBm
        assertTrue(quiet.signalChanges.isEmpty())
        val jump = tracker.update(listOf(device("a", -66.0))) // 11 dBm
        assertEquals(1, jump.signalChanges.size)
        assertEquals(11.0, jump.signalChanges.first().diff, 1e-9)
    }

    @Test
    fun `anomalien werden ueber die historien-abweichung erkannt`() {
        val tracker = NetworkDeviceTracker(anomalyDeviationDbm = 20.0, anomalyWindow = 5)
        repeat(5) {
            val result = tracker.update(listOf(device("a", -60.0)))
            assertTrue(result.anomalies.isEmpty())
        }
        val result = tracker.update(listOf(device("a", -95.0)))
        assertEquals(1, result.anomalies.size)
        assertEquals("a", result.anomalies.first().id)
    }

    @Test
    fun `clear setzt den tracker zurueck`() {
        val tracker = NetworkDeviceTracker()
        tracker.update(listOf(device("a", -50.0)))
        tracker.clear()
        assertTrue(tracker.knownDevices().isEmpty())
    }
}
