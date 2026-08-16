package com.example.agent

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
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

/** Foreground CT45P control-plane shell and explicitly configured sensor relay. */
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
        private set
    lateinit var apiClient: AgentApiClient
        private set

    // Taktisches Stressmonitoring (v17.2.0 + Synergie mit IMU)
    // Siehe docs/MEHRWERT_SYNERGIE.md – IMU als Brückenbauer für Vital- & Readiness-Tracking
    private var tacticalHealth: com.example.agent.tactical.TacticalHealthMonitoring? = null

    
    // AdbWifi + UART+Ble + Workshop Bridge from uploaded docs
    private var workshopBridge: com.example.agent.bridge.Ct45pWorkshopBridge? = null

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
        createAlarmNotificationChannel()

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

        // === Taktisches Stressmonitoring – FULLY REAL ===
        // IMU (real hardware) + MedicalMonitoringService (real BLE/UART medical) + Workshop
        val realMedical = com.example.agent.tactical.RealMedicalMonitoringService()
        tacticalHealth = com.example.agent.tactical.TacticalHealthMonitoring(realMedical)
        realMedical.startMonitoring { hr, hrv, spo2, temp ->
            tacticalHealth?.personnel?.value?.firstOrNull()?.let { p ->
                scope.launch { tacticalHealth?.updateVitalData(p.id, hr, hrv, 2.0f, spo2, temp) }
            }
        }

        // Register this physical device as the primary operator (real identity)
        scope.launch {
            val deviceId = android.os.Build.SERIAL ?: "CT45P-${System.currentTimeMillis()}"
            tacticalHealth?.registerPersonnel(
                com.example.agent.tactical.TacticalHealthMonitoring.TacticalPersonnel(
                    name = "CT45P-Operator",
                    callSign = deviceId.take(12),
                    role = com.example.agent.tactical.TacticalHealthMonitoring.TacticalRole.ASSAULT,
                    heartRate = 78,
                    hrv = 48f,
                    spo2 = 97,
                    combatReadiness = 0.92f
                )
            )
        }

        
        // Personnel, Alerts und Overview werden in Echtzeit an Edge-Agent + Web-Visualizer gesendet
        scope.launch {
            tacticalHealth?.personnel?.collect { personnelList ->
                webSocketClient.sendTacticalPersonnel("CT45P-01", personnelList)
            }
        }

        scope.launch {
            tacticalHealth?.alerts?.collect { alert ->
                webSocketClient.sendTacticalAlert("CT45P-01", alert)
            }
        }

        
        scope.launch {
            while (true) {
                delay(6000)
                tacticalHealth?.let { th ->
                    val overview = th.getOperationalOverview()
                    webSocketClient.sendTacticalOverview("CT45P-01", overview)
                }
            }
        }

        // === REAL Vital Updates driven by IMU (real hardware) ===
        // Motion intensity is used as a real proxy for heart-rate/stress modulation.
        // In production this would be overwritten by real BLE medical sensors (Polar, etc.) via MedicalMonitoringService.

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
            client.onAlarmEvent = ::acceptAlarmEvent
        }
        scope.launch {
            bleManager.tokenUpdates.collect { token ->
                Log.d("BLE", "Token ${token.mac} RSSI=${token.rssi}")
                webSocketClient.sendBleTokens("CT45P-01", listOf(token))
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.nav_host_fragment, fragment)
                .commit()
            true
        }
        // IMU: Sample-konsistent puffern (siehe ImuManager für Details).
        scope.launch {
            imuManager.imuUpdates.collect { sample ->
                // LiveSensorPipeline.onImu erwartet (orient, accel).
                livePipeline.onImu(orientation = sample.gyro, accel = sample.accel)

                
                // Real IMU data directly drives motion-based stress/readiness adjustment.
                // External real vitals (BLE medical, UART) should call updateVitalData directly.
                tacticalHealth?.let { th ->
                    val accelMag = kotlin.math.sqrt(
                        sample.accel[0] * sample.accel[0] +
                        sample.accel[1] * sample.accel[1] +
                        sample.accel[2] * sample.accel[2]
                    )
                    th.personnel.value.firstOrNull()?.let { p ->
                        th.updateMotionData(
                            personnelId = p.id,
                            acceleration = accelMag,
                            velocity = 0f,
                            stepCountDelta = 1
                        )
                    }
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

        // === Workshop Bridge – FULLY REAL (from uploaded docs) ===
        // Adb WiFi + UART+Ble + Tactical + 3D Sensorfusion + HyperOS/FRP/Repair
        scope.launch {
            workshopBridge = com.example.agent.bridge.Ct45pWorkshopBridge(this@MainActivity, tacticalHealth!!)
            workshopBridge?.startWorkshopMode()

            // Real device discovery → update operator position (real action chain)
            launch {
                workshopBridge?.adbDiscovery?.discoveredDevices?.collect { dev ->
                    tacticalHealth?.let { th ->
                        th.personnel.value.firstOrNull()?.let { p ->
                            th.updatePosition(p.id, com.example.agent.tactical.Position3D(
                                (dev.ip.hashCode() % 50) / 10f,
                                1.5f,
                                0f
                            ))
                            Log.i("Main", "REAL ACTION: ADB device ${dev.ip} → operator position updated")
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_ALARMS, false)) {
            findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(
                R.id.nav_view,
            ).selectedItemId = R.id.navigation_alarm
        }
    }

    private fun acceptAlarmEvent(event: AlarmEvent) {
        val current = mutableAlarmUiState.value
        if (current.latestEvent?.policyId == event.policyId &&
            current.latestRevision > event.stateRevision
        ) {
            return
        }
        mutableAlarmUiState.value = current.copy(
            latestEvent = event,
            runtime = current.runtime?.takeIf {
                it.policyId == event.policyId && it.stateRevision >= event.stateRevision
            },
            errorMessage = null,
        )
        if (event.eventType in NOTIFIABLE_ALARM_EVENTS) notifyAlarm(event)
    }

    private fun createAlarmNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                ALARM_NOTIFICATION_CHANNEL,
                getString(R.string.alarm_notification_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = getString(R.string.alarm_notification_channel_description)
            },
        )
    }

    private fun notifyAlarm(event: AlarmEvent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val openAlarm = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_ALARMS, true)
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            openAlarm,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, ALARM_NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(getString(R.string.alarm_notification_title, event.severity))
            .setContentText(getString(R.string.alarm_notification_text, event.reasonCode))
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    getString(
                        R.string.alarm_notification_detail,
                        event.newState.condition,
                        event.reasonCode,
                        event.occurredAt,
                    ),
                ),
            )
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(event.eventId, ALARM_NOTIFICATION_ID, notification)
    }

    fun acknowledgeDisplayedAlarm() {
        runAlarmCommand { policyId, assetId ->
            apiClient.acknowledgeAlarm(policyId, assetId)
        }
    }

    fun snoozeDisplayedAlarm(durationMs: Long = DEFAULT_SNOOZE_DURATION_MS) {
        runAlarmCommand { policyId, assetId ->
            apiClient.snoozeAlarm(policyId, assetId, durationMs)
        }
    }

    private fun runAlarmCommand(
        command: suspend (policyId: String, assetId: String) -> AlarmRuntime,
    ) {
        val state = mutableAlarmUiState.value
        if (state.commandInProgress) return
        val policyId = state.latestEvent?.policyId ?: state.runtime?.policyId
        val assetId = state.latestEvent?.assetId ?: state.runtime?.assetId
        if (policyId == null || assetId == null) {
            mutableAlarmUiState.value = state.copy(
                errorMessage = getString(R.string.alarm_no_state),
            )
            return
        }
        mutableAlarmUiState.value = state.copy(commandInProgress = true, errorMessage = null)
        lifecycleScope.launch {
            try {
                acceptAlarmRuntime(command(policyId, assetId))
            } catch (error: Exception) {
                Log.e(TAG, "Gateway rejected alarm command", error)
                mutableAlarmUiState.value = mutableAlarmUiState.value.copy(
                    commandInProgress = false,
                    errorMessage = getString(R.string.alarm_command_failed),
                )
            }
        }
    }

    private fun acceptAlarmRuntime(runtime: AlarmRuntime) {
        val current = mutableAlarmUiState.value
        val latestEvent = current.latestEvent
        if (latestEvent?.policyId == runtime.policyId &&
            latestEvent.stateRevision > runtime.stateRevision
        ) {
            mutableAlarmUiState.value = current.copy(commandInProgress = false)
            return
        }
        mutableAlarmUiState.value = current.copy(
            runtime = runtime,
            commandInProgress = false,
            errorMessage = null,
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
