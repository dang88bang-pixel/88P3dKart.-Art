package com.example.agent.utils

import android.os.Handler
import android.os.Looper
import kotlin.math.sqrt
import kotlin.system.measureNanoTime

/** Laufzeit- und Speicher-Profiling für die Performance-Validierung. */
object Profiler {
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private val measurements = mutableMapOf<String, MutableList<Long>>()

    fun start() {
        if (isRunning) return
        isRunning = true
        handler.postDelayed({ collectMemoryStats() }, 5000)
    }

    fun stop() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
    }

    fun <T> measure(name: String, block: () -> T): T {
        var result: T? = null
        val duration = measureNanoTime { result = block() }
        synchronized(measurements) {
            measurements.getOrPut(name) { mutableListOf() }.add(duration)
        }
        return result!!
    }

    fun getStats(name: String): Map<String, Double> {
        val list = synchronized(measurements) { measurements[name]?.toList() } ?: return emptyMap()
        if (list.isEmpty()) return emptyMap()
        return mapOf(
            "count" to list.size.toDouble(),
            "avg_ms" to list.average() / 1e6,
            "min_ms" to list.min().toDouble() / 1e6,
            "max_ms" to list.max().toDouble() / 1e6,
            "std_ms" to list.stdDev() / 1e6,
        )
    }

    fun clear(name: String? = null) {
        synchronized(measurements) {
            if (name == null) measurements.clear() else measurements.remove(name)
        }
    }

    private fun collectMemoryStats() {
        if (!isRunning) return
        val runtime = Runtime.getRuntime()
        Logger.d("Memory: Used=${runtime.totalMemory() - runtime.freeMemory()} / ${runtime.maxMemory()}")
        handler.postDelayed({ collectMemoryStats() }, 10_000)
    }

    private fun List<Long>.stdDev(): Double {
        val avg = average()
        return sqrt(sumOf { (it - avg) * (it - avg) } / size)
    }
}
