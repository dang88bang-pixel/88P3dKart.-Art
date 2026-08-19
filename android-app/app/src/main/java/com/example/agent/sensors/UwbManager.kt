package com.example.agent.sensors

import android.content.Context
import android.util.Log
import com.example.agent.offline.NLOSGeometry
import java.util.concurrent.Executors

/**
 * UWB-Ranging & Micro-Doppler-Erfassung (Android 12+).
 *
 * Hinweis (Baubarkeit dieses Standes): Die Jetpack-UWB-Alpha-Bibliothek
 * (androidx.uwb) ist im verwendeten Maven-Repository nicht auflösbar.
 * Diese Klasse stellt daher die Phasen-/NLOS-Verarbeitungskette bereit und
 * nimmt Ranging-Daten über `onRawDistance` (Meter) oder `onPhase` (Radians)
 * entgegen. Der FiRa-Hardware-Driver (UwbRangingSession) kann später ohne
 * API-Änderung an dieser Stelle ergänzt werden.
 */
class UwbManager(private val context: Context) {

    companion object {
        private const val TAG = "UwbManager"
    }

    private val executor = Executors.newSingleThreadExecutor()

    /** Callback liefert die rohe Phase (Radians) eines Ranging-Ergebnisses. */
    var onPhase: ((Float) -> Unit)? = null

    /** Optionaler Callback für NLOS / Ghost-Geometrie (real data). */
    var onNlosEstimate: ((NLOSGeometry.NlosEstimate) -> Unit)? = null

    // Puffer für NLOS-Analyse (letzte ~1 Sekunde bei 20 Hz)
    private val phaseBuffer = mutableListOf<Float>()
    private val maxBuffer = 20

    /** Startet die Ranging-Kette (ohne FiRa-Stack: passiv, Daten via onRawDistance/onPhase). */
    fun startRanging(peerAddress: ByteArray = ByteArray(0), channel: Int = 9, preambleIndex: Int = 9) {
        Log.d(TAG, "UWB-Ranging aktiviert (Channel $channel) — FiRa-Driver nicht Teil dieses Standes")
    }

    /**
     * Nimmt eine Distanzmessung (Meter) entgegen und wandelt sie in die
     * Phasen-/NLOS-Kette um (Wavelength ~6,5 GHz).
     */
    fun onRawDistance(distanceMeters: Double) {
        val wavelength = 0.046f
        val phase = (((distanceMeters % wavelength.toDouble()) / wavelength) * (2 * Math.PI)).toFloat()
        onPhase?.invoke(phase)
        phaseBuffer.add(phase)
        if (phaseBuffer.size > maxBuffer) phaseBuffer.removeAt(0)
        val estimate = NLOSGeometry.fromPhaseBuffer(phaseBuffer)
        if (estimate.isLikelyNlos || estimate.confidence > 0.4f) {
            onNlosEstimate?.invoke(estimate)
            Log.d(TAG, "NLOS estimate: extra=${estimate.estimatedExtraDistance}m conf=${estimate.confidence}")
        }
    }

    fun stopRanging() {
        phaseBuffer.clear()
    }

    fun shutdown() {
        stopRanging()
        executor.shutdown()
    }
}
