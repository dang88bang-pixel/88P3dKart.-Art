package com.example.agent.triangulation

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.rtt.RangingRequest
import android.net.wifi.rtt.RangingResult
import android.net.wifi.rtt.RangingResultCallback
import android.net.wifi.rtt.WifiRttManager
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
import java.util.concurrent.Executors

/**
 * Wi-Fi-RTT-Triangulation über `WifiRttManager` (IEEE 802.11mc Fine Time
 * Measurement, docs/TRIANGULATION.md §1/§2/§4).
 *
 * Wichtig: Wi-Fi 6 (802.11ax) impliziert **nicht** automatisch RTT/FTM —
 * Gerät (Feature `FEATURE_WIFI_RTT`, `is80211mcSupported`) und Access Points
 * müssen 802.11mc unterstützen. Die Unterstützung wird beim Start geprüft und
 * über [supported] bzw. [ieee80211mcSupported] exponiert.
 *
 * Ablauf: Ranging-Anfrage an die konfigurierten Anker-APs (max. 10 pro
 * Anfrage), Distanzen in mm + Standardabweichung → [TrilaterationEngine] →
 * Positionsschätzung. Typische Genauigkeit: 1–2 m bei Sichtlinie.
 *
 * Berechtigungen: `ACCESS_FINE_LOCATION` (zwingend); Android 13+ zusätzlich
 * `NEARBY_WIFI_DEVICES` (im Manifest deklariert). Android drosselt Ranging-
 * Anfragen — Intervall nicht unter ~1 s wählen.
 */
class WifiRttTriangulator(private val context: Context) {

    companion object {
        private const val TAG = "WifiRttTriangulator"

        /** Max. APs pro Ranging-Anfrage (Android-Limit). */
        private const val MAX_APS_PER_REQUEST = 10

        /** Standard-Intervall zwischen Ranging-Anfragen. */
        const val DEFAULT_RANGING_INTERVAL_MS = 2_000L
    }

    /** Anker-Access-Point mit bekannter Position. */
    data class RttAnchor(
        val id: String,
        val bssid: String,
        val x: Double,
        val y: Double,
        val z: Double = 0.0,
    )

    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val rttManager =
        context.applicationContext.getSystemService(Context.WIFI_RTT_RANGING_SERVICE) as? WifiRttManager

    /** RTT grundsätzlich verfügbar (Feature + Manager verfügbar)? */
    val supported: Boolean

    /** IEEE 802.11mc-Unterstützung des Geräts (API 31+). */
    val ieee80211mcSupported: Boolean

    init {
        val pm = context.packageManager
        val featureRtt = pm.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)
        ieee80211mcSupported = rttManager?.is80211mcSupported == true
        supported = featureRtt && rttManager != null && rttManager.isAvailable
    }

    private val executor = Executors.newSingleThreadExecutor()
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val anchorsByBssid = HashMap<String, RttAnchor>()

    private val _estimates = MutableSharedFlow<PositionEstimate>(extraBufferCapacity = 32)
    val estimates: SharedFlow<PositionEstimate> = _estimates.asSharedFlow()

    @Volatile
    private var running = false

    @Volatile
    private var pendingRequest = false

    /** Setzt die Anker-AP-Konfiguration (ersetzt bestehende). */
    fun setAnchors(anchors: List<RttAnchor>) {
        anchorsByBssid.clear()
        for (a in anchors) anchorsByBssid[a.bssid.uppercase()] = a
    }

    /** Startet den Ranging-Loop. No-op, wenn RTT nicht unterstützt wird. */
    fun start(intervalMs: Long = DEFAULT_RANGING_INTERVAL_MS) {
        if (!supported) {
            Log.w(TAG, "Wi-Fi RTT nicht verfügbar (Feature=${ieee80211mcSupported})")
            return
        }
        if (running) return
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        running = true
        scope.launch {
            while (isActive && running) {
                requestRanging()
                delay(intervalMs)
            }
        }
        Log.i(TAG, "Wi-Fi-RTT-Loop aktiv (Intervall ${intervalMs}ms)")
    }

    fun stop() {
        running = false
        scope.cancel()
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun requestRanging() {
        if (pendingRequest) return
        val scanResults = runCatching { wifiManager?.scanResults }.getOrNull() ?: return
        val candidates = scanResults
            .filter { it.BSSID != null && anchorsByBssid.containsKey(it.BSSID!!.uppercase()) }
        if (candidates.size < 3) return
        // Bekannte Anker bevorzugen, die sich als 802.11mc-Responder melden
        // (vgl. Plinzen/android-rttmanager-sample); APs ohne Flag bleiben
        // als Fallback im Pool (manche APs setzen das Bit nicht zuverlässig).
        val (responders, others) = candidates.partition { it.is80211mcResponder }
        val selected = (responders + others).take(MAX_APS_PER_REQUEST)

        val request = try {
            RangingRequest.Builder().addAccessPoints(selected).build()
        } catch (e: Exception) {
            Log.w(TAG, "RangingRequest-Fehler: ${e.message}")
            return
        }

        pendingRequest = true
        try {
            rttManager?.startRanging(
                request,
                executor,
                object : RangingResultCallback() {
                    override fun onRangingResults(results: List<RangingResult>) {
                        pendingRequest = false
                        handleResults(results)
                    }

                    override fun onRangingFailure(code: Int) {
                        pendingRequest = false
                        Log.w(TAG, "Ranging fehlgeschlagen (Code $code)")
                    }
                },
            )
        } catch (e: SecurityException) {
            pendingRequest = false
            Log.e(TAG, "ACCESS_FINE_LOCATION fehlt: ${e.message}")
        } catch (e: Exception) {
            pendingRequest = false
            Log.w(TAG, "startRanging-Ausnahme: ${e.message}")
        }
    }

    private fun handleResults(results: List<RangingResult>) {
        val distances = HashMap<String, Double>()
        val uncertainties = HashMap<String, Double>()
        for (r in results) {
            if (r.status != RangingResult.STATUS_SUCCESS) continue
            val bssid = r.macAddress?.uppercase() ?: continue
            if (!anchorsByBssid.containsKey(bssid)) continue
            distances[bssid] = r.distanceMm / 1000.0
            uncertainties[bssid] = maxOf(r.distanceStdDevMm / 1000.0, 0.1)
        }
        if (distances.size < 3) return

        val anchors = anchorsByBssid
            .filterKeys { distances.containsKey(it) }
            .values
            .toList()
        val estimate = TrilaterationEngine.solve(
            anchors = anchors.map { TrilaterationEngine.Anchor(it.id, it.x, it.y, it.z) },
            distances = distances,
            uncertainties = uncertainties,
            useZ = false,
        ) ?: return

        _estimates.tryEmit(
            PositionEstimate(
                timestampMs = System.currentTimeMillis(),
                source = PositionEstimate.Source.WIFI_RTT,
                x = estimate.x,
                y = estimate.y,
                z = estimate.z,
                accuracyM = maxOf(estimate.positionSigmaM, 0.5),
                confidence = estimate.confidence,
                detail = "RTT: ${distances.size} APs, RMS ${"%.2f".format(java.util.Locale.US, estimate.residualRmsM)} m",
            )
        )
    }
}
