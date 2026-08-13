package com.example.agent.network

import android.util.Log
import com.example.agent.network.models.AlarmEvent
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
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    private val sessionProvider: suspend () -> GatewaySession?,
    private val invalidateSession: () -> Unit,
) {
    companion object {
        private const val TAG = "AgentWS"
        private const val AUTHENTICATION_CLOSE_CODE = 4401
        private const val SESSION_RENEWAL_CLOSE_CODE = 4001
        private const val SESSION_RENEWAL_MARGIN_SECONDS = 60L
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
    private var renewalJob: Job? = null
    private var connectionGeneration = 0L
    @Volatile private var closed = false

    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onBinaryPointCloud: ((ByteArray) -> Unit)? = null
    var onEkfState: ((EkfState) -> Unit)? = null
    var onAlarmEvent: ((AlarmEvent) -> Unit)? = null

    @Synchronized
    fun connect() {
        if (isConnected || isConnecting || closed) return
        val url = serverUrl?.takeIf { it.startsWith("wss://") } ?: run {
            Log.w(TAG, "A wss:// gateway URL is required; connection disabled")
            return
        }
        isConnecting = true
        val generation = ++connectionGeneration
        scope.launch {
            val session = try {
                sessionProvider()
            } catch (error: Exception) {
                Log.e(TAG, "Could not obtain a gateway session", error)
                null
            }
            if (session == null) {
                if (markTerminated(generation)) scheduleReconnect()
                return@launch
            }
            if (!isCurrentConnectionAttempt(generation)) return@launch
            openAuthenticatedSocket(url, session, generation)
        }
    }

    @Synchronized
    private fun openAuthenticatedSocket(url: String, session: GatewaySession, generation: Long) {
        if (!isCurrentConnectionAttempt(generation)) return
        Log.d(TAG, "Connecting to configured gateway")
        val request = try {
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${session.accessToken}")
                .build()
        } catch (error: IllegalArgumentException) {
            markTerminated(generation)
            Log.e(TAG, "Configured gateway URL is invalid", error)
            return
        }

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!markConnected(generation)) {
                    webSocket.close(1000, "Superseded connection")
                    return
                }
                scheduleSessionRenewal(session.expiresAtEpochSeconds, generation)
                Log.d(TAG, "WebSocket connected")
                scope.launch(Dispatchers.Main) { onConnected?.invoke() }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!isCurrent(generation)) return
                handleJsonMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (!isCurrent(generation)) return
                scope.launch(Dispatchers.Main) { onBinaryPointCloud?.invoke(bytes.toByteArray()) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!isCurrent(generation)) return
                if (code == AUTHENTICATION_CLOSE_CODE) invalidateSession()
                if (!markTerminated(generation)) return
                scope.launch(Dispatchers.Main) { onDisconnected?.invoke() }
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!isCurrent(generation)) return
                if (response?.code == 401 || response?.code == 403) invalidateSession()
                if (!markTerminated(generation)) return
                Log.e(TAG, "WebSocket connection failed", t)
                scope.launch(Dispatchers.Main) { onDisconnected?.invoke() }
                scheduleReconnect()
            }
        })
    }

    private fun handleJsonMessage(text: String) {
        try {
            val root = json.parseToJsonElement(text).jsonObject
            if (root["type"]?.jsonPrimitive?.content == "alarm_event") {
                val payload = root["payload"] ?: error("Missing alarm event payload")
                val event = json.decodeFromJsonElement<AlarmEvent>(payload)
                scope.launch(Dispatchers.Main) { onAlarmEvent?.invoke(event) }
            } else {
                val state = json.decodeFromString<EkfState>(text)
                scope.launch(Dispatchers.Main) { onEkfState?.invoke(state) }
            }
        } catch (error: Exception) {
            Log.w(TAG, "Rejected gateway JSON", error)
        }
    }

    @Synchronized
    private fun markConnected(generation: Long): Boolean {
        if (closed || generation != connectionGeneration) return false
        isConnecting = false
        isConnected = true
        reconnectJob?.cancel()
        return true
    }

    @Synchronized
    private fun markTerminated(generation: Long): Boolean {
        if (generation != connectionGeneration) return false
        connectionGeneration += 1
        isConnecting = false
        isConnected = false
        webSocket = null
        renewalJob?.cancel()
        return !closed
    }

    @Synchronized
    private fun isCurrent(generation: Long): Boolean =
        !closed && generation == connectionGeneration

    @Synchronized
    private fun isCurrentConnectionAttempt(generation: Long): Boolean =
        isConnecting && isCurrent(generation)

    @Synchronized
    private fun scheduleSessionRenewal(expiresAtEpochSeconds: Long, generation: Long) {
        renewalJob?.cancel()
        val now = System.currentTimeMillis() / 1000L
        val delaySeconds = (expiresAtEpochSeconds - SESSION_RENEWAL_MARGIN_SECONDS - now)
            .coerceAtLeast(0L)
        renewalJob = scope.launch {
            delay(delaySeconds * 1_000L)
            renewSession(generation)
        }
    }

    @Synchronized
    private fun renewSession(generation: Long) {
        if (!isConnected || !isCurrent(generation)) return
        invalidateSession()
        webSocket?.close(SESSION_RENEWAL_CLOSE_CODE, "Renewing gateway session")
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

    @Synchronized
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
        connectionGeneration += 1
        reconnectJob?.cancel()
        renewalJob?.cancel()
        webSocket?.close(1000, "Client shutting down")
        webSocket = null
        isConnected = false
        isConnecting = false
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        scope.cancel()
    }
}
