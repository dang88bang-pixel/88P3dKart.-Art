package com.example.agent.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.agent.tactical.TacticalForegroundService

/** Startet den Dauerbetrieb nach Boot oder App-Update automatisch. */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in HANDLED) return
        Log.i(TAG, "Boot/Update ($action) — starte Dauerbetrieb")
        runCatching { TacticalForegroundService.start(context.applicationContext) }
            .onFailure { Log.w(TAG, "FGS-Start nach Boot fehlgeschlagen: ${it.message}") }
    }

    companion object {
        private const val TAG = "BootCompleted"
        private val HANDLED = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
        )
    }
}
