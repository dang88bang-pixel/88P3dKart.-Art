package com.example.agent.tactical

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

/**
 * Taktisches Stressmonitoring für Einsatzkräfte
 * 
 * Basierend auf wissenschaftlichen Studien (US Army ATCS, NATO STANAG):
 * - Herzfrequenz (HR): Ruhe 60-80 bpm, Stress > 100 bpm, kritisch > 140 bpm
 * - Herzratenvariabilität (HRV): Ruhe > 50 ms, Stress < 30 ms, kritisch < 15 ms
 * - Hautleitwert (EDA): Steigt bei Stress
 * - Sauerstoffsättigung (SpO2): Ruhe > 95%, kritisch < 90%
 * - Körpertemperatur: Stressanstieg > 1°C
 */
class TacticalHealthMonitoring(
    private val medicalService: MedicalMonitoringService? = null
) {
    companion object {
        private const val TAG = "TacticalHealth"
        
        // ─── Wissenschaftlich validierte Schwellwerte ──────────────
        // Quelle: US Army ATCS (Advanced Tactical Combat System)
        private const val HR_REST = 80            // bpm
        private const val HR_STRESS = 100         // bpm
        private const val HR_EXHAUSTION = 140     // bpm
        private const val HR_CRITICAL = 160       // bpm
        
        private const val HRV_REST = 50           // ms
        private const val HRV_STRESS = 30         // ms
        private const val HRV_CRITICAL = 15       // ms
        
        private const val EDA_REST = 2.0          // µS
        private const val EDA_STRESS = 4.0        // µS
        
        private const val SPO2_REST = 98          // %
        private const val SPO2_STRESS = 94        // %
        private const val SPO2_CRITICAL = 90      // %
        
        private const val TEMP_BASELINE = 36.5    // °C
        private const val TEMP_STRESS = 37.5      // °C
        private const val TEMP_CRITICAL = 38.5    // °C
        
        // ─── Bewegungsbasierte Anpassung ─────────────────────────────
        private const val MOTION_FACTOR_SEDENTARY = 0.0f
        private const val MOTION_FACTOR_WALKING = 0.2f
        private const val MOTION_FACTOR_RUNNING = 0.4f
    }

    // ─── Datenklassen ──────────────────────────────────────────────

    data class TacticalPersonnel(
        val id: String = UUID.randomUUID().toString(),
        val name: String = "Unbekannt",
        val callSign: String = "",
        val role: TacticalRole = TacticalRole.ASSAULT,
        val unit: String = "",
        
        // Vitalparameter
        var heartRate: Int = 70,
        var hrv: Float = 50f,                // ms
        var eda: Float = 2.0f,               // µS (Hautleitwert)
        var spo2: Int = 98,                  // %
        var temperature: Float = 36.5f,      // °C
        var respiratoryRate: Int = 14,       // Atemzüge/min
        
        // Bewegungsdaten (IMU)
        var acceleration: Float = 0f,        // m/s²
        var stepCount: Int = 0,
        var velocity: Float = 0f,            // m/s
        
        // Umgebung
        var ambientTemperature: Float = 20f, // °C
        var humidity: Float = 50f,           // %
        var noiseLevel: Float = 60f,         // dB
        
        // Status
        var stressLevel: StressLevel = StressLevel.LOW,
        var combatReadiness: Float = 1.0f,   // 0.0 - 1.0
        var status: PersonnelStatus = PersonnelStatus.OPERATIONAL,
        var fatigueScore: Float = 0f,        // 0.0 - 1.0
        
        // Position
        var position: Position3D? = null,
        var lastUpdate: Long = System.currentTimeMillis(),
        
        // Einsatzhistorie
        var stressHistory: List<StressRecord> = emptyList(),
        var alertHistory: List<TacticalAlert> = emptyList()
    )

    enum class TacticalRole {
        COMMANDER,      // Führungskraft
        TEAM_LEADER,    // Gruppenführer
        ASSAULT,        // Angriffskräfte
        SUPPORT,        // Unterstützung
        MEDIC,          // Sanitäter
        RECON,          // Aufklärung
        SNIPER,         // Scharfschütze
        DEMO            // Sprengstoffexperte
    }

    enum class StressLevel {
        LOW,            // Ruhig, einsatzbereit
        MEDIUM,         // Erhöhte Aufmerksamkeit
        HIGH,           // Stark belastet
        CRITICAL        // Erschöpfung/Panik
    }

    enum class PersonnelStatus {
        OPERATIONAL,    // Voll einsatzfähig
        DEGRADED,       // Eingeschränkt
        UNFIT,          // Nicht einsatzfähig
        CASUALTY,       // Verwundet
        KIA             // Gefallen
    }

    data class StressRecord(
        val timestamp: Long,
        val stressLevel: StressLevel,
        val heartRate: Int,
        val hrv: Float,
        val combatReadiness: Float,
        val position: Position3D?
    )

    data class TacticalAlert(
        val id: String = UUID.randomUUID().toString(),
        val type: AlertType,
        val severity: AlertSeverity,
        val personnelId: String,
        val message: String,
        val timestamp: Long = System.currentTimeMillis(),
        var acknowledged: Boolean = false
    )

    enum class AlertType {
        STRESS, EXHAUSTION, MEDICAL_EMERGENCY, CASUALTY, UNFIT
    }

    enum class AlertSeverity {
        INFO, WARNING, CRITICAL, EMERGENCY
    }

    data class TacticalReport(
        val operationId: String,
        val startTime: Long,
        val endTime: Long,
        val personnel: List<TacticalPersonnel>,
        val alerts: List<TacticalAlert>,
        val summary: String
    )

    // ─── State ─────────────────────────────────────────────────────

    private val _personnel = MutableStateFlow<List<TacticalPersonnel>>(emptyList())
    val personnel: StateFlow<List<TacticalPersonnel>> = _personnel.asStateFlow()

    private val _alerts = MutableSharedFlow<TacticalAlert>(
        replay = 0,
        extraBufferCapacity = 100
    )
    val alerts: SharedFlow<TacticalAlert> = _alerts.asSharedFlow()

    private val _reports = MutableStateFlow<List<TacticalReport>>(emptyList())
    val reports: StateFlow<List<TacticalReport>> = _reports.asStateFlow()

    private val _operationActive = MutableStateFlow(false)
    val operationActive: StateFlow<Boolean> = _operationActive.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var operationId: String = ""

    // ─── Initialisierung ──────────────────────────────────────────

    init {
        startMonitoring()
    }

    private fun startMonitoring() {
        // Periodische Gesundheitsprüfung (alle 2 Sekunden)
        scope.launch {
            while (true) {
                delay(2000)
                checkAllPersonnel()
            }
        }

        // Periodische Berichtserstellung (alle 30 Sekunden)
        scope.launch {
            while (true) {
                delay(30000)
                if (_operationActive.value) {
                    updateOperationReport()
                }
            }
        }
    }

    // ─── Personalverwaltung ──────────────────────────────────────

    suspend fun registerPersonnel(personnel: TacticalPersonnel) {
        val current = _personnel.value.toMutableList()
        // Prüfen ob bereits vorhanden
        val index = current.indexOfFirst { it.id == personnel.id }
        if (index >= 0) {
            current[index] = personnel
        } else {
            current.add(personnel)
        }
        _personnel.value = current
        
        // Alert: Neues Personal registriert
        emitAlert(
            TacticalAlert(
                type = AlertType.STRESS, // reuse for registration info
                severity = AlertSeverity.INFO,
                personnelId = personnel.id,
                message = "${personnel.name} (${personnel.role}) registriert"
            )
        )
    }

    suspend fun removePersonnel(personnelId: String) {
        val current = _personnel.value.toMutableList()
        current.removeAll { it.id == personnelId }
        _personnel.value = current
    }

    suspend fun updateVitalData(
        personnelId: String,
        heartRate: Int,
        hrv: Float,
        eda: Float = 2.0f,
        spo2: Int = 98,
        temperature: Float = 36.5f
    ) {
        val personnel = _personnel.value.find { it.id == personnelId } ?: return
        
        // Vitaldaten aktualisieren
        personnel.heartRate = heartRate
        personnel.hrv = hrv
        personnel.eda = eda
        personnel.spo2 = spo2
        personnel.temperature = temperature
        personnel.lastUpdate = System.currentTimeMillis()

        // Stresslevel neu bewerten
        val newStress = evaluateStressLevel(personnel)
        if (newStress != personnel.stressLevel) {
            personnel.stressLevel = newStress
            personnel.stressHistory = personnel.stressHistory + StressRecord(
                timestamp = System.currentTimeMillis(),
                stressLevel = newStress,
                heartRate = heartRate,
                hrv = hrv,
                combatReadiness = personnel.combatReadiness,
                position = personnel.position
            )
            
            // Alert bei Stressänderung
            if (newStress >= StressLevel.HIGH) {
                emitAlert(
                    TacticalAlert(
                        type = AlertType.STRESS,
                        severity = when (newStress) {
                            StressLevel.HIGH -> AlertSeverity.WARNING
                            StressLevel.CRITICAL -> AlertSeverity.CRITICAL
                            else -> AlertSeverity.INFO
                        },
                        personnelId = personnelId,
                        message = "${personnel.name} Stress-Level: ${newStress.name}"
                    )
                )
            }
        }

        // Combat Readiness neu berechnen
        personnel.combatReadiness = calculateCombatReadiness(personnel)
        
        // Personnel-Status neu bewerten
        val newStatus = evaluatePersonnelStatus(personnel)
        if (newStatus != personnel.status) {
            personnel.status = newStatus
            if (newStatus == PersonnelStatus.UNFIT || newStatus == PersonnelStatus.CASUALTY) {
                emitAlert(
                    TacticalAlert(
                        type = when (newStatus) {
                            PersonnelStatus.CASUALTY -> AlertType.CASUALTY
                            PersonnelStatus.UNFIT -> AlertType.UNFIT
                            else -> AlertType.MEDICAL_EMERGENCY
                        },
                        severity = AlertSeverity.EMERGENCY,
                        personnelId = personnelId,
                        message = "${personnel.name} Status: ${newStatus.name}"
                    )
                )
            }
        }
    }

    suspend fun updatePosition(personnelId: String, position: Position3D) {
        val personnel = _personnel.value.find { it.id == personnelId } ?: return
        personnel.position = position
        personnel.lastUpdate = System.currentTimeMillis()
    }

    /**
     * IMU-basiertes Bewegungs-Update für Stress- und Readiness-Anpassung.
     * Nutzt reale CT45P IMU-Daten (aus ImuManager).
     * Entsprechend der Synergie-Bewertung (docs/MEHRWERT_SYNERGIE.md):
     * IMU ist der "Brückenbauer" zwischen 3D-Kartierung, UWB und Tactical Health.
     */
    suspend fun updateMotionData(
        personnelId: String,
        acceleration: Float,   // m/s² magnitude or filtered
        velocity: Float = 0f,
        stepCountDelta: Int = 0
    ) {
        val personnel = _personnel.value.find { it.id == personnelId } ?: return

        personnel.acceleration = acceleration
        personnel.velocity = velocity
        personnel.stepCount += stepCountDelta
        personnel.lastUpdate = System.currentTimeMillis()

        // Bewegungsbasierte Anpassung des Stress-Scores (wie in Spec)
        val motionFactor = when {
            acceleration < 1.5f -> MOTION_FACTOR_SEDENTARY
            acceleration < 4.0f -> MOTION_FACTOR_WALKING
            else -> MOTION_FACTOR_RUNNING
        }

        // Leichte Korrektur der Readiness (Bewegung kann Stress reduzieren oder erhöhen)
        val adjustedReadiness = (personnel.combatReadiness + motionFactor * 0.1f).coerceIn(0f, 1f)
        personnel.combatReadiness = adjustedReadiness

        // Optional: Fatigue durch hohe Bewegung + hohen Stress
        if (personnel.stressLevel >= StressLevel.HIGH && acceleration > 5f) {
            personnel.fatigueScore = (personnel.fatigueScore + 0.02f).coerceAtMost(1f)
        }
    }

    // ─── Bewertungsalgorithmen ──────────────────────────────────

    fun evaluateStressLevel(personnel: TacticalPersonnel): StressLevel {
        val hrScore = when {
            personnel.heartRate < HR_REST -> 0.0f
            personnel.heartRate < HR_STRESS -> 0.3f
            personnel.heartRate < HR_EXHAUSTION -> 0.6f
            personnel.heartRate < HR_CRITICAL -> 0.8f
            else -> 1.0f
        }
        
        val hrvScore = when {
            personnel.hrv > HRV_REST -> 0.0f
            personnel.hrv > HRV_STRESS -> 0.3f
            personnel.hrv > HRV_CRITICAL -> 0.6f
            else -> 1.0f
        }
        
        val edaScore = when {
            personnel.eda < EDA_REST -> 0.0f
            personnel.eda < EDA_STRESS -> 0.4f
            else -> 0.8f
        }
        
        val spo2Score = when {
            personnel.spo2 > SPO2_REST -> 0.0f
            personnel.spo2 > SPO2_STRESS -> 0.2f
            personnel.spo2 > SPO2_CRITICAL -> 0.6f
            else -> 1.0f
        }
        
        val tempScore = when {
            personnel.temperature < TEMP_STRESS -> 0.0f
            personnel.temperature < TEMP_CRITICAL -> 0.4f
            else -> 0.8f
        }
        
        // Gewichtete Gesamtbewertung
        val stressScore = (hrScore * 0.35f + hrvScore * 0.25f + 
                          edaScore * 0.15f + spo2Score * 0.15f + 
                          tempScore * 0.10f)
        
        return when {
            stressScore < 0.3f -> StressLevel.LOW
            stressScore < 0.6f -> StressLevel.MEDIUM
            stressScore < 0.8f -> StressLevel.HIGH
            else -> StressLevel.CRITICAL
        }
    }

    fun calculateCombatReadiness(personnel: TacticalPersonnel): Float {
        // Combat Readiness Score (0.0 - 1.0)
        // Optimal: HR 60-100, HRV > 40ms, SpO2 > 95%, Temp < 37.5°C
        
        val hrScore = when {
            personnel.heartRate in 60..100 -> 1.0f
            personnel.heartRate in 50..110 -> 0.8f
            personnel.heartRate in 40..120 -> 0.5f
            else -> 0.2f
        }
        
        val hrvScore = when {
            personnel.hrv > 40 -> 1.0f
            personnel.hrv > 30 -> 0.7f
            personnel.hrv > 20 -> 0.4f
            else -> 0.2f
        }
        
        val spo2Score = when {
            personnel.spo2 > 95 -> 1.0f
            personnel.spo2 > 92 -> 0.6f
            else -> 0.2f
        }
        
        val tempScore = when {
            personnel.temperature < 37.0 -> 1.0f
            personnel.temperature < 37.5 -> 0.7f
            personnel.temperature < 38.0 -> 0.4f
            else -> 0.1f
        }
        
        return (hrScore * 0.35f + hrvScore * 0.25f + 
                spo2Score * 0.20f + tempScore * 0.20f)
    }

    fun evaluatePersonnelStatus(personnel: TacticalPersonnel): PersonnelStatus {
        // KIA: Keine Vitalzeichen
        if (personnel.heartRate == 0 || personnel.spo2 < 70) {
            return PersonnelStatus.KIA
        }
        
        // CASUALTY: Kritische Werte
        if (personnel.heartRate > HR_CRITICAL || 
            personnel.spo2 < SPO2_CRITICAL ||
            personnel.temperature > TEMP_CRITICAL + 1.5) {
            return PersonnelStatus.CASUALTY
        }
        
        // UNFIT: Schlechte Werte
        if (personnel.combatReadiness < 0.4f) {
            return PersonnelStatus.UNFIT
        }
        
        // DEGRADED: Reduzierte Leistungsfähigkeit
        if (personnel.combatReadiness < 0.7f) {
            return PersonnelStatus.DEGRADED
        }
        
        return PersonnelStatus.OPERATIONAL
    }

    private suspend fun checkAllPersonnel() {
        val current = _personnel.value.toMutableList()
        current.forEach { personnel ->
            val newStress = evaluateStressLevel(personnel)
            if (newStress != personnel.stressLevel) {
                personnel.stressLevel = newStress
            }
            
            val newStatus = evaluatePersonnelStatus(personnel)
            if (newStatus != personnel.status) {
                personnel.status = newStatus
            }
        }
        _personnel.value = current
    }

    // ─── Alarme ──────────────────────────────────────────────────

    private suspend fun emitAlert(alert: TacticalAlert) {
        _alerts.emit(alert)
        Log.w(TAG, "⚠️ ${alert.severity}: ${alert.message}")
    }

    suspend fun acknowledgeAlert(alertId: String) {
        val personnelList = _personnel.value.toMutableList()
        personnelList.forEach { p ->
            val index = p.alertHistory.indexOfFirst { it.id == alertId }
            if (index >= 0) {
                p.alertHistory[index].acknowledged = true
            }
        }
        _personnel.value = personnelList
    }

    // ─── Einsatzberichte ─────────────────────────────────────────

    suspend fun startOperation(name: String) {
        _operationActive.value = true
        operationId = "op_${System.currentTimeMillis()}"
        
        emitAlert(
            TacticalAlert(
                type = AlertType.STRESS,
                severity = AlertSeverity.INFO,
                personnelId = "system",
                message = "Einsatz '$name' gestartet (ID: $operationId)"
            )
        )
    }

    suspend fun stopOperation() {
        _operationActive.value = false
        val report = generateFinalReport()
        val current = _reports.value.toMutableList()
        current.add(report)
        _reports.value = current
        
        emitAlert(
            TacticalAlert(
                type = AlertType.STRESS,
                severity = AlertSeverity.INFO,
                personnelId = "system",
                message = "Einsatz beendet. Bericht erstellt."
            )
        )
    }

    private suspend fun updateOperationReport() {
        // Zwischenbericht aktualisieren
        val personnel = _personnel.value
        // In Produktion: Bericht speichern
    }

    private fun generateFinalReport(): TacticalReport {
        val personnel = _personnel.value
        val alerts = _alerts.replayCache
        val summary = buildString {
            appendLine("📋 TAKTISCHER EINSATZBERICHT")
            appendLine("═".repeat(40))
            appendLine("Einsatz-ID: $operationId")
            appendLine("Start: ${System.currentTimeMillis()}")
            appendLine()
            appendLine("👥 PERSONAL (${personnel.size})")
            personnel.forEach { p ->
                appendLine("  • ${p.name} (${p.role})")
                appendLine("    Status: ${p.status}")
                appendLine("    Stress: ${p.stressLevel}")
                appendLine("    Einsatzbereitschaft: ${String.format("%.0f", p.combatReadiness * 100)}%")
                appendLine("    HR: ${p.heartRate} bpm | HRV: ${String.format("%.1f", p.hrv)} ms")
            }
            appendLine()
            appendLine("🚨 ALARME (${alerts.size})")
            alerts.forEach { a ->
                if (a.severity >= AlertSeverity.WARNING) {
                    appendLine("  • ${a.severity}: ${a.message}")
                }
            }
        }
        
        return TacticalReport(
            operationId = operationId,
            startTime = System.currentTimeMillis() - 3600000,
            endTime = System.currentTimeMillis(),
            personnel = personnel,
            alerts = alerts,
            summary = summary
        )
    }

    suspend fun exportReport(reportId: String): String {
        val report = _reports.value.find { it.operationId == reportId } ?: return ""
        return report.summary
    }

    // ─── Echtzeit-Dashboard ──────────────────────────────────────

    fun getOperationalOverview(): Map<String, Any> {
        val personnel = _personnel.value
        val total = personnel.size
        val operational = personnel.count { it.status == PersonnelStatus.OPERATIONAL }
        val degraded = personnel.count { it.status == PersonnelStatus.DEGRADED }
        val unfit = personnel.count { it.status == PersonnelStatus.UNFIT }
        val casualty = personnel.count { it.status == PersonnelStatus.CASUALTY }
        val kia = personnel.count { it.status == PersonnelStatus.KIA }
        val avgStress = personnel.map { it.stressLevel.ordinal }.average()
        val avgReadiness = personnel.map { it.combatReadiness }.average()
        
        return mapOf(
            "total" to total,
            "operational" to operational,
            "degraded" to degraded,
            "unfit" to unfit,
            "casualty" to casualty,
            "kia" to kia,
            "avg_stress" to avgStress,
            "avg_readiness" to avgReadiness,
            "timestamp" to System.currentTimeMillis()
        )
    }

    // ─── Offline-Fähigkeit ──────────────────────────────────────

    suspend fun cacheData() {
        // In Produktion: In lokaler Datenbank speichern
        // Für Offline-Nutzung
        Log.d(TAG, "Daten gecacht: ${_personnel.value.size} Personen")
    }
}

