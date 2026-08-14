package com.example.agent.triangulation

import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * BLE-RSSI-Triangulation über den dedizierten Scan-Kanal
 * (docs/TRIANGULATION.md §4/§5).
 *
 * Pro Anker (Beacon mit bekannter Position) wird der RSSI über einen
 * EMA-Filter geglättet und mit einem individuell kalibrierbaren
 * [PathLossModel] in eine Distanz umgerechnet. Sobald ≥ 3 Anker frische
 * Messwerte liefern, löst die [TrilaterationEngine] die Position.
 *
 * Genauigkeit: typisch 3–8 m (stark multipath-abhängig) — dient als
 * Sekundärquelle neben Wi-Fi RTT und wird im [TriangulationService]
 * fusioniert.
 */
class BleBeaconTriangulator(
    private val backend: BleRadioBackend,
    private val scanMode: Int = ScanSettings.SCAN_MODE_LOW_LATENCY,
) {

    companion object {
        private const val TAG = "BleTriangulation"

        /** Frische-Schwelle für Anker-RSSI-Werte (2 s). */
        private const val ANCHOR_FRESHNESS_MS = 2_000L

        /** Basis-Unsicherheit der RSSI-Distanzschätzung in Metern. */
        private const val BASE_ACCURACY_M = 3.0
    }

    /** Anker (Beacon) mit bekannter Position und eigenem Pfadmodell. */
    data class BeaconAnchor(
        val id: String,
        val mac: String,
        val x: Double,
        val y: Double,
        val z: Double = 0.0,
        val model: PathLossModel = PathLossModel(),
    )

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val anchorsByMac = HashMap<String, BeaconAnchor>()
    private val smoothers = HashMap<String, RssiSmoother>()
    private val rssiByMac = HashMap<String, Double>()
    private val rssiTimeByMac = HashMap<String, Long>()

    private val _estimates = MutableSharedFlow<PositionEstimate>(extraBufferCapacity = 32)
    val estimates: SharedFlow<PositionEstimate> = _estimates.asSharedFlow()

    /** Aktuelle geglättete RSSI-Werte je Anker-MAC (für UI/Diagnose). */
    private val _anchorRssi = MutableSharedFlow<Map<String, Double>>(extraBufferCapacity = 32)
    val anchorRssi: SharedFlow<Map<String, Double>> = _anchorRssi.asSharedFlow()

    @Volatile
    private var running = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleScanResult(result)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "BLE-Scan fehlgeschlagen: $errorCode")
        }
    }

    /** Setzt die Anker-Konfiguration (ersetzt bestehende). */
    fun setAnchors(anchors: List<BeaconAnchor>) {
        anchorsByMac.clear()
        for (a in anchors) {
            anchorsByMac[a.mac.uppercase()] = a
            smoothers.getOrPut(a.mac.uppercase()) { RssiSmoother() }
        }
    }

    fun start() {
        if (running) return
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val settings = ScanSettings.Builder()
            .setScanMode(scanMode)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .build()
        val ok = backend.startScan(scanCallback, settings, null)
        running = ok
        Log.i(TAG, "BLE-Triangulation gestartet (Backend verfügbar: ${backend.available})")
    }

    fun stop() {
        running = false
        backend.stopScan(scanCallback)
        scope.cancel()
    }

    private fun handleScanResult(result: ScanResult) {
        val mac = result.device.address.uppercase()
        val anchor = anchorsByMac[mac] ?: return
        val rssi = result.rssi

        val smoother = smoothers[mac] ?: RssiSmoother().also { smoothers[mac] = it }
        val smoothed = smoother.smooth(mac, rssi)

        scope.launch {
            rssiByMac[mac] = smoothed
            rssiTimeByMac[mac] = System.currentTimeMillis()
            _anchorRssi.tryEmit(HashMap(rssiByMac))
            evaluate()
        }
    }

    /** ≥ 3 frische Anker → Distanzen → Trilateration. */
    private fun evaluate() {
        val now = System.currentTimeMillis()
        val fresh = anchorsByMac.filter { (mac, _) ->
            val t = rssiTimeByMac[mac] ?: return@filter false
            now - t <= ANCHOR_FRESHNESS_MS
        }
        if (fresh.size < 3) return

        val distances = HashMap<String, Double>()
        for ((mac, anchor) in fresh) {
            val d = anchor.model.distanceFromRssi(rssiByMac[mac] ?: continue)
            if (d.isFinite() && d in 0.05..100.0) distances[anchor.id] = d
        }
        if (distances.size < 3) return

        val anchors = fresh.values.toList()
        val estimate = TrilaterationEngine.solve(
            anchors = anchors.map { TrilaterationEngine.Anchor(it.id, it.x, it.y, it.z) },
            distances = distances,
            useZ = anchors.any { it.z != 0.0 },
        ) ?: return

        val meanDistance = distances.values.average()
        _estimates.tryEmit(
            PositionEstimate(
                timestampMs = now,
                source = PositionEstimate.Source.BLE_RSSI,
                x = estimate.x,
                y = estimate.y,
                z = estimate.z,
                accuracyM = (BASE_ACCURACY_M + 0.15 * meanDistance).coerceAtMost(12.0),
                confidence = estimate.confidence,
                detail = "BLE: ${distances.size} Anker, Ø-Distanz ${"%.1f".format(java.util.Locale.US, meanDistance)} m",
            )
        )
    }
}
