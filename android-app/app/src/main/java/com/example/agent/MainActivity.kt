package com.example.agent

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.agent.network.AgentWebSocketClient
import com.example.agent.sensors.BleTokenManager
import com.example.agent.sensors.ImuManager
import com.example.agent.sensors.SerialManager
import kotlinx.coroutines.launch

/** Foreground CT45P control-plane shell and explicitly configured sensor relay. */
class MainActivity : AppCompatActivity() {
    private lateinit var serialManager: SerialManager
    private lateinit var bleManager: BleTokenManager
    private lateinit var imuManager: ImuManager
    lateinit var webSocketClient: AgentWebSocketClient
        private set

    private var enrolledDeviceId: String? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { startBleIfPermitted() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        enrolledDeviceId = preferences.getString(KEY_DEVICE_ID, null)
            ?.takeIf(::isValidDeviceId)
        val gatewayUrl = preferences.getString(KEY_GATEWAY_WEBSOCKET_URL, null)

        serialManager = SerialManager(this)
        bleManager = BleTokenManager(this)
        imuManager = ImuManager(this)
        webSocketClient = AgentWebSocketClient(gatewayUrl)

        requestBlePermissions()
        collectMeasurements()

        if (enrolledDeviceId == null || gatewayUrl == null) {
            Log.w(TAG, "Enrollment/gateway configuration missing; network relay is disabled")
        } else {
            webSocketClient.connect()
        }
    }

    override fun onStart() {
        super.onStart()
        serialManager.initDevices()
        serialManager.triggerLidarScan()
        startBleIfPermitted()
        imuManager.start()
    }

    override fun onStop() {
        bleManager.stopScan()
        imuManager.stop()
        serialManager.stop()
        super.onStop()
    }

    private fun collectMeasurements() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    serialManager.lidarPoints.collect { points ->
                        enrolledDeviceId?.let { deviceId ->
                            webSocketClient.sendLidarFrame(
                                deviceId = deviceId,
                                points = points,
                                scattering = null,
                            )
                        }
                    }
                }
                launch {
                    serialManager.mmwaveTargets.collect { targets ->
                        enrolledDeviceId?.let { deviceId ->
                            webSocketClient.sendMmwaveTargets(deviceId, targets)
                        }
                    }
                }
                launch {
                    bleManager.tokenUpdates.collect { token ->
                        Log.d(TAG, "BLE token ${token.mac} RSSI=${token.rssi}")
                        enrolledDeviceId?.let { deviceId ->
                            webSocketClient.sendBleTokens(deviceId, listOf(token))
                        }
                    }
                }
            }
        }
    }

    private fun requestBlePermissions() {
        val missing = REQUIRED_BLE_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startBleIfPermitted() {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
            REQUIRED_BLE_PERMISSIONS.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }
        ) {
            bleManager.startScan()
        }
    }

    override fun onDestroy() {
        webSocketClient.disconnect()
        serialManager.close()
        bleManager.close()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFERENCES_NAME = "runtime_configuration"
        private const val KEY_DEVICE_ID = "enrolled_device_id"
        private const val KEY_GATEWAY_WEBSOCKET_URL = "gateway_websocket_url"
        private val DEVICE_ID_PATTERN = Regex("[A-Za-z0-9._:-]{1,128}")
        private val REQUIRED_BLE_PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )

        private fun isValidDeviceId(value: String): Boolean = DEVICE_ID_PATTERN.matches(value)
    }
}
