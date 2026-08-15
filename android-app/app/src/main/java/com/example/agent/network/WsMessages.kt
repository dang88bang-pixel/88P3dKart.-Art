package com.example.agent.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire-DTOs für den WebSocket-Client.
 *
 * Hintergrund: Die alte Implementierung baute jedes Frame per
 * `mapOf("type" to ..., "payload" to ...)` zusammen und rief
 * `Json.encodeToString<Map<String, Any?>>(…)` auf. `kotlinx.serialization`
 * kann `Any?` nicht serialisieren — die Folge war entweder eine
 * `SerializationException` zur Laufzeit oder ein leeres `{}`. Diese
 * DTOs ersetzen den Map-Pfad vollständig.
 *
 * JSON-Layout (unverändert, kompatibel zum Python-Edge-Agent):
 * ```
 * { "type": "<event>", "payload": { ...felder... } }
 * ```
 */
@Serializable
internal data class WsEnvelope<T>(
    val type: String,
    val payload: T,
)

@Serializable
internal data class WsMmwaveTarget(
    val x: Float,
    val y: Float,
    val z: Float,
    val v: Float,
)

@Serializable
internal data class WsMmwavePayload(
    @SerialName("device_id") val deviceId: String,
    val timestamp: Double,
    val targets: List<WsMmwaveTarget>,
)

@Serializable
internal data class WsBleToken(
    val mac: String,
    val rssi: Int,
    @SerialName("accel_x") val accelX: Float,
    @SerialName("accel_y") val accelY: Float,
    @SerialName("accel_z") val accelZ: Float,
    val battery: Int,
)

@Serializable
internal data class WsBlePayload(
    @SerialName("device_id") val deviceId: String,
    val timestamp: Double,
    val tokens: List<WsBleToken>,
)

@Serializable
internal data class WsUwbPhasePayload(
    @SerialName("device_id") val deviceId: String,
    val timestamp: Double,
    val phase: Float,
)

@Serializable
internal data class WsTelemetryPayload(
    @SerialName("device_id") val deviceId: String,
    val battery: Float,
    @SerialName("thermal_c") val thermalC: Float,
    val scattering: Boolean,
)

@Serializable
internal data class WsPositionPayload(
    @SerialName("device_id") val deviceId: String,
    val timestamp: Double,
    val source: String,
    val x: Double,
    val y: Double,
    val z: Double,
    @SerialName("accuracy_m") val accuracyM: Double,
    val confidence: Float,
)

@Serializable
internal data class WsAnchor(
    val id: String,
    val type: String,
    val x: Float,
    val y: Float,
    val z: Float,
)

@Serializable
internal data class WsTriangulationAnchorsPayload(
    @SerialName("device_id") val deviceId: String,
    val anchors: List<WsAnchor>,
)

@Serializable
internal data class WsAuraVoxel(
    val x: Float,
    val y: Float,
    val z: Float,
    val attenuation: Float,
    val weight: Float,
)

@Serializable
internal data class WsAuraVoxelsPayload(
    @SerialName("device_id") val deviceId: String,
    val timestamp: Double,
    val voxels: List<WsAuraVoxel>,
)

@Serializable
internal data class WsAuraHeatmapCell(
    val x: Float,
    val y: Float,
    val z: Float,
    val height: Float,
    val dbm: Float,
    val size: Float,
)

@Serializable
internal data class WsAuraHeatmapPayload(
    @SerialName("device_id") val deviceId: String,
    val timestamp: Double,
    val cells: List<WsAuraHeatmapCell>,
)
