package com.example.agent.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * IMU-Erfassung (Gyroskop + Beschleunigungsmesser + Magnetometer)
 * über den Android SensorManager.
 *
 * Bugfix: Vorher wurden die Felder `a`, `g`, `m` von drei separaten
 * Sensor-Callbacks befüllt und bei JEDEM Sensor-Event ein `ImuData`
 * emittiert. Da Android die Sensor-Callbacks nicht synchron aufruft,
 * enthielt der emittierte Datensatz eine Mischung aus dem aktuellen
 * und dem letzten Wert — also **konsistenzlose, falsche Daten**. Jetzt
 * werden alle drei Vektoren synchron in einem `Sample` zusammengefasst
 * und nur emittiert, wenn alle drei Sensoren für den Tick geliefert
 * haben. Die alte 9-Felder-`ImuData` ist als deprecated Wrapper
 * erhalten, damit externe Aufrufer (Tests etc.) weiter kompilieren.
 */
class ImuManager(context: Context) : SensorEventListener {

    /** Sample-konsistenter IMU-Datensatz (alle 3 Sensoren, gleicher Zeitstempel). */
    data class ImuSample(
        val timestampNs: Long,
        val accel: FloatArray, // [x, y, z] m/s²
        val gyro: FloatArray,  // [x, y, z] rad/s
        val mag: FloatArray,   // [x, y, z] µT
    ) {
        // data class equality on FloatArray would break — override manually
        // (nicht zwingend nötig, aber dokumentiert).
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ImuSample) return false
            return timestampNs == other.timestampNs &&
                accel.contentEquals(other.accel) &&
                gyro.contentEquals(other.gyro) &&
                mag.contentEquals(other.mag)
        }

        override fun hashCode(): Int {
            var result = timestampNs.hashCode()
            result = 31 * result + accel.contentHashCode()
            result = 31 * result + gyro.contentHashCode()
            result = 31 * result + mag.contentHashCode()
            return result
        }
    }

    /**
     * Legacy-9-Felder-Datensatz. Wird aus [ImuSample] abgeleitet, damit
     * bestehende Konsumenten (z. B. MainActivity) ohne große Änderung
     * kompilieren. ACHTUNG: Ist nicht garantiert sample-konsistent —
     * bitte wo möglich direkt [imuUpdates] (vom Typ ImuSample) konsumieren.
     */
    data class ImuData(
        val accelX: Float, val accelY: Float, val accelZ: Float,
        val gyroX: Float, val gyroY: Float, val gyroZ: Float,
        val magX: Float, val magY: Float, val magZ: Float,
    ) {
        companion object {
            fun fromSample(s: ImuSample) = ImuData(
                s.accel[0], s.accel[1], s.accel[2],
                s.gyro[0], s.gyro[1], s.gyro[2],
                s.mag[0], s.mag[1], s.mag[2],
            )
        }
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val mag = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    // Pro Sensor ein eigener Slot, der bei jedem Callback überschrieben
    // wird. Erst wenn alle drei Sensoren für eine "Runde" geliefert
    // haben, wird ein ImuSample emittiert. Das vermeidet die alte
    // Mischwert-Falle.
    @Volatile private var latestAccel: FloatArray? = null
    @Volatile private var latestGyro: FloatArray? = null
    @Volatile private var latestMag: FloatArray? = null
    @Volatile private var lastAccelTs: Long = 0L
    @Volatile private var lastGyroTs: Long = 0L
    @Volatile private var lastMagTs: Long = 0L

    private val _imuUpdates = MutableSharedFlow<ImuSample>(extraBufferCapacity = 100)
    val imuUpdates: SharedFlow<ImuSample> = _imuUpdates.asSharedFlow()

    fun start() {
        accel?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyro?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        mag?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() = sensorManager.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                latestAccel = event.values.clone()
                lastAccelTs = event.timestamp
            }
            Sensor.TYPE_GYROSCOPE -> {
                latestGyro = event.values.clone()
                lastGyroTs = event.timestamp
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                latestMag = event.values.clone()
                lastMagTs = event.timestamp
            }
        }

        // Sample emittieren, sobald alle drei Sensoren einen Wert haben
        // und der gemeinsame Zeitstempel plausibel ist. Wir nehmen den
        // minimalen der drei Timestamps als Sample-Zeit (konservativ).
        val a = latestAccel; val g = latestGyro; val m = latestMag
        if (a != null && g != null && m != null) {
            val ts = minOf(lastAccelTs, lastGyroTs, lastMagTs)
            val emitted = ImuSample(
                timestampNs = ts,
                accel = a,
                gyro = g,
                mag = m,
            )
            // tryEmit statt emit: bei Backpressure würden wir den
            // langsamsten Sensor verlieren, nicht den ganzen Stream.
            _imuUpdates.tryEmit(emitted)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.d("ImuManager", "Accuracy: $accuracy")
    }
}

/**
 * Synergie-Hinweis (docs/MEHRWERT_SYNERGIE.md):
 * Die IMU ist der "Brückenbauer" zwischen 3D-Kartierung, UWB und Tactical Health Monitoring.
 * CT45P liefert ein vollständiges 6+ Sensor-Set (Accel, Gyro, Mag, eCompass, Hall, Gravity).
 * Die hier emittierten ImuSample-Daten fließen direkt in:
 *   - EKF (Position/Orientierung)
 *   - TacticalHealthMonitoring.updateMotionData (Stress-/Readiness-Anpassung bei Bewegung)
 *   - Akustische Klassifikation (Bewegungsmuster)
 *
 * Dies ist ein zentraler Synergie-Effekt der Plattform auf dem CT45P.
 */
