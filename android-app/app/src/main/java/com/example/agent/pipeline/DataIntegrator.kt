package com.example.agent.pipeline

import android.util.Log
import com.example.agent.offline.MotionDetector
import com.example.agent.offline.OpenHPSAdapter
import com.example.agent.offline.SmartMeshIntegrator
import com.example.agent.sensors.EkfFusion

/**
 * Integriert interpretierte Client-Signale in EKF, Mesh und Semantik.
 */
class DataIntegrator(
    private val ekf: EkfFusion,
    private val meshIntegrator: SmartMeshIntegrator,
    private val motionDetector: MotionDetector,
    private val trilateration: OpenHPSAdapter = OpenHPSAdapter,
) {
    companion object {
        private const val TAG = "DataIntegrator"
    }

    fun integrateSignal(signal: InterpretedSignal) {
        if (signal.quality < 0.5f) {
            Log.d(TAG, "Signal verworfen (Qualität: ${signal.quality})")
            return
        }

        when (signal.semanticType) {
            "geometry" -> integrateLidar(signal)
            "moving_person", "stationary_person" -> integrateMmwave(signal)
            "beacon" -> integrateBle(signal)
            "motion" -> integrateImu(signal)
            "environment" -> integrateEnvironment(signal)
            else -> Log.d(TAG, "Unbekannter Signaltyp: ${signal.semanticType}")
        }
    }

    private fun integrateLidar(signal: InterpretedSignal) {
        val points = signal.rawData.payload["points"] as? List<Number> ?: return
        val flat = points.map { it.toFloat() }
        if (flat.isNotEmpty()) {
            ekf.updateLidar(floatArrayOf(flat[0], flat[1], flat.getOrElse(2) { 0f }))
            meshIntegrator.addPoints(flat, confidence = signal.quality, motionScore = 0f)
        }
    }

    private fun integrateMmwave(signal: InterpretedSignal) {
        val targets = signal.rawData.payload["targets"] as? List<Map<String, Any?>> ?: return
        for (t in targets) {
            val x = (t["x"] as? Number)?.toFloat() ?: 0f
            val y = (t["y"] as? Number)?.toFloat() ?: 0f
            val z = (t["z"] as? Number)?.toFloat() ?: 0f
            val v = (t["v"] as? Number)?.toFloat() ?: 0f
            ekf.updateMmwave(floatArrayOf(x, y, z))
            val motion = motionDetector.detectFromMmwave(v)
            meshIntegrator.addPoints(
                listOf(x, y, z),
                semanticType = if (v > 0.5f) "person" else "stationary",
                confidence = signal.quality,
                motionScore = motion,
            )
        }
    }

    private fun integrateBle(signal: InterpretedSignal) {
        val rssi = (signal.rawData.payload["rssi"] as? Number)?.toInt() ?: return
        // Distanzschätzung (zur späteren Trilateration in die Pipeline einspeisen)
        OpenHPSAdapter.distanceFromRssi(rssi)
    }

    private fun integrateImu(signal: InterpretedSignal) {
        val accel = (signal.rawData.payload["accel"] as? List<Number>)?.map { it.toFloat() }
        if (accel != null && accel.size >= 3) {
            ekf.predict()
            motionDetector.detectFromImu(accel[0], accel[1], accel[2])
        }
    }

    private fun integrateEnvironment(signal: InterpretedSignal) {
        val temp = (signal.rawData.payload["temperature"] as? Number)?.toFloat() ?: return
        if (temp > 60f) ekf.adaptToEnvironment(false, temp)
    }
}
