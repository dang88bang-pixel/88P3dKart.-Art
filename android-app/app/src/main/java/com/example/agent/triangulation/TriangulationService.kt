package com.example.agent.triangulation

import android.content.Context
import android.util.Log
import com.example.agent.sensors.EkfFusion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Orchestrierung der Triangulationsquellen + Sensorfusion
 * (docs/TRIANGULATION.md §5.4):
 *
 * ```
 * Wi-Fi RTT (802.11mc)  ──┐
 * BLE-RSSI (2. Funk)    ──┼─► Frische + Mahalanobis-Gate ─► EKF ─► FUSED
 * Wi-Fi-Fingerprinting  ──┘        (EstimateGate)       (EkfFusion)
 * ```
 *
 * Fusionsstrategie:
 * 1. Wi-Fi RTT + BLE frisch **und** konsistent → invers-varianz-gewichteter
 *    Mittelwert (Quelle `FUSED`),
 * 2. sonst Wi-Fi RTT (genaueste Einzelquelle, Modus `FULL`),
 * 3. sonst BLE/Fingerprint (Modus `DEGRADED`),
 * 4. sonst keine Ausgabe (Modus `MINIMAL`, EKF rechnet mit IMU weiter).
 *
 * Jede fusionierte Schätzung wird als absoluter Positionsmesswert in den
 * bestehenden 6-DOF-EKF ([EkfFusion]) eingespeist — robuste Positionsschätzung
 * auch in anspruchsvollen Industrieumgebungen (AURA/3dxAgent-Konvention).
 */
class TriangulationService(
    context: Context,
    private val ekf: EkfFusion? = null,
) {

    companion object {
        private const val TAG = "Triangulation"
    }

    /** Betriebsmodus der Positionsbestimmung. */
    enum class Mode { FULL, DEGRADED, MINIMAL }

    private val wifiRtt = WifiRttTriangulator(context)
    private val bleTriangulator = BleBeaconTriangulator(StandardAndroidBleBackend(context))
    private val fingerprint = WifiRssiFingerprinter(context)

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _fused = MutableSharedFlow<PositionEstimate>(extraBufferCapacity = 64)
    val fused: SharedFlow<PositionEstimate> = _fused.asSharedFlow()

    private val _mode = MutableStateFlow(Mode.MINIMAL)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    @Volatile
    private var latestRtt: PositionEstimate? = null

    @Volatile
    private var latestBle: PositionEstimate? = null

    @Volatile
    private var latestFp: PositionEstimate? = null

    /** Fähigkeiten des Geräts (für UI/Status). */
    val wifiRttSupported: Boolean get() = wifiRtt.supported
    val ieee80211mcSupported: Boolean get() = wifiRtt.ieee80211mcSupported

    // ── Konfiguration ───────────────────────────────────────────────

    fun setWifiAnchors(anchors: List<WifiRttTriangulator.RttAnchor>) = wifiRtt.setAnchors(anchors)

    fun setBleAnchors(anchors: List<BleBeaconTriangulator.BeaconAnchor>) =
        bleTriangulator.setAnchors(anchors)

    fun addFingerprint(fp: WifiRssiFingerprinter.Fingerprint) = fingerprint.addFingerprint(fp)

    fun clearFingerprints() = fingerprint.clearFingerprints()

    // ── Lebenszyklus ────────────────────────────────────────────────

    /**
     * Startet alle gewünschten Quellen. Fingerprinting ist standardmäßig
     * deaktiviert (Scan-Drosselung); Quellen ohne Hardware-Unterstützung
     * melden sich selbst ab (No-op mit Log).
     */
    fun start(
        wifiRttEnabled: Boolean = true,
        bleEnabled: Boolean = true,
        fingerprintEnabled: Boolean = false,
    ) {
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        if (wifiRttEnabled) wifiRtt.start()
        if (bleEnabled) bleTriangulator.start()
        if (fingerprintEnabled) fingerprint.start()

        scope.launch { wifiRtt.estimates.collect { onEstimate(it, rtt = true, ble = false, fp = false) } }
        scope.launch { bleTriangulator.estimates.collect { onEstimate(it, rtt = false, ble = true, fp = false) } }
        scope.launch { fingerprint.estimates.collect { onEstimate(it, rtt = false, ble = false, fp = true) } }
        Log.i(TAG, "Triangulation aktiv (RTT=${wifiRtt.supported}, 802.11mc=${wifiRtt.ieee80211mcSupported})")
    }

    fun stop() {
        wifiRtt.stop()
        bleTriangulator.stop()
        fingerprint.stop()
        scope.cancel()
    }

    // ── Fusion ──────────────────────────────────────────────────────

    private fun onEstimate(estimate: PositionEstimate, rtt: Boolean, ble: Boolean, fp: Boolean) {
        if (rtt) latestRtt = estimate
        if (ble) latestBle = estimate
        if (fp) latestFp = estimate

        val now = System.currentTimeMillis()
        val rttFresh = EstimateGate.isFresh(latestRtt, now)
        val bleFresh = EstimateGate.isFresh(latestBle, now)
        val fpFresh = EstimateGate.isFresh(latestFp, now)

        val fused: PositionEstimate = when {
            rttFresh && bleFresh && EstimateGate.consistent(latestRtt!!, latestBle!!) ->
                EstimateGate.weightedMean(latestRtt!!, latestBle!!, now)
            rttFresh -> latestRtt!!
            bleFresh -> latestBle!!
            fpFresh -> latestFp!!
            else -> return
        }

        _fused.tryEmit(fused)
        _mode.value = when {
            rttFresh -> Mode.FULL
            bleFresh || fpFresh -> Mode.DEGRADED
            else -> Mode.MINIMAL
        }

        // Absoluter Messwert in den 6-DOF-EKF (R = σ², min. 0,04 m²)
        ekf?.updateAbsolutePosition(
            floatArrayOf(fused.x.toFloat(), fused.y.toFloat(), fused.z.toFloat()),
            (fused.accuracyM * fused.accuracyM).toFloat().coerceAtLeast(0.04f),
        )
    }
}
