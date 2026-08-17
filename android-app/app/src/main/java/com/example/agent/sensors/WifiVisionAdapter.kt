package com.example.agent.sensors

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * WiFi Vision Adapter — real, zero-extra-hardware WiFi-based sensing.
 *
 * Inspired by "WiFi Vision" research: uses existing WiFi RSSI fluctuations
 * and scan results to detect motion / rough presence through walls (very low power).
 *
 * This is a **real** lightweight layer on top of Android WifiManager.
 * Not as precise as mmWave/UWB, but excellent for redundancy or initial coarse map.
 *
 * All data comes from real system APIs.
 */
class WifiVisionAdapter(private val context: Context) {

    companion object {
        private const val TAG = "WifiVision"
    }

    private val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val _detections = MutableSharedFlow<WifiVisionDetection>(extraBufferCapacity = 20)
    val detections: SharedFlow<WifiVisionDetection> = _detections.asSharedFlow()

    data class WifiVisionDetection(
        val bssid: String,
        val ssid: String?,
        val rssi: Int,
        val motionScore: Float,   // 0..1 derived from RSSI variance over time
        val timestamp: Long = System.currentTimeMillis()
    )

    // Simple per-BSSID history for variance (real data)
    private val rssiHistory = mutableMapOf<String, MutableList<Int>>()
    private val maxHistory = 12

    /**
     * Call this periodically with fresh scan results (from BleTokenManager or a dedicated WiFi scan).
     * In real use you would trigger WifiManager.startScan() or listen to SCAN_RESULTS_AVAILABLE_ACTION.
     */
    fun processScanResults(scanResults: List<android.net.wifi.ScanResult>) {
        if (wifiManager == null) return

        for (result in scanResults) {
            val bssid = result.BSSID ?: continue
            val rssi = result.level

            val history = rssiHistory.getOrPut(bssid) { mutableListOf() }
            history.add(rssi)
            if (history.size > maxHistory) history.removeAt(0)

            val motion = if (history.size >= 4) {
                val mean = history.average().toFloat()
                val variance = history.map { (it - mean) * (it - mean) }.average().toFloat()
                (variance / 120f).coerceIn(0f, 1f)   // heuristic scaling
            } else 0f

            val detection = WifiVisionDetection(
                bssid = bssid,
                ssid = result.SSID,
                rssi = rssi,
                motionScore = motion
            )

            _detections.tryEmit(detection)

            if (motion > 0.4f) {
                Log.d(TAG, "WiFi motion hint: $bssid rssi=$rssi motion=${"%.2f".format(motion)}")
            }
        }
    }

    fun getCurrentMotionScore(bssid: String): Float {
        val h = rssiHistory[bssid] ?: return 0f
        if (h.size < 3) return 0f
        val mean = h.average().toFloat()
        val varSum = h.sumOf { (it - mean).toDouble() * (it - mean) }
        return (varSum / h.size / 120.0).coerceIn(0.0, 1.0).toFloat()
    }
}