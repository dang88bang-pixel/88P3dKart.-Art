package com.example.agent.pipeline

import com.example.agent.sensors.BleTokenManager
import com.example.agent.sensors.SerialManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Zeitsynchrone Frame-Erstellung aus den Sensor-Puffern.
 * Bündelt die zuletzt empfangenen Daten aller Sensoren zu einem Frame.
 */
class LiveSensorPipeline {

    companion object {
        private const val SYNC_WINDOW_MS = 50L
    }

    data class SensorFrame(
        val timestamp: Long,
        val lidarPoints: List<Float>? = null,
        val mmwaveTargets: List<SerialManager.MmwaveTarget>? = null,
        val bleTokens: List<BleTokenManager.TokenData>? = null,
        val uwbPhase: Float? = null,
        val imuOrientation: FloatArray? = null,
        val imuAccel: FloatArray? = null,
    )

    private data class Timed<T>(val t: Long, val value: T)

    private val lidarBuffer = ArrayDeque<Timed<List<Float>>>()
    private val mmwaveBuffer = ArrayDeque<Timed<List<SerialManager.MmwaveTarget>>>()
    private val bleBuffer = ArrayDeque<Timed<List<BleTokenManager.TokenData>>>()
    private val uwbBuffer = ArrayDeque<Timed<Float>>()
    private val imuBuffer = ArrayDeque<Timed<Pair<FloatArray, FloatArray>>>()

    private val _frameStream = MutableSharedFlow<SensorFrame>(extraBufferCapacity = 100)
    val frameStream: SharedFlow<SensorFrame> = _frameStream.asSharedFlow()

    private val maxBuffer = 200

    fun onLidar(points: List<Float>) = push(lidarBuffer, points)
    fun onMmwave(targets: List<SerialManager.MmwaveTarget>) = push(mmwaveBuffer, targets)
    fun onBle(tokens: List<BleTokenManager.TokenData>) = push(bleBuffer, tokens)
    fun onUwb(phase: Float) = push(uwbBuffer, phase)
    fun onImu(orientation: FloatArray, accel: FloatArray) = push(imuBuffer, orientation to accel)

    private fun <T> push(buffer: ArrayDeque<Timed<T>>, value: T) {
        buffer.addLast(Timed(System.currentTimeMillis(), value))
        if (buffer.size > maxBuffer) buffer.removeFirst()
    }

    /** Erstellt einen Frame aus den neuesten synchronen Daten (falls vorhanden). */
    fun tryCreateFrame(): SensorFrame? {
        val latest = listOfNotNull(
            lidarBuffer.lastOrNull()?.t,
            mmwaveBuffer.lastOrNull()?.t,
            bleBuffer.lastOrNull()?.t,
            uwbBuffer.lastOrNull()?.t,
            imuBuffer.lastOrNull()?.t,
        ).maxOrNull() ?: return null

        val frame = SensorFrame(
            timestamp = latest,
            lidarPoints = lidarBuffer.lastOrNull { latest - it.t <= SYNC_WINDOW_MS }?.value,
            mmwaveTargets = mmwaveBuffer.lastOrNull { latest - it.t <= SYNC_WINDOW_MS }?.value,
            bleTokens = bleBuffer.lastOrNull { latest - it.t <= SYNC_WINDOW_MS }?.value,
            uwbPhase = uwbBuffer.lastOrNull { latest - it.t <= SYNC_WINDOW_MS }?.value,
            imuOrientation = imuBuffer.lastOrNull { latest - it.t <= SYNC_WINDOW_MS }?.value?.first,
            imuAccel = imuBuffer.lastOrNull { latest - it.t <= SYNC_WINDOW_MS }?.value?.second,
        )
        _frameStream.tryEmit(frame)
        return frame
    }
}
