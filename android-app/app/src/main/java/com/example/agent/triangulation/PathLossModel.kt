package com.example.agent.triangulation

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * Log-Distance-Path-Loss-Modell für RSSI-basierte Distanzschätzung
 * (docs/TRIANGULATION.md §5.2):
 *
 *     d = 10^((RSSI₀ − RSSI) / (10·n))
 *
 * RSSI₀ = Referenzpegel bei 1 m, n = Pfadverlustexponent
 * (2 = Freiraum, 2,7–4 = typisch Indoor/Industrie).
 *
 * Die Kalibrierung [calibrate] bestimmt beide Parameter per
 * linearer Regression aus (Distanz, RSSI)-Messpaaren.
 */
class PathLossModel(
    /** Referenz-RSSI bei 1 m [dBm]. */
    var referenceRssiDbm: Double = -59.0,
    /** Pfadverlustexponent n. */
    var pathLossExponent: Double = 2.8,
) {

    /** RSSI → Distanz in Metern; NaN bei ungültigem Pegel. */
    fun distanceFromRssi(rssiDbm: Double): Double {
        if (rssiDbm.isNaN() || rssiDbm > -1.0) return Double.NaN
        return 10.0.pow((referenceRssiDbm - rssiDbm) / (10.0 * pathLossExponent))
    }

    /** Distanz → erwarteter RSSI [dBm]. */
    fun rssiFromDistance(distanceM: Double): Double =
        referenceRssiDbm - 10.0 * pathLossExponent * log10(max(distanceM, 1e-3))

    companion object {

        /** Kalibrierungsergebnis der linearen Regression. */
        data class Calibration(
            val referenceRssiDbm: Double,
            val pathLossExponent: Double,
            /** Bestimmtheitsmaß R² der Regression (0..1). */
            val rSquared: Double,
        )

        /**
         * Kalibriert das Modell aus (Distanz [m], RSSI [dBm])-Messpaaren.
         * Regression: RSSI = RSSI₀ − 10n·log10(d).
         * @return [Calibration] oder null bei < 3 Messpaaren/Entartung
         */
        fun calibrate(samples: List<Pair<Double, Double>>): Calibration? {
            if (samples.size < 3) return null
            val n = samples.size
            var sumX = 0.0
            var sumY = 0.0
            var sumXX = 0.0
            var sumXY = 0.0
            var sumYY = 0.0
            for ((d, rssi) in samples) {
                if (!d.isFinite() || d <= 0.0 || !rssi.isFinite()) return null
                val x = 10.0 * log10(max(d, 0.01))
                sumX += x
                sumY += rssi
                sumXX += x * x
                sumXY += x * rssi
                sumYY += rssi * rssi
            }
            val denom = n * sumXX - sumX * sumX
            if (abs(denom) < 1e-12) return null
            // x-Achse = 10·log10(d) → Steigung = −n (dB je Dekade)
            val slope = (n * sumXY - sumX * sumY) / denom
            val intercept = (sumY - slope * sumX) / n           // = RSSI₀

            val r = (n * sumXY - sumX * sumY) /
                kotlin.math.sqrt((n * sumXX - sumX * sumX) * (n * sumYY - sumY * sumY))
            val rSquared = (r * r).coerceIn(0.0, 1.0)

            return Calibration(
                referenceRssiDbm = intercept,
                pathLossExponent = max(0.1, -slope),
                rSquared = rSquared,
            )
        }
    }
}

/**
 * RSSI-Filter je Sender-MAC — glättet BLE-/Wi-Fi-RSSI-Jitter vor der
 * Distanzschätzung. Implementierungen: EMA ([RssiSmoother]),
 * Median ([RssiMedianFilter]), 1D-Kalman ([RssiKalmanFilter]).
 */
interface RssiFilter {
    /** Liefert den gefilterten RSSI-Wert für [key]. */
    fun smooth(key: String, rssiDbm: Int): Double

    /** Zuletzt gefilterter Wert (oder null). */
    fun value(key: String): Double?

    fun clear(key: String)
}

/** Exponentiell gleitender Mittelwert (EMA). */
class RssiSmoother(private val alpha: Float = 0.6f) : RssiFilter {

    private val values = HashMap<String, Double>()

    override fun smooth(key: String, rssiDbm: Int): Double {
        val prev = values[key] ?: rssiDbm.toDouble()
        val next = alpha * rssiDbm + (1f - alpha) * prev
        values[key] = next
        return next
    }

    override fun value(key: String): Double? = values[key]

    override fun clear(key: String) {
        values.remove(key)
    }

    fun clearAll() = values.clear()
}

/**
 * Gleitender Median-Filter je MAC — unterdrückt RSSI-Spikes (Multipath-
 * Ausreißer); vgl. MDPI Sensors 2025, 25(9):2834 (Median + MAF).
 */
class RssiMedianFilter(private val window: Int = 5) : RssiFilter {

    private val buffers = HashMap<String, ArrayDeque<Int>>()

    override fun smooth(key: String, rssiDbm: Int): Double {
        val buffer = buffers.getOrPut(key) { ArrayDeque() }
        buffer.addLast(rssiDbm)
        while (buffer.size > window) buffer.removeFirst()
        val sorted = buffer.sorted()
        return if (sorted.size % 2 == 1) {
            sorted[sorted.size / 2].toDouble()
        } else {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        }
    }

    override fun value(key: String): Double? = buffers[key]?.lastOrNull()?.toDouble()

    override fun clear(key: String) {
        buffers.remove(key)
    }
}

/**
 * 1D-Kalman-Filter je MAC zur RSSI-Glättung (Zustandsmodell: RSSI konstant,
 * A = 1, H = 1, Prozessrauschen q, Messrauschen r) — dämpft kurzzeitige
 * Sprünge; vgl. avibn/indoor-positioning-trilateration, MDPI Sensors 2017.
 */
class RssiKalmanFilter(
    private val q: Double = 4.0,
    private val r: Double = 16.0,
) : RssiFilter {

    private data class State(val estimate: Double, val covariance: Double)

    private val states = HashMap<String, State>()

    override fun smooth(key: String, rssiDbm: Int): Double {
        val prev = states[key] ?: State(rssiDbm.toDouble(), 1.0)
        // Predict
        val pPred = prev.covariance + q
        // Update
        val gain = pPred / (pPred + r)
        val estimate = prev.estimate + gain * (rssiDbm - prev.estimate)
        val covariance = (1.0 - gain) * pPred
        states[key] = State(estimate, covariance)
        return estimate
    }

    override fun value(key: String): Double? = states[key]?.estimate

    override fun clear(key: String) {
        states.remove(key)
    }
}
