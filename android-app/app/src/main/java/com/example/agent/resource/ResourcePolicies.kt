package com.example.agent.resource

import kotlin.math.sqrt

/**
 * Ressourcensparende Scan-/Betriebspolitiken (docs/RESOURCE_OPT.md).
 *
 * Portierung der v11.0.0-Kernlogik (AdaptiveScanManager,
 * ResourceManagementService) — **mit Korrekturen am Spec-Code:**
 *
 * 1. `Triple(20f, 20f, 10f, 5f)` + 4-fach-Destrukturierung: Kotlins
 *    `Triple` hat genau **drei** Werte — kompiliert nicht. Ersetzt durch
 *    die Datenklasse [ScanRates].
 * 2. Rückgabetyp `Quadruple<...>` (ResourceManagementService): existiert in
 *    der Kotlin-Standardbibliothek **nicht** — ebenfalls [ScanRates].
 * 3. Die Energieprofil- und Ratenlogik ist als reine Funktionen umgesetzt
 *    (JVM-testbar); die Android-Seite (Foreground Service/Broadcasts)
 *    folgt dem WorkManager-/Coroutine-Muster aus docs/SERVICE_WORKER.md.
 */
object ResourcePolicies {

    enum class MotionState { STATIONARY, WALKING, RUNNING, VEHICLE }

    enum class PowerProfile { PERFORMANCE, BALANCED, POWER_SAVE, EMERGENCY }

    /** Ressourcen-Zustand (v11.0.0 ResourceState). */
    data class ResourceState(
        val cpuLoad: Float,           // 0..1
        val memoryUsage: Float,       // 0..1
        val batteryLevel: Int,        // 0..100
        val batteryTemperature: Float,
        val isCharging: Boolean = false,
        val networkBandwidthMbs: Float = 0f,
    )

    /** Scan-Raten-Konfiguration (ersetzt Triple/Quadruple der Spec). */
    data class ScanRates(
        val lidarRate: Float,
        val mmwaveRate: Float,
        val uwbRate: Float,
        val bleRate: Float,
        val meshRate: Float,
        val quality: Float, // 0..1
    )

    // ── Adaptive Scan-Raten ─────────────────────────────────────────

    private const val MIN_SCAN_RATE = 1f
    private const val MAX_SCAN_RATE = 20f
    private const val MOTION_THRESHOLD_WALKING = 0.5f // m/s
    private const val MOTION_THRESHOLD_RUNNING = 1.5f
    private const val MOTION_THRESHOLD_VEHICLE = 5.0f

    /** Basis-Raten je Bewegungszustand (v11.0.0-Tabelle). */
    private data class BaseRates(val lidar: Float, val mmwave: Float, val uwb: Float, val ble: Float)

    private val BASE_RATES = mapOf(
        MotionState.VEHICLE to BaseRates(20f, 20f, 10f, 5f),
        MotionState.RUNNING to BaseRates(15f, 15f, 8f, 4f),
        MotionState.WALKING to BaseRates(10f, 10f, 5f, 3f),
        MotionState.STATIONARY to BaseRates(2f, 2f, 1f, 1f),
    )

    /** Mesh-Rate je Bewegungszustand (Stillstand → seltener Rebuild). */
    private val MESH_RATES = mapOf(
        MotionState.VEHICLE to 10f,
        MotionState.RUNNING to 5f,
        MotionState.WALKING to 2f,
        MotionState.STATIONARY to 0.5f,
    )

    /** Qualität je Bewegungszustand (Bewegungsunschärfe-Kompensation). */
    private val QUALITY_FACTORS = mapOf(
        MotionState.STATIONARY to 1.0f,
        MotionState.WALKING to 0.9f,
        MotionState.RUNNING to 0.7f,
        MotionState.VEHICLE to 0.5f,
    )

    /** Baseline (Volllast) für die Einsparungsberechnung. */
    val BASELINE_RATES = ScanRates(20f, 20f, 10f, 10f, 5f, 1f)

    fun motionStateOf(velocity: Float): MotionState = when {
        velocity > MOTION_THRESHOLD_VEHICLE -> MotionState.VEHICLE
        velocity > MOTION_THRESHOLD_RUNNING -> MotionState.RUNNING
        velocity > MOTION_THRESHOLD_WALKING -> MotionState.WALKING
        else -> MotionState.STATIONARY
    }

    /** Batterie-Faktor (v11.0.0-Staffel). */
    fun batteryFactor(batteryLevel: Int): Float = when {
        batteryLevel > 80 -> 1.0f
        batteryLevel > 50 -> 0.8f
        batteryLevel > 30 -> 0.5f
        batteryLevel > 15 -> 0.3f
        else -> 0.1f
    }

    /** Temperatur-Faktor (Überhitzung drosselt). */
    fun thermalFactor(thermalC: Float): Float = when {
        thermalC > 50f -> 0.3f
        thermalC > 40f -> 0.6f
        thermalC > 35f -> 0.8f
        else -> 1.0f
    }

