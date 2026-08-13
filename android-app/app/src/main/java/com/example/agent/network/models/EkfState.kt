package com.example.agent.network.models

import kotlinx.serialization.Serializable

@Serializable
data class EkfState(
    val x: Float,
    val y: Float,
    val z: Float,
    val vx: Float,
    val vy: Float,
    val vz: Float,
    val covariance: List<List<Float>>,
    val kalman_gain_lidar: Float,
    val mode: String,
)

@Serializable
data class LidarFrame(
    val device_id: String,
    val timestamp: Double,
    val points: List<Float>,
    val scattering_detected: Boolean,
)
