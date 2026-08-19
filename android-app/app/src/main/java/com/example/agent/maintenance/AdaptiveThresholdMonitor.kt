package com.example.agent.maintenance

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Adaptive Schwellwert-Überwachung (Anomaly Detector, docs/SERVICE_WORKER.md).
 *
 * Portierung der sinnvollen Kernlogik aus der v10.2.0-ServiceWorker-Spezifikation
 * (Schwellwerte, Spike-, Trend-, Kontextanalyse, selbstlernende Schwellwerte) —
 * **mit zwei Korrekturen gegenüber dem Original:**
 *
 * 1. **Richtungsbewusstsein:** die Spec prüft `value > threshold.critical`, was
 *    für Metriken mit „niedriger = schlechter" (Batterie, RSSI) falsch ist
 *    (Batterie 10 % löst weder Warning 30 noch Critical 15 aus; RSSI −85 wird
 *    nicht kritisch). Hier entscheidet [MetricConfig.direction].
 * 2. **Trendrichtung:** aufwärts = schlecht gilt nur für HIGHER_IS_WORSE;
 *    bei LOWER_IS_WORSE ist ein fallender Trend der Alarmfall.
 *
 * Als reines Kotlin-Modul (ohne Android-Abhängigkeiten) JVM-testbar; im
 * Android-Betrieb wird die Ausführung über WorkManager/Coroutines gesteuert.
 */
