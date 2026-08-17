package com.example.agent

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.agent.network.AgentWebSocketClient
import com.example.agent.aura.AuraIntegrator
import com.example.agent.pipeline.LiveSensorPipeline
import com.example.agent.pipeline.PipelineOrchestrator
import com.example.agent.triangulation.TriangulationService
import com.example.agent.sensors.BleTokenManager
import com.example.agent.sensors.CognitiveRadarPolicy
import com.example.agent.sensors.EkfFusion
import com.example.agent.sensors.ImuManager
import com.example.agent.sensors.SerialManager
import com.example.agent.sensors.UwbManager
import com.example.agent.storage.AppDatabase
import com.example.agent.storage.SpatialRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var serialManager: SerialManager
    private lateinit var bleManager: BleTokenManager
    private lateinit var imuManager: ImuManager
    private lateinit var uwbManager: UwbManager
    private lateinit var ekf: EkfFusion
    private lateinit var db: AppDatabase
    private lateinit var pipeline: PipelineOrchestrator
    private lateinit var livePipeline: LiveSensorPipeline
    private lateinit var auraIntegrator: AuraIntegrator
    private lateinit var triangulation: TriangulationService
    lateinit var webSocketClient: AgentWebSocketClient

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * Permission-Erlaubnis-Flag. Die gesamte Hardware-Initialisierung
     * (BLE-Scan, IMU, USB-Serial, UWB) wird erst NACH dem Grant gestartet —
     * sonst riskiert man SecurityException-Crashes, wenn der User die
     * Permissions ablehnt.
     */
    private val requiredPermissions: Array<String> by lazy {
        buildList {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.UWB_RANGING)
            // ab Android 13 (API 33): NEARBY_WIFI_DEVICES für Wi-Fi RTT-Scans
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }.toTypedArray()
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            Log.i("MainActivity", "Alle Runtime-Permissions erteilt — starte Hardware")
            initializeHardware()
        } else {
            Log.w(
                "MainActivity",
                "Permissions abgelehnt: ${results.filterValues { !it }.keys}"
            )
            // Wir starten trotzdem mit dem, was erlaubt ist — die
            // Manager prüfen ihre Permission selbst und loggen/fail-soft.
            initializeHardware()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Software-/Datenebene: benötigt KEINE Permissions, kann sofort starten.
        ekf = EkfFusion(dt = 0.05f)
        db = AppDatabase.getInstance(this)
        pipeline = PipelineOrchestrator(this)
        livePipeline = LiveSensorPipeline()
        webSocketClient = AgentWebSocketClient().also { it.connect() }

        // Permission-Flow zuerst; Hardware wird im Callback initialisiert.
        if (hasAllRequiredPermissions()) {
            initializeHardware()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    private fun hasAllRequiredPermissions(): Boolean =
        requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    /**
     * Initialisiert alle Hardware-Manager. Wird aufgerufen, sobald die
     * Runtime-Permissions geklärt sind.
     *
     * Bugfix-Historie: Vorher wurden BLE-/IMU-/Serial-Manager unbedingt
     * in `onCreate` gestartet — bei abgelehnten Permissions flogen
     * SecurityExceptions oder die Listener registrierten sich auf
     * nicht-existente Sensoren. Jetzt mit explizitem Permission-Check
     * pro Manager (fail-soft).
     */
    private fun initializeHardware() {
        // USB-Serial (RPLIDAR + mmWave) — benötigt nur USB-HOST-Feature,
        // keine Runtime-Permissions. Wenn das Gerät nicht erkannt wird,
        // ist `usbManager.deviceList` schlicht leer.
        serialManager = SerialManager(this).also {
            it.initDevices()
            it.triggerLidarScan()
            it.configureMmwave(reduced = false)
        }

        // BLE-Token — prüft ACCESS_FINE_LOCATION intern (siehe BleTokenManager).
        bleManager = BleTokenManager(this).also { it.startScan() }

        // IMU — kein Runtime-Permission; SensorManager wirft nicht, wenn
        // ein Sensor fehlt (Register wird null), darum sichere Callbacks.
        imuManager = ImuManager(this).also { it.start() }

        // UWB — benötigt UWB_RANGING. startRanging wird per UI-Action
        // getriggert; hier nur Manager instanziieren.
        uwbManager = UwbManager(this)
        uwbManager.onPhase = { phase ->
            webSocketClient.sendUwbPhase("CT45P-01", phase)
        }

        // Aura (SDR/RTI) — defensiv starten (Port belegt → nur Log).
        auraIntegrator = AuraIntegrator().also { it.setPoseProvider { ekf.getState() } }
        scope.launch {
            auraIntegrator.rtiVoxels.collect { voxels ->
                livePipeline.onRtiVoxels(voxels)
                webSocketClient.sendAuraVoxels("CT45P-01", voxels)
            }
        }
        scope.launch {
            auraIntegrator.heatmapCells.collect { cells ->
                webSocketClient.sendAuraHeatmap("CT45P-01", cells)
            }
        }
        scope.launch {
            auraIntegrator.alerts.collect { alert ->
                Log.w("Aura", "[${alert.severity}] ${alert.message}")
            }
        }
        try {
            auraIntegrator.start()
        } catch (e: Exception) {
            Log.w("Aura", "Tunnel-Start übersprungen: ${e.message}")
        }

        // Triangulation (Wi-Fi RTT / BLE / Fingerprinting)
        triangulation = TriangulationService(this, ekf)
        scope.launch {
            triangulation.fused.collect { estimate ->
                webSocketClient.sendPositionEstimate("CT45P-01", estimate)
            }
        }
        scope.launch {
            triangulation.mode.collect { mode ->
                Log.d("Triangulation", "Modus: $mode (RTT verfügbar: ${triangulation.wifiRttSupported})")
            }
        }
        try {
            triangulation.start(wifiRttEnabled = true, bleEnabled = true)
        } catch (e: Exception) {
            Log.w("Triangulation", "Start übersprungen: ${e.message}")
        }

        // Sensor → EKF + Pipeline + WebSocket
        scope.launch {
            serialManager.lidarPoints.collect { points ->
                if (points.isNotEmpty()) {
                    ekf.updateLidar(floatArrayOf(points[0], points[1], points[2]))
                    webSocketClient.sendLidarFrame("CT45P-01", points, scatteringDetected = false)
                    saveCurrentState()
                }
            }
        }
        scope.launch {
            serialManager.mmwaveTargets.collect { targets ->
                if (targets.isNotEmpty()) {
                    val t = targets.first()
                    ekf.updateMmwave(floatArrayOf(t.x, t.y, t.z))
                    webSocketClient.sendMmwaveTargets("CT45P-01", targets)
                }
            }
        }
        scope.launch {
            bleManager.tokenUpdates.collect { token ->
                Log.d("BLE", "Token ${token.mac} RSSI=${token.rssi}")
                webSocketClient.sendBleTokens("CT45P-01", listOf(token))
            }
        }
        // IMU: Sample-konsistent puffern + Cognitive Radar Policy (real on-device)
        scope.launch {
            imuManager.imuUpdates.collect { sample ->
                livePipeline.onImu(orientation = sample.gyro, accel = sample.accel)

                // === Real Cognitive Radar decision (first research integration) ===
                val accelMag = kotlin.math.sqrt(
                    sample.accel[0]*sample.accel[0] +
                    sample.accel[1]*sample.accel[1] +
                    sample.accel[2]*sample.accel[2]
                )

                val ctx = CognitiveRadarPolicy.SensorContext(
                    scatteringDetected = false,           // can be fed from LiDAR later
                    thermalC = 42f,                       // placeholder — replace with real thermal sensor
                    motionIntensity = accelMag,
                    batteryPercent = 78,                  // can be improved with real BatteryManager
                    uwbPhaseVariance = 0.3f,              // from uwbManager when available
                    mmwaveDopplerStrength = 0.5f          // can be derived from mmwave velocity
                )

                CognitiveRadarPolicy.applyToEkf(ekf, ctx)

                // Optional: log status occasionally
                if (System.currentTimeMillis() % 15000 < 200) {
                    Log.i("Cognitive", CognitiveRadarPolicy.getStatusSummary(ctx))
                }
            }
        }

        // Telemetrie (alle 5 s)
        scope.launch {
            while (true) {
                delay(5000)
                webSocketClient.sendTelemetry("CT45P-01", 85f, 45f, false)
            }
        }

        // Retention (alle 10 s prüfen)
        scope.launch {
            while (true) {
                delay(10_000)
                val cutoff = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
                db.spatialDao().deleteOlderThan(cutoff)
            }
        }

        // === REAL Service / Techniker DB (from full audit) ===
        val serviceRepo = com.example.agent.service.ServiceTechnicianRepository(this)
        scope.launch {
            serviceRepo.registerTechnician("CT45P-Feldtechniker", "TECH-CT45P-01", "Honeywell CT45P Service")
            Log.i("MainActivity", "Techniker-DB + Service-Repository initialisiert (real)")
        }
    }

    private suspend fun saveCurrentState() {
        val state = ekf.getState()
        val cov = ekf.getCovariance()
        db.spatialDao().insert(
            SpatialRecord(
                posX = state[0], posY = state[1], posZ = state[2],
                covLidar = cov[0][0], covMmwave = cov[1][1],
                metadataJson = """{"battery":85,"temp":45}""",
            )
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        // Reihenfolge: zuerst Manager stoppen, dann erst den Scope canceln.
        // Sonst könnten laufende Coroutines noch in die Manager schreiben.
        runCatching { webSocketClient.disconnect() }
        runCatching { auraIntegrator.stop() }
        runCatching { triangulation.stop() }
        runCatching { serialManager.close() }
        runCatching { bleManager.stopScan() }
        runCatching { imuManager.stop() }
        runCatching { uwbManager.stopRanging() }
        scope.cancel()
    }
}
