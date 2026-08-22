package com.example.agent.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Zentrale Berechtigungs- und Capability-Prüfung für BLE + Classic Bluetooth Zubehör.
 */
object BluetoothPermissions {

    val REQUIRED_PERMISSIONS: List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            add(Manifest.permission.BLUETOOTH)
            add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    val OPTIONAL_PERMISSIONS: List<String> = listOf(
        Manifest.permission.UWB_RANGING,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        Manifest.permission.NEARBY_WIFI_DEVICES,
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.BLUETOOTH_SCAN,
    )

    fun hasAllPermissions(context: Context): Boolean =
        REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    fun missingPermissions(context: Context): List<String> =
        REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

    fun isBluetoothSupported(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH) &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

    fun isBluetoothEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter?.isEnabled == true
    }

    fun getBluetoothAdapter(context: Context): BluetoothAdapter? {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter
    }

    /** Prüft ob wir im Hintergrund scannen dürfen (Android 10+ Einschränkungen) */
    fun canScanInBackground(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                hasAllPermissions(context)
        } else true
    }
}
