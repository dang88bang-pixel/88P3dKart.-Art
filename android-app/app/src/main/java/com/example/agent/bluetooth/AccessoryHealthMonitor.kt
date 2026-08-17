package com.example.agent.bluetooth

import com.example.agent.network.ClientHealthEvaluator
import com.example.agent.network.ClientRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Überwacht Gesundheit aller Bluetooth-Zubehörgeräte.
 *
 * - Kombiniert RSSI-basierte Distanz, Batterie, Alter, Datenqualität zu Health-Score
 * - Nutzt ClientHealthEvaluator für einheitliches Regelwerk
 * - Meldet CRITICAL Geräte (low battery, expired, SOS)
 */
class AccessoryHealthMonitor(
    private val clientRegistry: ClientRegistry? = null,
) {
    private val healthEvaluator = ClientHealthEvaluator()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    data class AccessoryHealth(
        val mac: String,
        val type: BluetoothAccessoryType,
        val status: String, // HEALTHY / DEGRADED / CRITICAL / LOST
        val score: Float,
        val details: Map<String, Float>,
        val warnings: List<String>,
        val lastSeenAgoMs: Long,
    )

    private val _healthStates = MutableStateFlow<Map<String, AccessoryHealth>>(emptyMap())
    val healthStates: StateFlow<Map<String, AccessoryHealth>> = _healthStates.asStateFlow()

    private val accessoriesSnapshot = mutableMapOf<String, BluetoothAccessory>()

    fun updateAccessories(list: List<BluetoothAccessory>) {
        for (acc in list) {
            accessoriesSnapshot[acc.macAddress] = acc
        }
        // Veraltete entfernen (älter als 60s)
        val now = System.currentTimeMillis()
        accessoriesSnapshot.entries.removeIf { now - it.value.lastSeenMs > 60_000 }

        val newHealth = mutableMapOf<String, AccessoryHealth>()
        for ((mac, acc) in accessoriesSnapshot) {
            val registration = try { clientRegistry?.getClient(mac) ?: acc.toClientRegistration() } catch (_: Exception) { acc.toClientRegistration() }
            val clientHealth = healthEvaluator.evaluate(registration)

            val warnings = mutableListOf<String>()
            if (acc.batteryLevel < 15) warnings.add("LOW_BATTERY")
            if (acc.isExpired) warnings.add("EXPIRED")
            if (acc.rssi < -85) warnings.add("WEAK_SIGNAL")
            if (acc.isSosActive) warnings.add("SOS_ACTIVE")
            if (acc.isFallDetected) warnings.add("FALL_DETECTED")
            if (acc.estimatedDistanceM != null && acc.estimatedDistanceM!! > 15) warnings.add("OUT_OF_RANGE")

            val status = when {
                warnings.contains("SOS_ACTIVE") || warnings.contains("FALL_DETECTED") -> "CRITICAL"
                acc.isExpired -> "LOST"
                else -> clientHealth.status
            }

            newHealth[mac] = AccessoryHealth(
                mac = mac,
                type = acc.type,
                status = status,
                score = clientHealth.score * if (acc.isExpired) 0.2f else 1f,
                details = clientHealth.details + mapOf(
                    "rssi_norm" to ((acc.rssi + 100) / 70f).coerceIn(0f, 1f),
                    "distance" to (1f - (acc.estimatedDistanceM ?: 0f) / 20f).coerceIn(0f, 1f),
                ),
                warnings = warnings,
                lastSeenAgoMs = acc.ageMs,
            )
        }
        _healthStates.value = newHealth
    }

    fun getHealth(mac: String): AccessoryHealth? = _healthStates.value[mac]

    fun getCriticalAccessories(): List<AccessoryHealth> =
        _healthStates.value.values.filter { it.status == "CRITICAL" || it.warnings.isNotEmpty() }.toList()

    fun getLostAccessories(): List<AccessoryHealth> =
        _healthStates.value.values.filter { it.status == "LOST" }.toList()

    fun startPeriodicCleanup(intervalMs: Long = 10_000L) {
        scope.launch {
            while (true) {
                delay(intervalMs)
                // re-evaluate with current time → expired detection
                updateAccessories(accessoriesSnapshot.values.toList())
            }
        }
    }
}
