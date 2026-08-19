package com.example.agent.sensors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/**
 * Erfassung von Drahtlosnetzwerkdaten (Wi-Fi, Mobilfunk).
 * Diese Signale dienen als zusätzliche Positions-/Kontextquelle.
 */
class NetworkDataCollector(private val context: Context) {

    private val wifiManager: WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val telephonyManager: TelephonyManager? =
        context.applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    fun collectWiFiData(): Map<String, Any?> {
        val wifi = wifiManager ?: return emptyMap()
        val info = wifi.connectionInfo
        return mapOf(
            "connected_ssid" to (info?.ssid ?: ""),
            "connected_rssi" to (info?.rssi ?: 0),
        )
    }

    fun collectCellularData(): Map<String, Any?> {
        val tm = telephonyManager ?: return emptyMap()
        return mapOf(
            "network_type" to tm.dataNetworkType,
            "operator" to (tm.networkOperatorName ?: ""),
        )
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}
