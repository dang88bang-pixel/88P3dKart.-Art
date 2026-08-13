package com.example.agent.utils

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Zentralisierte Fehlerbehandlung mit Recovery-Strategien. */
object ErrorRecovery {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun handleException(context: Context, throwable: Throwable, fallback: (() -> Unit)? = null) {
        Logger.e("Fehler aufgetreten", throwable)

        scope.launch(Dispatchers.Main) {
            Toast.makeText(
                context,
                "Fehler: ${throwable.message ?: "Unbekannter Fehler"}",
                Toast.LENGTH_LONG,
            ).show()
        }

        fallback?.invoke()

        if (throwable is OutOfMemoryError) {
            scope.launch {
                delay(1000)
                System.gc()
                Logger.i("Speicher bereinigt")
            }
        }
    }

    suspend fun retry(
        block: suspend () -> Unit,
        maxRetries: Int = 3,
        delayMs: Long = 1000,
    ): Boolean {
        var attempts = 0
        while (attempts < maxRetries) {
            try {
                block()
                return true
            } catch (e: Exception) {
                attempts++
                Logger.w("Retry $attempts/$maxRetries: ${e.message}")
                if (attempts < maxRetries) delay(delayMs * attempts)
            }
        }
        return false
    }
}
