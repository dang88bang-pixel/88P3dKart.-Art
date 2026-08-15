package com.example.agent.network

import android.util.Log
import com.example.agent.network.models.EkfState
import com.example.agent.sensors.BleTokenManager
import com.example.agent.sensors.SerialManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

/**
 * Robuster WebSocket-Client zum Edge-Agent (mit exponentiellem Backoff-Reconnect).
 */
class AgentWebSocketClient(
    private val serverUrl: String = "ws://192.168.1.100:8080/ws/agent/events",
) {
    companion object {
        private const val TAG = "AgentWS"
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var reconnectJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true }

    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onBinaryPointCloud: ((ByteArray) -> Unit)? = null
    var onEkfState: ((EkfState) -> Unit)? = null

    fun connect() {
        if (isConnected) return
        Log.d(TAG, "Verbinde zu $serverUrl ...")
        val request = Request.Builder().url(serverUrl).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                Log.d(TAG, "WebSocket verbunden")
                scope.launch(Dispatchers.Main) { onConnected?.invoke() }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val state = json.decodeFromString<EkfState>(text)
                    scope.launch(Dispatchers.Main) { onEkfState?.invoke(state) }
                } catch (e: Exception) {
                    Log.w(TAG, "JSON-Parse Fehler: $e")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                scope.launch(Dispatchers.Main) { onBinaryPointCloud?.invoke(bytes.toByteArray()) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                scope.launch(Dispatchers.Main) { onDisconnected?.invoke() }
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                Log.e(TAG, "WebSocket Fehler: ${t.message}")
                scope.launch(Dispatchers.Main) { onDisconnected?.invoke() }
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            var delayMs = 1000L
            while (!isConnected) {
                delay(delayMs)
                Log.d(TAG, "Reconnect-Versuch in ${delayMs}ms")
                connect()
                if (delayMs < 30000) delayMs = (delayMs * 1.5).toLong()
            }
        }
    }

    private fun sendPayload(type: String, payload: Map<String, Any?>) {
        if (!isConnected) return
        val msg = mapOf("type" to type, "payload" to payload)
        webSocket?.send(json.encodeToString(msg))
    }

    fun sendLidarFrame(deviceId: String, points: List<Float>, scattering: Boolean) {
        sendPayload(
            "lidar",
            mapOf(
                "device_id" to deviceId,
                "timestamp" to System.currentTimeMillis() / 1000.0,
                "points" to points,
                "scattering_detected" to scattering,
            )
        )
    }

    fun sendMmwaveTargets(deviceId: String, targets: List<SerialManager.MmwaveTarget>) {
        val list = targets.map { mapOf("x" to it.x, "y" to it.y, "z" to it.z, "v" to it.velocity) }
        sendPayload(
            "mmwave",
            mapOf(
                "device_id" to deviceId,
                "timestamp" to System.currentTimeMillis() / 1000.0,
                "targets" to list,
            )
        )
    }

    fun sendBleTokens(deviceId: String, tokens: List<BleTokenManager.TokenData>) {
        val list = tokens.map {
            mapOf(
                "mac" to it.mac, "rssi" to it.rssi,
                "accel_x" to it.accelX, "accel_y" to it.accelY, "accel_z" to it.accelZ,
                "battery" to it.battery,
                "type" to it.type,
                "temperature" to it.temperature,
                "flags" to it.flags,
            )
        }
        sendPayload(
            "ble",
            mapOf(
                "device_id" to deviceId,
                "timestamp" to System.currentTimeMillis() / 1000.0,
                "tokens" to list,
            )
        )
    }

    /** Überladung für direktes Senden von Accessory Payload Maps (z.B. SOS, Button Events) */
    fun sendBleTokens(deviceId: String, tokens: List<Map<String, Any?>>) {
        sendPayload(
            "ble",
            mapOf(
                "device_id" to deviceId,
                "timestamp" to System.currentTimeMillis() / 1000.0,
                "tokens" to tokens,
            )
        )
    }

    /** Vollständiges Bluetooth-Zubehör Paket an Edge-Agent senden */
    fun sendBluetoothAccessories(deviceId: String, accessories: List<com.example.agent.bluetooth.BluetoothAccessory>) {
        val list = accessories.map { it.toSignalPayload() }
        sendPayload(
            "bluetooth_accessories",
            mapOf(
                "device_id" to deviceId,
                "timestamp" to System.currentTimeMillis() / 1000.0,
                "accessories" to list,
                "count" to list.size,
            )
        )
    }

    /** Einzelnes SOS / Button Event */
    fun sendAccessoryEvent(deviceId: String, mac: String, eventType: String, payload: Map<String, Any?>) {
        sendPayload(
            "accessory_event",
            mapOf(
                "device_id" to deviceId,
                "timestamp" to System.currentTimeMillis() / 1000.0,
                "mac" to mac,
                "event_type" to eventType,
                "payload" to payload,
            )
        )
    }

    fun sendUwbPhase(deviceId: String, phase: Float) {
        sendPayload(
            "uwb_phase",
            mapOf(
                "device_id" to deviceId,
                "timestamp" to System.currentTimeMillis() / 1000.0,
                "phase" to phase,
            )
        )
    }

    fun sendTelemetry(deviceId: String, battery: Float, thermal: Float, scattering: Boolean) {
        sendPayload(
            "telemetry",
            mapOf(
                "device_id" to deviceId,
                "battery" to battery,
                "thermal_c" to thermal,
                "scattering" to scattering,
            )
        )
    }

    fun disconnect() {
        reconnectJob?.cancel()
        webSocket?.close(1000, "App beendet")
        isConnected = false
    }
}
