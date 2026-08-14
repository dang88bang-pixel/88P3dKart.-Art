package com.example.agent

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.agent.network.AgentWebSocketClient
import com.example.agent.aura.AuraIntegrator
import com.example.agent.pipeline.LiveSensorPipeline
import com.example.agent.pipeline.PipelineOrchestrator
import com.example.agent.triangulation.TriangulationService
import com.example.agent.sensors.BleTokenManager
import com.example.agent.sensors.EkfFusion
import com.example.agent.sensors.ImuManager
import com.example.agent.sensors.SerialManager
import com.example.agent.sensors.UwbManager
import com.example.agent.storage.AppDatabase
import com.example.agent.storage.SpatialRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    companion object {
        private const val REQUEST_PERMISSIONS = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        requestPermissions()

        // Hardware
        serialManager = SerialManager(this).also {
            it.initDevices()
            it.triggerLidarScan()
            it.configureMmwave(reduced = false)
        }
        bleManager = BleTokenManager(this).also { it.startScan() }
        imuManager = ImuManager(this).also { it.start() }
        uwbManager = UwbManager(this)

        ekf = EkfFusion(dt = 0.05f)
        db = AppDatabase.getInstance(this)
        pipeline = PipelineOrchestrator()
        livePipeline = LiveSensorPipeline()

        // Netzwerk
        webSocketClient = AgentWebSocketClient().also { it.connect() }

        // Aura (SDR/RTI) — docs/AURA.md §8.1
        auraIntegrator = AuraIntegrator().also {
            it.setPoseProvider { ekf.getState() }
        }
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
        // Tunnel-Empfänger defensiv starten (Port belegt → nur Log, keine Crashs)
        try {
            auraIntegrator.start()
        } catch (e: Exception) {
            Log.w("Aura", "Tunnel-Start übersprungen: ${e.message}")
        }

        // Triangulation (Wi-Fi RTT / BLE / Fingerprinting) — docs/TRIANGULATION.md
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

        // LiDAR → EKF + Pipeline + WebSocket
        scope.launch {
            serialManager.lidarPoints.collect { points ->
                if (points.isNotEmpty()) {
                    ekf.updateLidar(floatArrayOf(points[0], points[1], points[2]))
                    webSocketClient.sendLidarFrame("CT45P-01", points, scatteringDetected = false)
                    saveCurrentState()
                }
            }
        }

        // mmWave → EKF + WebSocket
        scope.launch {
            serialManager.mmwaveTargets.collect { targets ->
                if (targets.isNotEmpty()) {
                    val t = targets.first()
                    ekf.updateMmwave(floatArrayOf(t.x, t.y, t.z))
                    webSocketClient.sendMmwaveTargets("CT45P-01", targets)
                }
            }
        }

        // BLE-Token → WebSocket (+ Tag-Geschwindigkeit über Aura, sobald eine
        // Positionsquelle — UWB-Ranging oder RSSI-Triangulation — verfügbar ist:
        //   auraIntegrator.onTagPosition(token.mac, x, y, z)
        // siehe docs/AURA.md §6)
        scope.launch {
            bleManager.tokenUpdates.collect { token ->
                Log.d("BLE", "Token ${token.mac} RSSI=${token.rssi}")
                webSocketClient.sendBleTokens("CT45P-01", listOf(token))
            }
        }

        // UWB-Phase → WebSocket (Micro-Doppler)
        uwbManager.onPhase = { phase ->
            webSocketClient.sendUwbPhase("CT45P-01", phase)
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

    private fun requestPermissions() {
        val permissions = listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.UWB_RANGING,
        )
        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        webSocketClient.disconnect()
        auraIntegrator.stop()
        triangulation.stop()
        serialManager.close()
        bleManager.stopScan()
        imuManager.stop()
        uwbManager.stopRanging()
        scope.cancel()
    }
}
