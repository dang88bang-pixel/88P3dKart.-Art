package com.example.agent.triangulation

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Wi-Fi-RSSI-Fingerprinting (docs/TRIANGULATION.md §4):
 * Positionsbestimmung über den Vergleich des aktuellen RSSI-Vektors mit einer
 * vorab eingemessenen Fingerprint-Datenbank (gewichtet k-NN mit Gauß-Kern).
 * Typische Genauigkeit 1–3 m bei guter Abdeckung; dient als Fallback, wenn
 * RTT-fähige APs fehlen.
 *
 * Hinweis: Android drosselt Scan-Anfragen (im Hintergrund ~4 Scans/2 min);
 * der Scanner läuft daher mit moderatem Intervall und nur bei Bedarf.
 */
class WifiRssiFingerprinter(
    private val context: Context,
    /** Kernel-Breite σ für die Gauß-Gewichtung (RSSI-Distanz in dB). */
    private val sigma: Double = 8.0,
) {

    companion object {
        private const val TAG = "WifiFingerprint"

        /** Wartezeit nach startScan bis die Ergebnisse auslesbar sind. */
        private const val SCAN_SETTLE_MS = 1_500L

        /** Standard-Scan-Intervall (inkl. Settle-Zeit). */
        const val DEFAULT_SCAN_INTERVAL_MS = 5_000L
    }

    /** Ein eingemessener Referenzpunkt (RSSI-Vektor je BSSID). */
    data class Fingerprint(
        val id: String,
        val x: Double,
        val y: Double,
        val z: Double = 0.0,
        /** BSSID (uppercase) → RSSI [dBm]. */
        val rssi: Map<String, Int>,
    )

    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val fingerprints = ArrayList<Fingerprint>()
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _estimates = MutableSharedFlow<PositionEstimate>(extraBufferCapacity = 32)
    val estimates: SharedFlow<PositionEstimate> = _estimates.asSharedFlow()

    fun addFingerprint(fp: Fingerprint) = synchronized(fingerprints) { fingerprints.add(fp) }

    fun clearFingerprints() = synchronized(fingerprints) { fingerprints.clear() }

    fun fingerprintCount(): Int = synchronized(fingerprints) { fingerprints.size }

    /** Startet den Scan-Loop (Fingerprinting optional, Standard aus). */
    fun start(scanIntervalMs: Long = DEFAULT_SCAN_INTERVAL_MS) {
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            while (isActive) {
                requestScan()
                delay(SCAN_SETTLE_MS)
                val vector = collectScan()
                if (vector.isNotEmpty()) {
                    val estimate = localize(vector)
                    if (estimate != null) _estimates.tryEmit(estimate)
                }
                delay((scanIntervalMs - SCAN_SETTLE_MS).coerceAtLeast(500))
            }
        }
        Log.i(TAG, "Wi-Fi-Fingerprinting aktiv (Intervall ${scanIntervalMs}ms)")
    }

    fun stop() = scope.cancel()

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun requestScan() {
        runCatching { wifiManager?.startScan() }
            .onFailure { Log.w(TAG, "Scan-Anfrage fehlgeschlagen: ${it.message}") }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun collectScan(): Map<String, Int> {
        val results = runCatching { wifiManager?.scanResults }.getOrNull() ?: return emptyMap()
        return results
            .mapNotNull { r -> r.BSSID?.let { it.uppercase() to r.level } }
            .toMap()
    }

    /**
     * Gewichtetes k-NN über die Fingerprint-Datenbank:
     * RSSI-Vektoren werden über die gemeinsamen BSSIDs verglichen
     * (normalisierter euklidischer Abstand), Gewichtung mit Gauß-Kern.
     */
    fun localize(current: Map<String, Int>): PositionEstimate? {
        val fps = synchronized(fingerprints) { fingerprints.toList() }
        if (fps.isEmpty() || current.isEmpty()) return null

        data class Scored(val fp: Fingerprint, val distance: Double)

        val scored = fps.map { fp ->
            val common = fp.rssi.keys.intersect(current.keys)
            val distance = if (common.isEmpty()) {
                Double.POSITIVE_INFINITY
            } else {
                val sum = common.sumOf { bssid ->
                    val diff = (fp.rssi[bssid]!! - current[bssid]!!).toDouble()
                    diff * diff
                }
                sqrt(sum / common.size)
            }
            Scored(fp, distance)
        }
            .filter { it.distance.isFinite() }
            .sortedBy { it.distance }

        if (scored.isEmpty()) return null
        val k = min(3, scored.size)

        var weightSum = 0.0
        var wx = 0.0
        var wy = 0.0
        var wz = 0.0
        for (i in 0 until k) {
            val s = scored[i]
            val w = exp(-(s.distance * s.distance) / (2.0 * sigma * sigma))
            wx += w * s.fp.x
            wy += w * s.fp.y
            wz += w * s.fp.z
            weightSum += w
        }
        if (weightSum <= 0.0) return null

        val best = scored[0]
        val confidence = (1.0 / (1.0 + best.distance)).toFloat().coerceIn(0f, 1f)
        return PositionEstimate(
            timestampMs = System.currentTimeMillis(),
            source = PositionEstimate.Source.WIFI_FINGERPRINT,
            x = wx / weightSum,
            y = wy / weightSum,
            z = wz / weightSum,
            accuracyM = best.distance + 1.0,
            confidence = confidence,
            detail = "Fingerprint k=$k best=${best.fp.id} d=${String.format(java.util.Locale.US, "%.1f", best.distance)} dB",
        )
    }
}
