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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.agent.health.DeviceHealthState
import com.example.agent.health.DeviceThermalMonitor
import com.example.agent.health.WorkloadMode
import com.example.agent.network.AgentApiClient
import com.example.agent.network.AgentWebSocketClient
import com.example.agent.network.GatewayEndpoint
import com.example.agent.network.models.AlarmEvent
import com.example.agent.network.models.AlarmRuntime
import com.example.agent.security.GatewaySessionManager
import com.example.agent.security.SecureCredentialStore
import com.example.agent.sensors.BleTokenManager
import com.example.agent.sensors.ImuManager
import com.example.agent.sensors.SerialManager
import com.example.agent.ui.alarm.AlarmFragment
import com.example.agent.ui.alarm.AlarmUiState
import com.example.agent.ui.live.LiveViewFragment
import com.example.agent.ui.map.MapFragment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Foreground CT45P control-plane shell and explicitly configured sensor relay. */
class MainActivity : AppCompatActivity() {
    private lateinit var serialManager: SerialManager
    private lateinit var bleManager: BleTokenManager
    private lateinit var imuManager: ImuManager
    private lateinit var thermalMonitor: DeviceThermalMonitor
    private var appliedWorkloadMode: WorkloadMode? = null
    lateinit var webSocketClient: AgentWebSocketClient
        private set
    lateinit var apiClient: AgentApiClient
        private set

    private lateinit var sessionManager: GatewaySessionManager
    private var enrolledDeviceId: String? = null
    private val mutableAlarmUiState = MutableStateFlow(AlarmUiState())
    val alarmUiState: StateFlow<AlarmUiState> = mutableAlarmUiState.asStateFlow()
    val deviceHealthState: StateFlow<DeviceHealthState>
        get() = thermalMonitor.state

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { startBleIfPermitted() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        createAlarmNotificationChannel()

        serialManager = SerialManager(this)
        bleManager = BleTokenManager(this)
        imuManager = ImuManager(this)
        thermalMonitor = DeviceThermalMonitor(this)

        val credentialStore = SecureCredentialStore(applicationContext)
        val enrollment = try {
            credentialStore.load()
        } catch (error: Exception) {
            Log.e(TAG, "Protected gateway enrollment is unavailable", error)
            null
        }
        if (enrollment == null) {
            Log.w(TAG, "Secure enrollment is required; control plane is disabled")
            startActivity(Intent(this, EnrollmentActivity::class.java))
            finish()
            return
        }

        enrolledDeviceId = enrollment.deviceId
        sessionManager = GatewaySessionManager(credentialStore)
        apiClient = AgentApiClient(
            baseUrl = enrollment.gatewayBaseUrl,
            sessionProvider = { sessionManager.validSession() },
            invalidateSession = { sessionManager.invalidateSession() },
        )
        webSocketClient = AgentWebSocketClient(
            serverUrl = GatewayEndpoint.websocketEvents(enrollment.gatewayBaseUrl),
            sessionProvider = { sessionManager.validSession() },
            invalidateSession = { sessionManager.invalidateSession() },
        ).also { client ->
            client.onConnected = {
                mutableAlarmUiState.value = mutableAlarmUiState.value.copy(
                    connected = true,
                    errorMessage = null,
                )
            }
            client.onDisconnected = {
                mutableAlarmUiState.value = mutableAlarmUiState.value.copy(connected = false)
            }
            client.onAlarmEvent = ::acceptAlarmEvent
        }

        setupNavigation(savedInstanceState)
        requestBlePermissions()
        collectMeasurements()
        webSocketClient.connect()
    }

    private fun setupNavigation(savedInstanceState: Bundle?) {
        val navigation = findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(
            R.id.nav_view,
        )
        navigation.setOnItemSelectedListener { item ->
            val fragment = when (item.itemId) {
                R.id.navigation_live -> LiveViewFragment()
                R.id.navigation_map -> MapFragment()
                R.id.navigation_alarm -> AlarmFragment()
                else -> return@setOnItemSelectedListener false
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.nav_host_fragment, fragment)
                .commit()
            true
        }
        if (savedInstanceState == null) {
            navigation.selectedItemId = if (intent.getBooleanExtra(EXTRA_OPEN_ALARMS, false)) {
                R.id.navigation_alarm
            } else {
                R.id.navigation_live
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

    override fun onStart() {
        super.onStart()
        if (enrolledDeviceId == null) return
        thermalMonitor.start()
        appliedWorkloadMode = thermalMonitor.state.value.workloadMode
        if (appliedWorkloadMode != WorkloadMode.PAUSED) startLocalSensors()
    }

    override fun onStop() {
        stopLocalSensors()
        thermalMonitor.stop()
        appliedWorkloadMode = null
        super.onStop()
    }

    private fun startLocalSensors() {
        serialManager.initDevices()
        serialManager.triggerLidarScan()
        startBleIfPermitted()
        imuManager.start()
    }

    private fun stopLocalSensors() {
        bleManager.stopScan()
        imuManager.stop()
        serialManager.stop()
    }

    private fun applyWorkloadPolicy(state: DeviceHealthState) {
        val previousMode = appliedWorkloadMode
        if (previousMode == state.workloadMode) return
        appliedWorkloadMode = state.workloadMode
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return

        if (state.workloadMode == WorkloadMode.PAUSED) {
            Log.w(TAG, "Pausing local sensor workload due to ${state.thermalStatus}")
            stopLocalSensors()
        } else if (previousMode == WorkloadMode.PAUSED) {
            Log.i(TAG, "Resuming local sensor workload after thermal recovery")
            startLocalSensors()
        }
    }

    private fun collectMeasurements() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    thermalMonitor.state.collect(::applyWorkloadPolicy)
                }
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
        val requested = REQUIRED_BLE_PERMISSIONS.toMutableList().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val missing = requested.filter {
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
        if (::webSocketClient.isInitialized) webSocketClient.disconnect()
        serialManager.close()
        bleManager.close()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val DEFAULT_SNOOZE_DURATION_MS = 15 * 60 * 1_000L
        private const val ALARM_NOTIFICATION_CHANNEL = "gateway_alarm_events"
        private const val ALARM_NOTIFICATION_ID = 1001
        private const val EXTRA_OPEN_ALARMS = "open_alarm_view"
        private val NOTIFIABLE_ALARM_EVENTS = setOf(
            "TRIGGERED",
            "DATA_LOSS_STARTED",
            "EVALUATION_ERROR",
            "SNOOZE_EXPIRED",
        )
        private val REQUIRED_BLE_PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }
}
