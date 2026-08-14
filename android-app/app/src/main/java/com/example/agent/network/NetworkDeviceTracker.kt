package com.example.agent.network

import kotlin.math.abs

/**
 * Live-Netzwerk-Geräte-Tracker (docs/TACTICAL.md) — Portierung der
 * v9.1/9.3-Change-/Anomalie-Erkennung (LiveNetworkScanner.detectChanges,
 * NetworkAnalyzer.detectAnomalies) als reiner, testbarer Kern.
 *
 * Das Scannen selbst übernehmen die bestehenden Module
 * (`WifiRttTriangulator`, `BleBeaconTriangulator`, `NetworkDataCollector`);
 * der Tracker konsumiert deren Ergebnisse und meldet Änderungen.
 */
class NetworkDeviceTracker(
    private val signalChangeThresholdDbm: Double = 10.0,
    private val anomalyDeviationDbm: Double = 20.0,
    private val anomalyWindow: Int = 10,
) {

    data class NetworkDevice(
        val id: String,
        val type: String,
        val name: String = "",
        val rssi: Double,
        val lastSeenMs: Long = System.currentTimeMillis(),
    )

    data class SignalChange(
        val id: String,
        val oldRssi: Double,
        val newRssi: Double,
        val diff: Double,
    )

    data class Anomaly(
        val id: String,
        val avgRssi: Double,
        val currentRssi: Double,
        val deviation: Double,
        val severity: String, // medium|high
    )

    data class ScanChanges(
        val added: List<NetworkDevice>,
        val removed: List<NetworkDevice>,
        val signalChanges: List<SignalChange>,
        val anomalies: List<Anomaly>,
        val deviceCount: Int,
    )

    private var devices: Map<String, NetworkDevice> = emptyMap()
    private val history = HashMap<String, ArrayDeque<Double>>()

    /** Verarbeitet einen Scan-Zyklus und liefert die Änderungen. */
    fun update(scan: List<NetworkDevice>): ScanChanges {
        val byId = scan.associateBy { it.id }

        val added = byId.values.filter { it.id !in devices }
        val removed = devices.values.filter { it.id !in byId }

        val signalChanges = byId.values.mapNotNull { device ->
            val cached = devices[device.id] ?: return@mapNotNull null
            val diff = abs(cached.rssi - device.rssi)
            if (diff > signalChangeThresholdDbm) {
                SignalChange(device.id, cached.rssi, device.rssi, diff)
            } else null
        }

        val anomalies = byId.values.mapNotNull { device ->
            val buffer = history.getOrPut(device.id) { ArrayDeque() }
            buffer.addLast(device.rssi)
            while (buffer.size > anomalyWindow) buffer.removeFirst()
            if (buffer.size < anomalyWindow) return@mapNotNull null
            val avg = buffer.average()
            val deviation = abs(device.rssi - avg)
            if (deviation > anomalyDeviationDbm) {
                Anomaly(
                    id = device.id,
                    avgRssi = avg,
                    currentRssi = device.rssi,
                    deviation = deviation,
                    severity = if (deviation > 1.5 * anomalyDeviationDbm) "high" else "medium",
                )
            } else null
        }

        devices = byId
        // Verwaiste Historien begrenzt aufräumen
        if (history.size > 1000) {
            val active = byId.keys
            history.keys.filter { it !in active }.forEach { history.remove(it) }
        }

        return ScanChanges(
            added = added,
            removed = removed,
            signalChanges = signalChanges,
            anomalies = anomalies,
            deviceCount = scan.size,
        )
    }

    fun knownDevices(): Map<String, NetworkDevice> = devices

    fun clear() {
        devices = emptyMap()
        history.clear()
    }
}
