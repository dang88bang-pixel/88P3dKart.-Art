package com.example.agent.sensors

import android.content.Context
import android.util.Log
import androidx.uwb.ranging.RangingParameters
import androidx.uwb.ranging.RangingResult
import androidx.uwb.ranging.UwbManager as AndroidUwbManager
import androidx.uwb.ranging.UwbPeer
import java.util.concurrent.Executors

/**
 * UWB-Ranging & Micro-Doppler-Erfassung (Android 12+, FiRa-konform).
 *
 * Liefert die gemessene Phase (Radians) pro Ranging-Runde, die der
 * Edge-Agent zur FFT-basierten Atemfrequenz-Erkennung (0.15–0.6 Hz) nutzt.
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
                if (phase != null) onPhase?.invoke(phase)
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
        } catch (e: Exception) {
            Log.w(TAG, "Stopp fehlgeschlagen: ${e.message}")
        }
    }
}
