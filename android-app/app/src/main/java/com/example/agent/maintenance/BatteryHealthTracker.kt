package com.example.agent.maintenance

import kotlin.math.max

/**
 * Batteriezustands-Überwachung und Alterungsvorhersage (docs/SERVICE_WORKER.md).
 *
 * Portierung der sinnvollen Kernlogik aus der v10.2.0-ServiceWorker-
 * Spezifikation (Health-Schätzung, Ladezyklus-Tracking, Restlaufzeit-
 * Vorhersage, Empfehlungen) — **als reines Monitoring-Modul**:
 *
 * - **Ladezyklus-Zählung über kumulierte Entladung** statt des naiven
 *   `level === 100 && charging` der Spec (das zählt nur Voll-Ladungen
 *   und verfehlt Teilzyklen); 100 %-Punkte Entladung = 1 Zyklusäquivalent.
 * - **Keine Ladekontrolle:** Zielspannung/Laderate kann keine App und kein
 *   Worker setzen (Hardware-/OS-domäne) — das Modul liefert nur
 *   Empfehlungen.
 * - Referenzwerte: Honeywell CT45P-Akku 4020 mAh.
 */
class BatteryHealthTracker(
    private val capacityMah: Double = 4020.0,
    /** Kapazitätsverlust in % pro Ladezyklus. */
    private val cycleDegradationPct: Double = 0.01,
    /** Kalendarische Alterung in % pro Jahr. */
    private val yearlyDegradationPct: Double = 0.5,
    /** Standard-Entladerate in %/h, falls zu wenige Samples vorliegen (≈ 8 h Laufzeit). */
    private val defaultDischargePctPerHour: Double = 12.5,
) {

    data class BatteryState(
        val levelPct: Double,
        val temperatureC: Double,
        val isCharging: Boolean,
        val voltageV: Double = 4.2,
        val timestampMs: Long = System.currentTimeMillis(),
    )

    data class BatteryReport(
        val state: BatteryState,
        val healthPct: Double,
        val cycleCount: Double,
        val remainingRuntimeMin: Double,
        val remainingCyclesToEol: Double,
        val recommendations: List<String>,
    )

    private data class Sample(val level: Double, val timestampMs: Long)

    private var lastState: BatteryState? = null
    private val dischargeSamples = ArrayDeque<Sample>()
    private var cumulativeDischargePct = 0.0
    private var firstUseMs: Long = System.currentTimeMillis()
    private var lastReport: BatteryReport? = null

    /**
     * Verarbeitet einen Batteriezustand.
     * @return [BatteryReport] oder null beim ersten Aufruf (Baseline).
     */
    fun onBatteryState(state: BatteryState): BatteryReport? {
        val previous = lastState
        lastState = state

        if (previous == null) return null

        // Kumulierte Entladung nur im Entladebetrieb integrieren
        if (!state.isCharging && !previous.isCharging && state.levelPct < previous.levelPct) {
            cumulativeDischargePct += previous.levelPct - state.levelPct
            dischargeSamples.addLast(Sample(state.levelPct, state.timestampMs))
            while (dischargeSamples.size > 120) dischargeSamples.removeFirst()
        }

        val cycles = cumulativeDischargePct / 100.0
        val years = (state.timestampMs - firstUseMs) / (365.25 * 24 * 3600 * 1000.0)
        val health = max(
            0.0,
            100.0 - cycles * cycleDegradationPct - years * yearlyDegradationPct,
        )

        val remainingRuntimeMin = estimateRuntime(state, health)
        val remainingCycles = max(0.0, (health - 80.0) / cycleDegradationPct)

        val recommendations = buildRecommendations(state, health)
        val report = BatteryReport(
            state = state,
            healthPct = health,
            cycleCount = cycles,
            remainingRuntimeMin = remainingRuntimeMin,
            remainingCyclesToEol = remainingCycles,
            recommendations = recommendations,
        )
        lastReport = report
        return report
    }

    fun lastReport(): BatteryReport? = lastReport

    /** Zykluszähler manuell setzen (z. B. aus Gerätehistorie). */
    fun setCycleCount(cycles: Double) {
        cumulativeDischargePct = cycles * 100.0
    }

    // ── Intern ───────────────────────────────────────────────────────

    private fun estimateRuntime(state: BatteryState, healthPct: Double): Double {
        // Entladerate aus den letzten Samples schätzen
        var ratePctPerHour: Double? = null
        if (dischargeSamples.size >= 2) {
            val first = dischargeSamples.first()
            val last = dischargeSamples.last()
            val dtHours = (last.timestampMs - first.timestampMs) / 3_600_000.0
            if (dtHours > 0.0) {
                val levelDrop = first.level - last.level
                if (levelDrop > 0.0) ratePctPerHour = levelDrop / dtHours
            }
        }
        val rate = ratePctPerHour ?: defaultDischargePctPerHour
        if (rate <= 0.0) return 0.0

        val effectiveCapacity = capacityMah * healthPct / 100.0
        val remainingEnergy = effectiveCapacity * state.levelPct / 100.0
        val drainMah = rate / 100.0 * capacityMah // mAh pro Stunde
        return remainingEnergy / drainMah * 60.0
    }

    private fun buildRecommendations(state: BatteryState, healthPct: Double): List<String> {
        val result = ArrayList<String>(4)
        if (healthPct < 80.0) result.add("Batterie sollte bald ausgetauscht werden")
        if (state.levelPct < 20.0) result.add("Batterie ist kritisch niedrig — jetzt laden")
        if (state.temperatureC > 45.0) result.add("Batterie überhitzt — abkühlen lassen")
        if (state.isCharging && state.levelPct > 80.0) {
            result.add("Ladestopp empfohlen (80 % erreicht — schont die Alterung)")
        }
        return result
    }
}
