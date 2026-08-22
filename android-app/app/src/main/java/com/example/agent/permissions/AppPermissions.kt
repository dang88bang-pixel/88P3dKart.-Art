package com.example.agent.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Zentrale, vollständige Berechtigungsliste für den CT45P-Dauerbetrieb.
 *
 * Phase 1 (Runtime, zusammen anfordern): alle dangerous Permissions außer
 * Hintergrund-Ortung. Phase 2: ACCESS_BACKGROUND_LOCATION (Android verlangt
 * das getrennt, nachdem Fine Location erteilt wurde).
 */
object AppPermissions {

    /** Dangerous permissions that may be requested together. */
    fun runtimeForeground(): Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        add(Manifest.permission.UWB_RANGING)
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.BODY_SENSORS)
        add(Manifest.permission.ACTIVITY_RECOGNITION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
            add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.READ_MEDIA_VIDEO)
            add(Manifest.permission.READ_MEDIA_AUDIO)
        }
        // BODY_SENSORS_BACKGROUND separat via ADB/deploy — kombinierter Dialog würde sonst abbrechen.
    }.toTypedArray()

    fun runtimeBackgroundLocation(): Array<String> =
        arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    fun allRuntime(): Array<String> = runtimeForeground() + runtimeBackgroundLocation()

    fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun missing(context: Context, permissions: Array<String>): List<String> =
        permissions.filterNot { granted(context, it) }

    fun hasForegroundRuntime(context: Context): Boolean =
        missing(context, runtimeForeground()).isEmpty()

    fun hasBackgroundLocation(context: Context): Boolean =
        granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun batteryOptimizationIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }

    /** ADB `pm grant` list used by releases/ct45p-deploy.sh. */
    val ADB_GRANT: List<String> = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.UWB_RANGING,
        Manifest.permission.NEARBY_WIFI_DEVICES,
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.BODY_SENSORS,
        Manifest.permission.BODY_SENSORS_BACKGROUND,
        Manifest.permission.ACTIVITY_RECOGNITION,
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_AUDIO,
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
    )
}
