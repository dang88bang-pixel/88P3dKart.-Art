package com.example.agent.network

/** Client-Typen der 3dxAgent-Plattform. */
enum class ClientType { MASTER, RELAY, SENSOR, GATEWAY, WEARABLE, TOKEN }

/** Verfügbare Sensor-/Datenquellen eines Clients. */
enum class SensorType { LIDAR, MMWAVE, UWB, BLE, IMU, GPS, TEMPERATURE, HUMIDITY, AIR_QUALITY }

enum class ClientStatus { ONLINE, OFFLINE, ERROR, DEGRADED, PAUSED }

data class ClientCapabilities(
    val deviceType: ClientType,
    val sensorTypes: List<SensorType>,
    val maxFrequency: Float,
    val batteryPowered: Boolean,
    val hasPositioning: Boolean,
    val hasIMU: Boolean,
    val hasBLE: Boolean,
    val supportsMesh: Boolean,
    val supportsSecureConnection: Boolean,
)

data class ClientRegistration(
    val clientId: String,
    val type: ClientType,
    val capabilities: ClientCapabilities,
    val apiKey: String,
    val jwtSecret: String? = null,
    val certificate: String? = null,
    val allowedTopics: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    var lastSeen: Long = System.currentTimeMillis(),
    var status: ClientStatus = ClientStatus.ONLINE,
    var batteryLevel: Int = 100,
    var dataQuality: Float = 0.9f,
    var networkLatency: Float = 0f,
)

/** Ein eingehendes Signal eines Clients. */
data class ClientSignal(
    val clientId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val type: String = "sensor_data",
    val sensorType: SensorType,
    val deviceType: ClientType,
    val payload: Map<String, Any?> = emptyMap(),
    val metadata: Map<String, Any?> = emptyMap(),
    val quality: Float = 0.9f,
)
