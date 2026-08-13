package com.example.agent.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * IMU-Erfassung (Gyroskop + Beschleunigungsmesser + Magnetometer)
 * über den Android SensorManager.
 */
class ImuManager(context: Context) : SensorEventListener {

    data class ImuData(
        val accelX: Float, val accelY: Float, val accelZ: Float,
        val gyroX: Float, val gyroY: Float, val gyroZ: Float,
        val magX: Float, val magY: Float, val magZ: Float,
    )

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val mag = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private var a = FloatArray(3); private var g = FloatArray(3); private var m = FloatArray(3)

    private val _imuUpdates = MutableSharedFlow<ImuData>(extraBufferCapacity = 100)
    val imuUpdates: SharedFlow<ImuData> = _imuUpdates.asSharedFlow()

    fun start() {
        accel?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyro?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        mag?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() = sensorManager.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> a = event.values.clone()
            Sensor.TYPE_GYROSCOPE -> g = event.values.clone()
            Sensor.TYPE_MAGNETIC_FIELD -> m = event.values.clone()
        }
        _imuUpdates.tryEmit(ImuData(a[0], a[1], a[2], g[0], g[1], g[2], m[0], m[1], m[2]))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.d("ImuManager", "Accuracy: $accuracy")
    }
}
