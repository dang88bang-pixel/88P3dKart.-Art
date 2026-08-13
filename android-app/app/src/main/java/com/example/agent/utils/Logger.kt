package com.example.agent.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Zentrale Logging-Komponente mit optionaler Datei-Persistenz. */
object Logger {
    private const val TAG = "3dxAgent"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private var logFile: File? = null

    fun init(context: Context) {
        val dir = File(context.filesDir, "logs")
        if (!dir.exists()) dir.mkdirs()
        logFile = File(dir, "agent.log")
    }

    fun d(message: String) { Log.d(TAG, message); write("DEBUG", message) }
    fun i(message: String) { Log.i(TAG, message); write("INFO", message) }
    fun w(message: String) { Log.w(TAG, message); write("WARN", message) }
    fun e(message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
        write("ERROR", message + (throwable?.let { "\n${it.stackTraceToString()}" } ?: ""))
    }

    private fun write(level: String, message: String) {
        try {
            logFile?.appendText("${dateFormat.format(Date())} [$level] $message\n")
        } catch (_: Exception) {
            // Logging-Fehler ignorieren
        }
    }

    fun getLogFile(): File? = logFile
}
