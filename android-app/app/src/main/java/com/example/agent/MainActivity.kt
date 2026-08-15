package com.example.agent

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.agent.bluetooth.BluetoothAccessory
import com.example.agent.bluetooth.BluetoothAccessoryManager
import com.example.agent.bluetooth.BluetoothAccessoryType
import com.example.agent.network.AgentWebSocketClient
import com.example.agent.network.ClientRegistry
import com.example.agent.pipeline.PipelineOrchestrator
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
    private lateinit var clientRegistry: ClientRegistry
    lateinit var webSocketClient: AgentWebSocketClient

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        private const val REQUEST_PERMISSIONS = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        requestPermissions()

        // Registry für einheitliches Client-Health/Recovery
        clientRegistry = ClientRegistry()

        // Hardware
        serialManager = SerialManager(this).also {
            it.initDevices()
            it.triggerLidarScan()
            it.configureMmwave(reduced = false)
        }
        bleManager = BleTokenManager(this, clientRegistry).also {
            // High-Accuracy für taktische Einsätze – erkennt alle Zubehörtypen
            it.startHighAccuracyScan()
        }
        imuManager = ImuManager(this).also { it.start() }
        uwbManager = UwbManager(this)

        ekf = EkfFusion(dt = 0.05f)
        db = AppDatabase.getInstance(this)
        pipeline = PipelineOrchestrator()

        // Netzwerk
        webSocketClient = AgentWebSocketClient().also { it.connect() }

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

        // BLE-Token (legacy) → WebSocket
        scope.launch {
            bleManager.tokenUpdates.collect { token ->
                Log.d("BLE", "Token ${token.mac} RSSI=${token.rssi} type=${token.type}")
                webSocketClient.sendBleTokens("CT45P-01", listOf(token))
            }
        }

        // NEU: Vollständiges Bluetooth-Zubehör Ökosystem
        scope.launch {
            bleManager.accessoryUpdates.collect { accessory ->
                handleAccessoryUpdate(accessory)
            }
        }

        scope.launch {
            bleManager.bluetoothAccessoryManager.events.collect { event ->
                when (event) {
                    is BluetoothAccessoryManager.AccessoryEvent.SosTriggered -> {
                        Log.w("BT-ACCESSORY", "🚨 SOS von ${event.accessory.macAddress} (${event.accessory.name})")
                        webSocketClient.sendAccessoryEvent(
                            "CT45P-01",
                            event.accessory.macAddress,
                            "sos",
                            event.accessory.toSignalPayload()
                        )
                    }
                    is BluetoothAccessoryManager.AccessoryEvent.ButtonPressed -> {
                        Log.i("BT-ACCESSORY", "Button Press ${event.accessory.macAddress}")
                        webSocketClient.sendAccessoryEvent(
                            "CT45P-01",
                            event.accessory.macAddress,
                            "button",
                            event.accessory.toSignalPayload()
                        )
                    }
                    else -> {}
                }
            }
        }

        scope.launch {
            bleManager.bluetoothAccessoryManager.accessories.collect { list ->
                if (list.isNotEmpty()) {
                    val summary = list.groupBy { it.type }.mapValues { it.value.size }
                    Log.i("BT-ACCESSORY", "Zubehör Übersicht: $summary • gesamt ${list.size}")
                }
            }
        }

        // UWB-Phase → WebSocket (Micro-Doppler)
        uwbManager.onPhase = { phase ->
            webSocketClient.sendUwbPhase("CT45P-01", phase)
        }

        // Telemetrie + periodischer Accessory Dump (alle 5 s)
        scope.launch {
            while (true) {
                delay(5000)
                val accessories = bleManager.getAllAccessories()
                val lowBat = accessories.count { it.batteryLevel < 20 }
                webSocketClient.sendTelemetry("CT45P-01", 85f, 45f, false)
                if (accessories.isNotEmpty()) {
                    webSocketClient.sendBluetoothAccessories("CT45P-01", accessories)
                }
                if (lowBat > 0) Log.w("BT-ACCESSORY", "$lowBat Zubehör mit niedrigem Akku")
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

    private suspend fun handleAccessoryUpdate(accessory: BluetoothAccessory) {
        // Weiterleitung als ClientSignal für Pipeline Integration
        // Sensor-Umgebungsdaten → Kontext, Wearable → Person, Asset-Tag → Beacon, etc.
        val payload = accessory.toSignalPayload()
        when (accessory.type) {
            BluetoothAccessoryType.SENSOR_TAG -> {
                // Umwelt-Daten (Temperatur/Feuchte/Luft) für BIM und Evakuierungssimulation
                Log.d("SENSOR-TAG", "${accessory.macAddress} T=${accessory.temperatureC} H=${accessory.humidityPct}")
            }
            BluetoothAccessoryType.WEARABLE -> {
                // Vitaldaten (HR, Steps) → Avatar Status im taktischen Szenario
                Log.d("WEARABLE", "${accessory.macAddress} HR=${accessory.heartRateBpm} Steps=${accessory.steps}")
            }
            BluetoothAccessoryType.REMOTE_CONTROLLER -> {
                // Fernbedienung für Szenario-Steuerung
                if (accessory.buttonState != 0) {
                    Log.i("REMOTE", "Button ${accessory.buttonState} von ${accessory.macAddress}")
                }
            }
            else -> {}
        }
    }

    private fun mapTokenFromAccessory(acc: BluetoothAccessory, sos: Boolean = false): Map<String, Any?> {
        return acc.toSignalPayload() + mapOf(
            "sos" to sos,
            "flags" to if (sos) acc.flags or com.example.agent.bluetooth.AccessoryFlags.SOS else acc.flags,
        )
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
        val permissions = mutableListOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.UWB_RANGING,
        )
        // Background Location für Foreground Service ab Android Q
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        // Legacy BT für Android <12
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }
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
        serialManager.close()
        bleManager.bluetoothAccessoryManager.cleanup()
        imuManager.stop()
        uwbManager.stopRanging()
        scope.cancel()
    }
}
