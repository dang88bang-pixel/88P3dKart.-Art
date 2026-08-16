package com.example.agent.sensors

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.agent.bluetooth.BluetoothAccessoryManager
import com.example.agent.network.ClientRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Foreground-Service für kontinuierliches BLE-Zubehör Scanning im taktischen Einsatz.
 *
 * Wird nur gestartet wenn die App im Hintergrund weiter scannen muss
 * (z.B. bei BOS Einsätzen mit CT45P am Gürtel).
 */
class BluetoothAccessoryScanService : Service() {

    companion object {
        private const val TAG = "BTScanService"
        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "ble_scan_foreground"
    }

    private var manager: BluetoothAccessoryManager? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var registry: ClientRegistry? = null

    override fun onCreate() {
        super.onCreate()
        registry = ClientRegistry()
        manager = BluetoothAccessoryManager(this, registry)
        createNotificationChannel()
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val modeStr = intent?.getStringExtra("scan_mode") ?: "BALANCED"
        val mode = try { BluetoothAccessoryManager.ScanMode.valueOf(modeStr) } catch (_: Exception) { BluetoothAccessoryManager.ScanMode.BALANCED }

        startForeground(NOTIF_ID, buildNotification("Scan läuft: $mode"))

        manager?.startScan(mode)

        scope.launch {
            manager?.accessories?.collect { list ->
                val text = "${list.size} Zubehör erkannt • Tokens: ${list.count { it.type.name.contains("TOKEN") }}"
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIF_ID, buildNotification(text))
            }
        }

        Log.i(TAG, "Foreground Scan gestartet: $mode")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        manager?.cleanup()
        Log.i(TAG, "Service destroyed")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "BLE Zubehör Scan",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Kontinuierliches Scannen von Bluetooth-Zubehör im Einsatz"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(content: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationCompat.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            NotificationCompat.Builder(this)
        }
        return builder
            .setContentTitle("3dxAgent – Bluetooth Zubehör")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