class AdaptiveThresholdMonitor(
    private val learningMode: Boolean = true,
    private val historyLimit: Int = 1000,
    private val spikeSigma: Double = 3.0,
    private val trendSlopeThreshold: Double = 0.5,
    private val learningSamplesNeeded: Int = 50,
    private val spikeSamplesNeeded: Int = 20,
) {

    enum class MetricDirection { HIGHER_IS_WORSE, LOWER_IS_WORSE }

    enum class Severity { INFO, WARNING, HIGH, CRITICAL }

    data class MetricConfig(
        val warningThreshold: Double,
        val criticalThreshold: Double,
        val direction: MetricDirection,
        val unit: String = "",
    )

    data class MetricAnomaly(
        val type: Type,
        val metric: String,
        val value: Double,
        val threshold: Double? = null,
        val severity: Severity,
        val message: String,
        val context: Map<String, Double> = emptyMap(),
    ) {
        enum class Type { THRESHOLD, SPIKE, TREND, CONTEXTUAL }
    }

    private val configs = LinkedHashMap<String, MetricConfig>()
    private val history = HashMap<String, ArrayDeque<Double>>()

    init {
        // Defaults aus der v10.2.0-Spezifikation (richtungskorrigiert)
        configure("cpu", MetricConfig(70.0, 90.0, MetricDirection.HIGHER_IS_WORSE, "%"))
        configure("memory", MetricConfig(80.0, 95.0, MetricDirection.HIGHER_IS_WORSE, "%"))
        configure("battery", MetricConfig(30.0, 15.0, MetricDirection.LOWER_IS_WORSE, "%"))
        configure("temperature", MetricConfig(40.0, 60.0, MetricDirection.HIGHER_IS_WORSE, "°C"))
        configure("latency", MetricConfig(100.0, 300.0, MetricDirection.HIGHER_IS_WORSE, "ms"))
        configure("packetLoss", MetricConfig(1.0, 5.0, MetricDirection.HIGHER_IS_WORSE, "%"))
        configure("rssi", MetricConfig(-75.0, -85.0, MetricDirection.LOWER_IS_WORSE, "dBm"))
    }

    fun configure(metric: String, config: MetricConfig) {
        configs[metric] = config
    }

    /** Analysiert einen Datenpunkt und liefert alle erkannten Anomalien. */
    fun analyze(dataPoint: Map<String, Double>): List<MetricAnomaly> {
        val anomalies = ArrayList<MetricAnomaly>()

        for ((metric, value) in dataPoint) {
            if (!value.isFinite()) continue
            val config = configs[metric] ?: continue

            // 1) Schwellwerte (richtungskorrekt)
            anomalies.addAll(checkThreshold(metric, value, config))

            // 2) Spike (3σ)
            anomalies.addAll(checkSpike(metric, value))

            // 3) Trend (lineare Regression)
            anomalies.addAll(checkTrend(metric, value, config))

            // 4) Historie aktualisieren + Schwellwerte adaptieren
            updateHistory(metric, value)
        }

        // 5) Kontextuelle Anomalien
        anomalies.addAll(checkContext(dataPoint))

        if (learningMode) adaptThresholds()

        return anomalies
    }

    fun historySize(metric: String): Int = history[metric]?.size ?: 0

    fun currentThresholds(): Map<String, MetricConfig> = HashMap(configs)

    // ── Prüfungen ────────────────────────────────────────────────────

    private fun checkThreshold(metric: String, value: Double, config: MetricConfig): List<MetricAnomaly> {
        val result = ArrayList<MetricAnomaly>(2)
        val (isCritical, isWarning) = when (config.direction) {
            MetricDirection.HIGHER_IS_WORSE ->
                Pair(value > config.criticalThreshold, value > config.warningThreshold)
            MetricDirection.LOWER_IS_WORSE ->
                Pair(value < config.criticalThreshold, value < config.warningThreshold)
        }
        if (isCritical) {
            result.add(
                MetricAnomaly(
                    type = MetricAnomaly.Type.THRESHOLD,
                    metric = metric,
                    value = value,
                    threshold = config.criticalThreshold,
                    severity = Severity.CRITICAL,
                    message = "${metric.uppercase()} $value ${config.unit} überschreitet " +
                        "kritischen Schwellwert (${config.criticalThreshold})",
                )
            )
        } else if (isWarning) {
            result.add(
                MetricAnomaly(
                    type = MetricAnomaly.Type.THRESHOLD,
                    metric = metric,
                    value = value,
                    threshold = config.warningThreshold,
                    severity = Severity.WARNING,
                    message = "${metric.uppercase()} $value ${config.unit} überschreitet " +
                        "Warnschwellwert (${config.warningThreshold})",
                )
            )
        }
        return result
    }

    private fun checkSpike(metric: String, value: Double): List<MetricAnomaly> {
        val hist = history[metric] ?: return emptyList()
        if (hist.size < spikeSamplesNeeded) return emptyList()

        val mean = hist.average()
        val variance = hist.sumOf { (it - mean) * (it - mean) } / hist.size
        val std = sqrt(variance)
        if (std <= 0.0) return emptyList()

        val z = abs(value - mean) / std
        if (z > spikeSigma) {
            return listOf(
                MetricAnomaly(
                    type = MetricAnomaly.Type.SPIKE,
                    metric = metric,
                    value = value,
                    severity = Severity.HIGH,
                    message = "${metric.uppercase()} zeigt Spike: $value (Mittelwert " +
                        "${"%.2f".format(java.util.Locale.US, mean)}, σ " +
                        "${"%.2f".format(java.util.Locale.US, std)})",
                    context = mapOf("mean" to mean, "stdDev" to std, "z" to z),
                )
            )
        }
        return emptyList()
    }

    private fun checkTrend(
        metric: String,
        value: Double,
        config: MetricConfig,
    ): List<MetricAnomaly> {
        val hist = history[metric] ?: return emptyList()
        if (hist.size < spikeSamplesNeeded) return emptyList()

        val recent = hist.toList().takeLast(spikeSamplesNeeded)
        val slope = linearSlope(recent)

        // Richtungskorrekt: bei HIGHER_IS_WORSE ist Aufwärts alarmierend,
        // bei LOWER_IS_WORSE Abwärts.
        val alarming = when (config.direction) {
            MetricDirection.HIGHER_IS_WORSE -> slope > trendSlopeThreshold
            MetricDirection.LOWER_IS_WORSE -> slope < -trendSlopeThreshold
        }
        if (alarming) {
            return listOf(
                MetricAnomaly(
                    type = MetricAnomaly.Type.TREND,
                    metric = metric,
                    value = value,
                    severity = Severity.WARNING,
                    message = "${metric.uppercase()} zeigt " +
                        if (config.direction == MetricDirection.HIGHER_IS_WORSE) "steigenden" else "fallenden" +
                        " Trend (Steigung ${"%.2f".format(java.util.Locale.US, slope)}/Sample)",
                    context = mapOf("slope" to slope),
                )
            )
        }
        return emptyList()
    }

    private fun checkContext(data: Map<String, Double>): List<MetricAnomaly> {
        val result = ArrayList<MetricAnomaly>(2)

        val cpu = data["cpu"]
        val temp = data["temperature"]
        if (cpu != null && temp != null && cpu > 80.0 && temp > 50.0) {
            result.add(
                MetricAnomaly(
                    type = MetricAnomaly.Type.CONTEXTUAL,
                    metric = "cpu+temperature",
                    value = cpu,
                    severity = Severity.CRITICAL,
                    message = "Hohe CPU ($cpu %) bei erhöhter Temperatur ($temp °C)",
                    context = mapOf("cpu" to cpu, "temperature" to temp),
                )
            )
        }

        val latency = data["latency"]
        val loss = data["packetLoss"]
        if (latency != null && loss != null && latency > 200.0 && loss > 2.0) {
            result.add(
                MetricAnomaly(
                    type = MetricAnomaly.Type.CONTEXTUAL,
                    metric = "network",
                    value = latency,
                    severity = Severity.HIGH,
                    message = "Netzwerk-Degradation (Latenz $latency ms, Paketverlust $loss %)",
                    context = mapOf("latency" to latency, "packetLoss" to loss),
                )
            )
        }
        return result
    }

    // ── Historie & Lernmodus ─────────────────────────────────────────

    private fun updateHistory(metric: String, value: Double) {
        val buffer = history.getOrPut(metric) { ArrayDeque() }
        buffer.addLast(value)
        while (buffer.size > historyLimit) buffer.removeFirst()
    }

    /** Selbstlernende Schwellwerte: mean ± 1,5σ (Warning) bzw. ± 3σ (Critical). */
    private fun adaptThresholds() {
        for ((metric, config) in configs) {
            val hist = history[metric] ?: continue
            if (hist.size < learningSamplesNeeded) continue

            val mean = hist.average()
            val variance = hist.sumOf { (it - mean) * (it - mean) } / hist.size
            val std = sqrt(variance)

            when (config.direction) {
                MetricDirection.HIGHER_IS_WORSE -> {
                    val newWarning = mean + 1.5 * std
                    val newCritical = mean + 3.0 * std
                    if (newWarning < config.criticalThreshold * 0.8) {
                        configs[metric] = config.copy(
                            warningThreshold = max(config.warningThreshold * 0.9, newWarning),
                        )
                    }
                    if (newCritical < config.criticalThreshold * 1.2) {
                        configs[metric] = configs[metric]!!.copy(
                            criticalThreshold = max(configs[metric]!!.criticalThreshold * 0.9, newCritical),
                        )
                    }
                }
                MetricDirection.LOWER_IS_WORSE -> {
                    val newWarning = mean - 1.5 * std
                    val newCritical = mean - 3.0 * std
                    if (newWarning > config.criticalThreshold * 1.2) {
                        configs[metric] = config.copy(
                            warningThreshold = min(config.warningThreshold * 1.1, newWarning),
                        )
                    }
                    if (newCritical > config.criticalThreshold * 0.8) {
                        configs[metric] = configs[metric]!!.copy(
                            criticalThreshold = min(configs[metric]!!.criticalThreshold * 1.1, newCritical),
                        )
                    }
                }
            }
        }
    }

    private fun linearSlope(values: List<Double>): Double {
        val n = values.size
        if (n < 2) return 0.0
        var sumX = 0.0
        var sumY = 0.0
        var sumXY = 0.0
        var sumX2 = 0.0
        for (i in 0 until n) {
            sumX += i
            sumY += values[i]
            sumXY += i * values[i]
            sumX2 += i.toDouble() * i
        }
        val denom = n * sumX2 - sumX * sumX
        if (abs(denom) < 1e-12) return 0.0
        return (n * sumXY - sumX * sumY) / denom
    }
}