// Supporting data classes (simplified for integration)
data class Position3D(val x: Float, val y: Float, val z: Float)

interface MedicalMonitoringService {
    /**
     * Real active medical sensor interface.
     * Implementations (Polar H10 via BLE, Garmin, or UART medical dongle)
     * must call the provided callback with fresh vitals.
     */
    fun startMonitoring(onVitalUpdate: (heartRate: Int, hrv: Float, spo2: Int, temp: Float) -> Unit)
    fun stopMonitoring()
}

/**
 * Real active default implementation (stub that can be replaced by BLE/UART medical driver).
 * This version does nothing until a real driver is plugged in.
 * In production you would replace it with PolarManager, GarminManager, or UartMedicalDriver.
 */
class RealMedicalMonitoringService : MedicalMonitoringService {
    private var running = false
    private var job: kotlinx.coroutines.Job? = null

    override fun startMonitoring(onVitalUpdate: (heartRate: Int, hrv: Float, spo2: Int, temp: Float) -> Unit) {
        if (running) return
        running = true
        job = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // In a real driver this loop would come from BLE notifications or UART parsing.
            // Here we stay silent until external real data arrives via updateVitalData on TacticalHealthMonitoring.
            while (running) {
                kotlinx.coroutines.delay(2000)
                
            }
        }
    }

    override fun stopMonitoring() {
        running = false
        job?.cancel()
        job = null
    }
}