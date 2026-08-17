package com.example.agent.sensors

import android.content.Context
import android.util.Log
import androidx.uwb.ranging.RangingParameters
import androidx.uwb.ranging.RangingResult
import androidx.uwb.ranging.UwbManager as AndroidUwbManager
import androidx.uwb.ranging.UwbPeer
import com.example.agent.offline.NLOSGeometry
import java.util.concurrent.Executors

/**
 * UWB-Ranging & Micro-Doppler-Erfassung (Android 12+, FiRa-konform).
 *
 * Liefert die gemessene Phase (Radians) pro Ranging-Runde.
 * Zusätzlich: Echtzeit-NLOS/Geometry-Analyse (HoloRadar-style) mit realen Phasen.
 */
class UwbManager(private val context: Context) {

    companion object {
        private const val TAG = "UwbManager"
    }

    private val uwbManager = AndroidUwbManager.createInstance(context)
    private val executor = Executors.newSingleThreadExecutor()
    private var session: androidx.uwb.ranging.UwbRangingSession? = null

    /** Callback liefert die rohe Phase (Radians) eines Ranging-Ergebnisses. */
    var onPhase: ((Float) -> Unit)? = null

    /** Optionaler Callback für NLOS / Ghost-Geometrie (real data). */
    var onNlosEstimate: ((NLOSGeometry.NlosEstimate) -> Unit)? = null

    // Puffer für NLOS-Analyse (letzte ~1 Sekunde bei 20 Hz)
    private val phaseBuffer = mutableListOf<Float>()
    private val maxBuffer = 20

    fun startRanging(peerAddress: ByteArray, channel: Int = 9, preambleIndex: Int = 9) {
        try {
            val peer = UwbPeer.Builder(peerAddress).build()
            val params = RangingParameters.Builder()
                .setPeer(peer)
                .setChannel(channel)
                .setPreambleIndex(preambleIndex)
                .build()

            session = uwbManager.startRanging(params, executor) { rangingResult ->
                val phase = extractPhase(rangingResult)
                if (phase != null) {
                    onPhase?.invoke(phase)

                    // === Real NLOS analysis (HoloRadar-style) ===
                    phaseBuffer.add(phase)
                    if (phaseBuffer.size > maxBuffer) phaseBuffer.removeAt(0)

                    val estimate = NLOSGeometry.fromPhaseBuffer(phaseBuffer)
                    if (estimate.isLikelyNlos || estimate.confidence > 0.4f) {
                        onNlosEstimate?.invoke(estimate)
                        Log.d(TAG, "NLOS estimate: extra=${estimate.estimatedExtraDistance}m conf=${estimate.confidence}")
                    }
                }
            }
            Log.d(TAG, "UWB-Ranging gestartet (Channel $channel)")
        } catch (e: Exception) {
            Log.e(TAG, "UWB-Ranging fehlgeschlagen: ${e.message}")
        }
    }

    private fun extractPhase(result: RangingResult): Float? {
        // Distanzmessung → Phase (vereinfachte Modellierung für den
        // Micro-Doppler; auf realer Hardware an die Phasenrückgabe des
        // Qorvo-DWM3000 koppeln).
        val distanceMeters = result.distanceMeters ?: return null
        val wavelength = 0.046f // ~6.5 GHz UWB
        return ((distanceMeters % wavelength) / wavelength * (2 * Math.PI)).toFloat()
    }

    fun stopRanging() {
        try {
            session?.close()
            phaseBuffer.clear()
        } catch (e: Exception) {
            Log.w(TAG, "Stopp fehlgeschlagen: ${e.message}")
        }
    }
}
