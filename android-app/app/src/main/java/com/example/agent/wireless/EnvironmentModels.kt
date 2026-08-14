package com.example.agent.wireless

import kotlin.math.abs
import kotlin.math.pow

/**
 * Adaptive RSSI→Distanz-Umgebungsmodelle (docs/WIRELESS_MESH.md).
 *
 * Übernommen aus der v8.0/8.1-Spezifikation, **mit Korrekturen:**
 * - Der Spec-Parameter `txPower` wurde dort nie verwendet — entfällt.
 * - `environmentConfidence = 1 − bestScore` konnte negativ werden —
 *   hier begrenzt (0,3…1,0).
 * - Auswahlkriterium: mittlerer **relativer** Fehler je Umgebungs-Preset
 *   (Least-Squares über Messpaare RSSI ↔ bekannte Distanz).
 *
 * Die eigentliche Distanzformel nutzt das bereits vorhandene
 * [com.example.agent.triangulation.PathLossModel]; hier geht es um die
 * Umgebungsprädiktion und die RSSI-Konfidenzstaffelung.
 */
object EnvironmentModels {

    data class EnvironmentConfig(
        val name: String,
        val pathLossExponent: Double,
        val referenceRssiDbm: Double,
        val uncertaintyFactor: Double = 1.0,
    )

    val PRESETS: List<EnvironmentConfig> = listOf(
        EnvironmentConfig("FREIRAUM", 2.0, -50.0, 0.8),
        EnvironmentConfig("BUERO", 2.8, -55.0, 1.0),
        EnvironmentConfig("LAGER", 2.4, -55.0, 1.2),
        EnvironmentConfig("WERKSTATT", 3.2, -58.0, 1.5),
        EnvironmentConfig("STADT_AUSSEN", 3.5, -60.0, 2.0),
        EnvironmentConfig("INDUSTRIE", 3.8, -62.0, 2.5),
    )

    /** Messpaar (RSSI, bekannte Distanz) für die adaptive Umgebungsauswahl. */
    data class RssiMeasurement(val rssiDbm: Int, val actualDistanceM: Double)

    /** RSSI-Konfidenzstaffel (v8.0-Spec, übernommen). */
    fun rssiConfidence(rssiDbm: Int): Double = when {
        rssiDbm > -50 -> 0.95
        rssiDbm > -60 -> 0.85
        rssiDbm > -70 -> 0.70
        rssiDbm > -80 -> 0.50
        rssiDbm > -90 -> 0.30
        else -> 0.15
    }

    /** Distanzformel des Log-Distance-Modells für ein Preset. */
    fun distance(rssiDbm: Int, env: EnvironmentConfig): Double {
        val limited = rssiDbm.coerceIn(-100, -30)
        return 10.0.pow((env.referenceRssiDbm - limited) / (10.0 * env.pathLossExponent))
    }
}

/**
 * Wählt das bestpassende Umgebungs-Preset aus Messpaaren
 * (mittlerer relativer Fehler, begrenzte Konfidenz).
 */
class AdaptiveEnvironmentSelector(
    initial: EnvironmentModels.EnvironmentConfig =
        EnvironmentModels.PRESETS.first { it.name == "BUERO" },
    private val minSamples: Int = 10,
) {
    var current: EnvironmentModels.EnvironmentConfig = initial
        private set

    var confidence: Double = 0.5
        private set

    fun selectBest(measurements: List<EnvironmentModels.RssiMeasurement>): EnvironmentModels.EnvironmentConfig {
        if (measurements.size < minSamples) return current
        var bestScore = Double.MAX_VALUE
        var best = current
        for (env in EnvironmentModels.PRESETS) {
            var score = 0.0
            for (m in measurements) {
                if (m.actualDistanceM <= 0.0) continue
                val predicted = EnvironmentModels.distance(m.rssiDbm, env)
                val rel = (predicted - m.actualDistanceM) / m.actualDistanceM
                score += rel * rel
            }
            val avgError = score / measurements.size
            if (avgError < bestScore) {
                bestScore = avgError
                best = env
            }
        }
        if (bestScore < 0.8 && best.name != current.name) {
            current = best
            confidence = (1.0 - bestScore).coerceIn(0.3, 1.0)
        } else {
            confidence = (confidence * 0.95 + 0.05).coerceIn(0.3, 1.0)
        }
        return current
    }
}
