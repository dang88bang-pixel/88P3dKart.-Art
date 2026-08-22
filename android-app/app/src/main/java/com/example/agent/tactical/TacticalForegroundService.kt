package com.example.agent.tactical

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.agent.MainActivity
import com.example.agent.R
import com.example.agent.sensors.BluetoothAccessoryScanService

/**
 * Hält IMU, BLE-Scan, USB-Sensoren und taktische Health-Pipeline im
 * Vordergrund, auch wenn die Activity nicht sichtbar ist (CT45P am Gürtel).
 */
class TacticalForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        acquireWakeLock()
        Log.i(TAG, "Dauerbetrieb-Service erzeugt")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = buildNotification(getString(R.string.tactical_fgs_running))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
        startBleScanService()
        Log.i(TAG, "Dauerbetrieb aktiv")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseWakeLock()
        Log.i(TAG, "Dauerbetrieb-Service beendet")
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Prozess am Leben halten, auch wenn die Task-Liste geleert wird.
        TacticalServiceHelper.start(applicationContext)
        super.onTaskRemoved(rootIntent)
    }

    private fun startBleScanService() {
        val scan = Intent(this, BluetoothAccessoryScanService::class.java)
            .putExtra("scan_mode", "BALANCED")
        runCatching { ContextCompat.startForegroundService(this, scan) }
            .onFailure { Log.w(TAG, "BLE-Scan-Service: ${it.message}") }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.tactical_fgs_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.tactical_fgs_channel_description)
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(content: String): Notification {
        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.tactical_fgs_title))
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pending)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "TacticalFGS"
        private const val CHANNEL_ID = "tactical_dauerbetrieb"
        private const val NOTIF_ID = 2001
        private const val WAKELOCK_TAG = "3dxAgent:TacticalFGS"
        const val ACTION_START = "com.example.agent.tactical.START"
        const val ACTION_STOP = "com.example.agent.tactical.STOP"

        fun start(context: Context) = TacticalServiceHelper.start(context)
        fun stop(context: Context) = TacticalServiceHelper.stop(context)
    }
}

object TacticalServiceHelper {
    fun start(context: Context) {
        val intent = Intent(context, TacticalForegroundService::class.java)
            .setAction(TacticalForegroundService.ACTION_START)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        context.stopService(
            Intent(context, TacticalForegroundService::class.java)
                .setAction(TacticalForegroundService.ACTION_STOP),
        )
    }
}
