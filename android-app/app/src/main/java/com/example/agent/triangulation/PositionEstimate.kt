package com.example.agent.triangulation

import kotlin.math.sqrt

/**
 * Einheitliche Positionsschätzung aller Triangulationsquellen
 * (docs/TRIANGULATION.md §5.4) — Wi-Fi RTT, Wi-Fi-RSSI-Fingerprinting,
 * BLE-RSSI-Triangulation sowie die fusionierte Schätzung.
 */
data class PositionEstimate(
    val timestampMs: Long,
    val source: Source,
    val x: Double,
    val y: Double,
    val z: Double,
    /** 1σ-Positionsunsicherheit in Metern. */
    val accuracyM: Double,
    /** Konfidenz 0..1. */
    val confidence: Float,
    /** Freitext-Detail (z. B. Anzahl verwendeter Anker). */
    val detail: String = "",
) {
    enum class Source {
        WIFI_RTT,
        WIFI_FINGERPRINT,
        BLE_RSSI,
        FUSED,
    }
}

/**
 * Fusions-Logik: Frische-Prüfung, Mahalanobis-Konsistenztest und
 * invers-varianz-gewichteter Mittelwert (reine Logik, JVM-testbar).
 */
object EstimateGate {

    const val MAX_AGE_WIFI_RTT_MS = 5_000L
    const val MAX_AGE_BLE_MS = 3_000L
    const val MAX_AGE_FINGERPRINT_MS = 10_000L

    /** Ist die Schätzung für die Fusion noch gültig? */
    fun isFresh(estimate: PositionEstimate?, nowMs: Long): Boolean {
        if (estimate == null) return false
        val maxAgeMs = when (estimate.source) {
            PositionEstimate.Source.WIFI_RTT -> MAX_AGE_WIFI_RTT_MS
            PositionEstimate.Source.BLE_RSSI -> MAX_AGE_BLE_MS
            PositionEstimate.Source.WIFI_FINGERPRINT -> MAX_AGE_FINGERPRINT_MS
            PositionEstimate.Source.FUSED -> MAX_AGE_WIFI_RTT_MS
        }
        return nowMs - estimate.timestampMs <= maxAgeMs
    }

    /**
     * Mahalanobis-Konsistenztest: zwei Schätzungen gelten als konsistent, wenn
     * ihr Abstand ≤ k·√(σA² + σB²) ist (Standard k = 3).
     */
    fun consistent(a: PositionEstimate, b: PositionEstimate, k: Double = 3.0): Boolean {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        val sigma = k * sqrt(a.accuracyM * a.accuracyM + b.accuracyM * b.accuracyM)
        return dx * dx + dy * dy + dz * dz <= sigma * sigma
    }

    /**
     * Invers-varianz-gewichteter Mittelwert zweier konsistenter Schätzungen —
     * die genauere Quelle dominiert automatisch.
     */
    fun weightedMean(
        a: PositionEstimate,
        b: PositionEstimate,
        timestampMs: Long = System.currentTimeMillis(),
    ): PositionEstimate {
        val wa = 1.0 / (a.accuracyM * a.accuracyM + 1e-6)
        val wb = 1.0 / (b.accuracyM * b.accuracyM + 1e-6)
        val w = wa + wb
        return PositionEstimate(
            timestampMs = timestampMs,
            source = PositionEstimate.Source.FUSED,
            x = (wa * a.x + wb * b.x) / w,
            y = (wa * a.y + wb * b.y) / w,
            z = (wa * a.z + wb * b.z) / w,
            accuracyM = sqrt(1.0 / w),
            confidence = (a.confidence + b.confidence) / 2f,
            detail = "Fusion ${a.source}/${b.source}",
        )
    }
}
