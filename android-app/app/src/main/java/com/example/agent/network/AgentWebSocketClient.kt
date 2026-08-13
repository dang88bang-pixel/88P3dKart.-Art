package com.example.agent.network

import android.util.Log
import com.example.agent.network.models.EkfState
import com.example.agent.sensors.BleTokenManager
import com.example.agent.sensors.SerialManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/** WebSocket client for the gateway event/measurement interface. */
class AgentWebSocketClient(
    private val serverUrl: String?,
) {
    companion object {
        private const val TAG = "AgentWS"
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true }

    private var webSocket: WebSocket? = null
    @Volatile private var isConnected = false
    @Volatile private var isConnecting = false
    private var reconnectJob: Job? = null
    @Volatile private var closed = false

    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onBinaryPointCloud: ((ByteArray) -> Unit)? = null
    var onEkfState: ((EkfState) -> Unit)? = null

    @Synchronized
    fun connect() {
        if (isConnected || isConnecting || closed) return
        val url = serverUrl?.takeIf { it.startsWith("wss://") } ?: run {
            Log.w(TAG, "A wss:// gateway URL is required; connection disabled")
            return
        }
        isConnecting = true
        Log.d(TAG, "Connecting to configured gateway")
        val request = try {
            Request.Builder().url(url).build()
        } catch (error: IllegalArgumentException) {
            isConnecting = false
            Log.e(TAG, "Configured gateway URL is invalid", error)
            return
        }

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnecting = false
                isConnected = true
                reconnectJob?.cancel()
                Log.d(TAG, "WebSocket connected")
                scope.launch(Dispatchers.Main) { onConnected?.invoke() }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val state = json.decodeFromString<EkfState>(text)
                    scope.launch(Dispatchers.Main) { onEkfState?.invoke(state) }
                } catch (e: Exception) {
                    Log.w(TAG, "Rejected gateway JSON", e)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                scope.launch(Dispatchers.Main) { onBinaryPointCloud?.invoke(bytes.toByteArray()) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnecting = false
                isConnected = false
                scope.launch(Dispatchers.Main) { onDisconnected?.invoke() }
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnecting = false
                isConnected = false
                Log.e(TAG, "WebSocket failure: ${t.message}")
                scope.launch(Dispatchers.Main) { onDisconnected?.invoke() }
                scheduleReconnect()
            }
        })
    }

    @Synchronized
    private fun scheduleReconnect() {
        if (closed || reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            var delayMs = 1_000L
            while (!isConnected && !closed) {
                delay(delayMs)
                connect()
                delayMs = (delayMs * 2).coerceAtMost(30_000L)
            }
        }
    }

    private fun sendPayload(type: String, payload: JsonObject): Boolean {
        if (!isConnected || closed) return false
        val message = buildJsonObject {
            put("type", type)
            put("payload", payload)
        }
        return webSocket?.send(message.toString()) == true
    }

    fun sendLidarFrame(deviceId: String, points: List<Float>, scattering: Boolean?): Boolean =
        sendPayload(
            "lidar",
            buildJsonObject {
                put("device_id", deviceId)
                put("timestamp", System.currentTimeMillis() / 1000.0)
                put("points", buildJsonArray { points.forEach { add(it) } })
                put("scattering_detected", scattering?.let { JsonPrimitive(it) } ?: JsonNull)
            },
        )

    fun sendMmwaveTargets(
        deviceId: String,
        targets: List<SerialManager.MmwaveTarget>,
    ): Boolean = sendPayload(
        "mmwave",
        buildJsonObject {
            put("device_id", deviceId)
            put("timestamp", System.currentTimeMillis() / 1000.0)
            put("targets", buildJsonArray {
                targets.forEach { target ->
                    add(buildJsonObject {
                        put("x", target.x)
                        put("y", target.y)
                        put("z", target.z)
                        put("v", target.velocity)
                    })
                }
            })
        },
    )

    fun sendBleTokens(deviceId: String, tokens: List<BleTokenManager.TokenData>): Boolean =
        sendPayload(
            "ble",
            buildJsonObject {
                put("device_id", deviceId)
                put("timestamp", System.currentTimeMillis() / 1000.0)
                put("tokens", buildJsonArray {
                    tokens.forEach { token ->
                        add(buildJsonObject {
                            put("mac", token.mac)
                            put("rssi", token.rssi)
                            put("accel_x", token.accelX)
                            put("accel_y", token.accelY)
                            put("accel_z", token.accelZ)
                            put("battery", token.battery?.let { JsonPrimitive(it) } ?: JsonNull)
                            put("sequence", token.sequence)
                            put("protocol_version", token.protocolVersion)
                            put("imu_valid", token.imuValid)
                        })
                    }
                })
            },
        )

    fun sendTelemetry(
        deviceId: String,
        battery: Float?,
        thermal: Float?,
        scattering: Boolean,
    ): Boolean = sendPayload(
        "telemetry",
        buildJsonObject {
            put("device_id", deviceId)
            put("battery", battery?.let { JsonPrimitive(it) } ?: JsonNull)
            put("thermal_c", thermal?.let { JsonPrimitive(it) } ?: JsonNull)
            put("scattering", scattering)
        },
    )

    @Synchronized
    fun disconnect() {
        if (closed) return
        closed = true
        reconnectJob?.cancel()
        webSocket?.close(1000, "Client shutting down")
        webSocket = null
        isConnected = false
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        scope.cancel()
    }
}
