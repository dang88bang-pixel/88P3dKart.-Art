package com.example.agent.health

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Lifecycle-bound public Android thermal/battery monitor for the CT45P control plane. */
class DeviceThermalMonitor(context: Context) {
    private val applicationContext = context.applicationContext
    private val powerManager = applicationContext.getSystemService(PowerManager::class.java)
    private val mutableState = MutableStateFlow(DeviceHealthState())
    val state: StateFlow<DeviceHealthState> = mutableState.asStateFlow()

    private var started = false
    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        updateThermalStatus(status)
    }
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) updateBattery(intent)
        }
    }

    @Synchronized
    fun start() {
        if (started) return
        started = true
        powerManager.addThermalStatusListener(thermalListener)
        updateThermalStatus(powerManager.currentThermalStatus)
        ContextCompat.registerReceiver(
            applicationContext,
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            // Required for system-originated sticky broadcasts; BATTERY_CHANGED is protected.
            ContextCompat.RECEIVER_EXPORTED,
        )?.let(::updateBattery)
    }

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        powerManager.removeThermalStatusListener(thermalListener)
        applicationContext.unregisterReceiver(batteryReceiver)
    }

    private fun updateThermalStatus(platformStatus: Int) {
        val status = when (platformStatus) {
            PowerManager.THERMAL_STATUS_NONE -> DeviceThermalStatus.NONE
            PowerManager.THERMAL_STATUS_LIGHT -> DeviceThermalStatus.LIGHT
            PowerManager.THERMAL_STATUS_MODERATE -> DeviceThermalStatus.MODERATE
            PowerManager.THERMAL_STATUS_SEVERE -> DeviceThermalStatus.SEVERE
            PowerManager.THERMAL_STATUS_CRITICAL -> DeviceThermalStatus.CRITICAL
            PowerManager.THERMAL_STATUS_EMERGENCY -> DeviceThermalStatus.EMERGENCY
            PowerManager.THERMAL_STATUS_SHUTDOWN -> DeviceThermalStatus.SHUTDOWN
            else -> DeviceThermalStatus.UNKNOWN
        }
        val previous = mutableState.value
        mutableState.value = previous.copy(
            thermalStatus = status,
            workloadMode = DeviceThermalPolicy.workloadMode(status, previous.workloadMode),
        )
    }

    private fun updateBattery(intent: Intent) {
        val tenthsCelsius = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        mutableState.value = mutableState.value.copy(
            batteryTemperatureC = tenthsCelsius.takeIf { it != Int.MIN_VALUE }?.div(10f),
            batteryPercent = if (level >= 0 && scale > 0) {
                (level * 100f / scale).coerceIn(0f, 100f)
            } else {
                null
            },
        )
    }
}
