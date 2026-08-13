package com.example.agent.pipeline

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Stufe 1 — Sensor-/Netzwerkdaten-Erfassung.
 *
 * Zentrale Aufnahme aller Sensor- und Netzwerkdaten mit Qualitätsbewertung.
 * Einheitlicher Datenpunkt [SensorDataPoint] mit Zeitstempel, Quelle und Qualität.
 */
class DataAcquisitionService(private val context: Context) {

    companion object {
        private const val TAG = "DataAcquisition"
        private const val MAX_BUFFER_SIZE = 10000
    }

    data class SensorDataPoint(
        val timestamp: Long = System.currentTimeMillis(),
        val source: String,
        val x: Float,
        val y: Float,
        val z: Float,
        val quality: Float = 1f,
    )

    private val _points = MutableSharedFlow<SensorDataPoint>(extraBufferCapacity = MAX_BUFFER_SIZE)
    val points: SharedFlow<SensorDataPoint> = _points.asSharedFlow()

    private val buffer = ConcurrentHashMap<Long, SensorDataPoint>()
    private val counter = AtomicLong(0)

    /** Nimmt flache Koordinaten [x1,y1,z1, x2,y2,z2, ...] auf. */
    fun ingest(flat: List<Float>, source: String = "lidar", quality: Float = 1f) {
        var i = 0
        while (i + 2 < flat.size) {
            val p = SensorDataPoint(
                source = source,
                x = flat[i], y = flat[i + 1], z = flat[i + 2],
                quality = quality,
            )
            buffer[counter.incrementAndGet()] = p
            _points.tryEmit(p)
            i += 3
        }
        if (buffer.size > MAX_BUFFER_SIZE) {
            // Älteste Einträge verwerfen
            val oldest = buffer.keys.sorted().take(buffer.size - MAX_BUFFER_SIZE)
            oldest.forEach { buffer.remove(it) }
        }
        Log.d(TAG, "Erfasst: ${flat.size / 3} Punkte (Quelle=$source, Qualität=$quality)")
    }

    fun snapshot(): List<SensorDataPoint> = buffer.values.toList()
    fun count(): Int = buffer.size
}