    /**
     * Berechnet die Scan-Konfiguration aus Bewegung, Batterie und Temperatur.
     */
    fun computeScanRates(
        velocity: Float,
        batteryLevel: Int,
        thermalC: Float,
    ): ScanRates {
        val motion = motionStateOf(velocity)
        val base = BASE_RATES.getValue(motion)
        val factor = batteryFactor(batteryLevel) * thermalFactor(thermalC)

        return ScanRates(
            lidarRate = (base.lidar * factor).coerceIn(MIN_SCAN_RATE, MAX_SCAN_RATE),
            mmwaveRate = (base.mmwave * factor).coerceIn(MIN_SCAN_RATE, MAX_SCAN_RATE),
            uwbRate = (base.uwb * factor).coerceIn(0.5f, MAX_SCAN_RATE),
            bleRate = (base.ble * factor).coerceIn(0.5f, MAX_SCAN_RATE),
            meshRate = MESH_RATES.getValue(motion),
            quality = QUALITY_FACTORS.getValue(motion),
        )
    }

    /** Einsparung je Sensor relativ zur Baseline (1 = 100 %). */
    fun savings(current: ScanRates): Map<String, Float> {
        fun saving(cur: Float, base: Float): Float {
            if (base <= 0f) return 0f
            return (1f - cur / base).coerceIn(0f, 1f)
        }

        val lidar = saving(current.lidarRate, BASELINE_RATES.lidarRate)
        val mmwave = saving(current.mmwaveRate, BASELINE_RATES.mmwaveRate)
        val uwb = saving(current.uwbRate, BASELINE_RATES.uwbRate)
        val ble = saving(current.bleRate, BASELINE_RATES.bleRate)
        val mesh = saving(current.meshRate, BASELINE_RATES.meshRate)
        return mapOf(
            "lidar" to lidar,
            "mmwave" to mmwave,
            "uwb" to uwb,
            "ble" to ble,
            "mesh" to mesh,
            "total" to (lidar + mmwave + uwb + ble + mesh) / 5f,
        )
    }

    // ── Energieprofil (v11.0.0 ResourceManagementService) ──────────

    fun determinePowerProfile(state: ResourceState): PowerProfile = when {
        state.batteryLevel < 15 -> PowerProfile.EMERGENCY
        state.batteryLevel < 30 && !state.isCharging -> PowerProfile.POWER_SAVE
        state.cpuLoad > 0.7f || state.batteryTemperature > 40f -> PowerProfile.POWER_SAVE
        state.isCharging && state.cpuLoad < 0.5f -> PowerProfile.PERFORMANCE
        else -> PowerProfile.BALANCED
    }

    fun scanRatesForProfile(profile: PowerProfile): ScanRates = when (profile) {
        PowerProfile.PERFORMANCE -> ScanRates(20f, 20f, 10f, 10f, 5f, 1f)
        PowerProfile.BALANCED -> ScanRates(10f, 10f, 5f, 5f, 2f, 0.7f)
        PowerProfile.POWER_SAVE -> ScanRates(5f, 5f, 2f, 2f, 1f, 0.4f)
        PowerProfile.EMERGENCY -> ScanRates(1f, 1f, 0.5f, 0.5f, 0f, 0.1f)
    }

    /** Mesh-/Visualisierungsqualität je Profil (0..1). */
    fun qualityForProfile(profile: PowerProfile): Float = when (profile) {
        PowerProfile.PERFORMANCE -> 1.0f
        PowerProfile.BALANCED -> 0.7f
        PowerProfile.POWER_SAVE -> 0.4f
        PowerProfile.EMERGENCY -> 0.1f
    }
}

/**
 * Region-of-Interest-Scanning (v11.0.0 ROIScanManager): hohe Auflösung nur
 * in relevanten Bereichen, lineare Distanz-Gewichtung.
 */
class RoiWeightMap(
    private val maxRois: Int = 10,
    private val minPriority: Float = 0.3f,
    private val baseWeight: Float = 0.5f,
) {

    enum class RoiType { PERSON, VEHICLE, ENTRANCE, EXIT, COMMAND_POST, HAZARD, NETWORK_NODE }

    data class Roi(
        val centerX: Float,
        val centerY: Float,
        val centerZ: Float,
        val radius: Float,
        val priority: Float, // 0..1
        val type: RoiType,
    )

    private val rois = mutableListOf<Roi>()

    /** Fügt eine ROI hinzu (Prioritätsschwelle, Kapazität, Toleranz-Dedupe). */
    fun add(roi: Roi) {
        if (roi.priority < minPriority) return
        if (rois.any { distanceTo(it, roi) < 1e-3f }) return
        rois.add(roi)
        if (rois.size > maxRois) {
            rois.sortByDescending { it.priority }
            while (rois.size > maxRois) rois.removeAt(rois.size - 1)
        }
    }

    fun remove(centerX: Float, centerY: Float, centerZ: Float) {
        rois.removeAll { distanceTo(it, centerX, centerY, centerZ) < 1e-3f }
    }

    fun clear() = rois.clear()

    fun size(): Int = rois.size

    /** Scan-Gewichtung an einer Position (linearer Falloff, Basis 0,5). */
    fun weightAt(x: Float, y: Float, z: Float): Float {
        var maxWeight = baseWeight
        for (roi in rois) {
            val dist = distanceTo(roi, x, y, z)
            if (dist < roi.radius) {
                val weight = roi.priority * (1f - dist / roi.radius)
                if (weight > maxWeight) maxWeight = weight
            }
        }
        return maxWeight.coerceIn(0.1f, 1.0f)
    }

    private fun distanceTo(a: Roi, b: Roi): Float = distanceTo(a, b.centerX, b.centerY, b.centerZ)

    private fun distanceTo(roi: Roi, x: Float, y: Float, z: Float): Float {
        val dx = x - roi.centerX
        val dy = y - roi.centerY
        val dz = z - roi.centerZ
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
