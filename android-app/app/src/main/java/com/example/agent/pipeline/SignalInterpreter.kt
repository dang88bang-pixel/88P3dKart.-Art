package com.example.agent.pipeline

import com.example.agent.network.ClientSignal
import com.example.agent.network.SensorType

/** Interpretiertes Signal mit Qualitätsbewertung und Semantik. */
data class InterpretedSignal(
    val rawData: ClientSignal,
    val quality: Float,
    val semanticType: String,
    val context: Map<String, Any?>,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * Bewertet und interpretiert eingehende Client-Signale.
 */
class SignalInterpreter {

    /** Gesamtqualität aus SNR, Konfidenz, Latenz und Redundanz. */
    fun interpretSignal(rawData: ClientSignal): InterpretedSignal {
        val quality = evaluateQuality(rawData)
        val semanticType = extractSemantic(rawData)
        val context = enrichContext(rawData)
        return InterpretedSignal(rawData, quality, semanticType, context)
    }

    private fun evaluateQuality(signal: ClientSignal): Float {
        val snr = (signal.metadata["snr"] as? Number)?.toFloat() ?: 0f
        val confidence = (signal.metadata["confidence"] as? Number)?.toFloat() ?: 0.5f
        val latency = (System.currentTimeMillis() - signal.timestamp).coerceAtMost(1000L)

        val qSnr = (snr / 20f).coerceIn(0f, 1f)
        val qLatency = (1f - latency / 1000f).coerceIn(0f, 1f)
        // Redundanzprüfung hier vereinfacht (immer 1)
        return qSnr * 0.4f + confidence.coerceIn(0f, 1f) * 0.3f + qLatency * 0.2f + 0.1f
    }

    private fun extractSemantic(signal: ClientSignal): String = when (signal.sensorType) {
        SensorType.LIDAR -> "geometry"
        SensorType.MMWAVE -> {
            val v = (signal.payload["v"] as? Number)?.toFloat() ?: 0f
            if (v > 0.5f) "moving_person" else "stationary_person"
        }
        SensorType.BLE -> "beacon"
        SensorType.IMU -> "motion"
        SensorType.TEMPERATURE, SensorType.HUMIDITY, SensorType.AIR_QUALITY -> "environment"
        else -> "unknown"
    }

    private fun enrichContext(signal: ClientSignal): Map<String, Any?> = mapOf(
        "source" to signal.clientId,
        "device_type" to signal.deviceType.name,
        "timestamp" to signal.timestamp,
    ) + signal.metadata
}
