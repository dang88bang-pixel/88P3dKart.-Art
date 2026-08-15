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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Robuster WebSocket-Client zum Edge-Agent (mit exponentiellem Backoff-Reconnect).
 *
 * Bugfix-Historie (siehe Audit-Report):
 *  - Frühere Version serialisierte die Payloads per `Map<String, Any?>` mit
 *    `Json.encodeToString(...)`. `kotlinx.serialization` unterstützt `Any?`
 *    nicht → SerializationException / leeres `{}` zur Laufzeit. Jetzt mit
 *    typed DTOs (siehe `WsMessages.kt`).
 *  - Reconnect-Loop hatte eine Race-Condition: nach `connect()` war
 *    `isConnected` evtl. noch nicht `true`, die Schleife rief `connect()`
 *    erneut auf und öffnete mehrere parallele WebSockets. Jetzt über ein
 *    `AtomicBoolean isConnecting` serialisiert, max. Backoff begrenzt.
 */
class AgentWebSocketClient(
    private val serverUrl: String = "ws://192.168.1.100:8080/ws/agent/events",
) {
    companion object {
        private const val TAG = "AgentWS"
        private const val INITIAL_RECONNECT_DELAY_MS = 1_000L
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
        private const val BACKOFF_FACTOR = 1.5
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var webSocket: WebSocket? = null
    private val isConnected = AtomicBoolean(false)
    private val isConnecting = AtomicBoolean(false)
    private var reconnectJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onBinaryPointCloud: ((ByteArray) -> Unit)? = null
    var onEkfState: ((EkfState) -> Unit)? = null

    fun connect() {
        // Schutz gegen parallele Verbindungsaufbauten: ist bereits eine
        // Verbindung aktiv oder wird gerade aufgebaut, nichts tun.
        if (isConnected.get() || isConnecting.get()) return
        if (!isConnecting.compareAndSet(false, true)) return

        Log.d(TAG, "Verbinde zu $serverUrl ...")
        val request = Request.Builder().url(serverUrl).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected.set(true)
                isConnecting.set(false)
                Log.d(TAG, "WebSocket verbunden")
                scope.launch(Dispatchers.Main) { onConnected?.invoke() }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val state = json.decodeFromString<EkfState>(text)
                    scope.launch(Dispatchers.Main) { onEkfState?.invoke(state) }
                } catch (e: Exception) {
                    Log.w(TAG, "JSON-Parse Fehler: ${e.message}")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                scope.launch(Dispatchers.Main) { onBinaryPointCloud?.invoke(bytes.toByteArray()) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handleDisconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket Fehler: ${t.message}")
                handleDisconnect()
            }
        })
    }

    private fun handleDisconnect() {
        val wasConnected = isConnected.getAndSet(false)
        isConnecting.set(false)
        if (wasConnected) {
            scope.launch(Dispatchers.Main) { onDisconnected?.invoke() }
        }
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        // Vorherigen Reconnect-Job sauber beenden.
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            var delayMs = INITIAL_RECONNECT_DELAY_MS
            while (!isConnected.get()) {
                delay(delayMs)
                if (isConnected.get()) break
                Log.d(TAG, "Reconnect-Versuch in ${delayMs}ms")
                connect()
                // connect() ist nicht-suspending; bis der onOpen-Callback läuft
                // ist isConnected noch false. Wir warten weiter, falls der
                // Versuch fehlschlägt; bei Erfolg bricht die Schleife ab.
                delayMs = (delayMs * BACKOFF_FACTOR).toLong().coerceAtMost(MAX_RECONNECT_DELAY_MS)
            }
        }
    }

    /** Hilfsfunktion: serialisiert ein typisiertes DTO und schickt es als Text-Frame. */
    private inline fun <reified P> send(type: String, payload: P) {
        if (!isConnected.get()) return
        val envelope = WsEnvelope(type = type, payload = payload)
        val text = json.encodeToString(envelope)
        // send() ist false, wenn der Backlog voll ist; in dem Fall
        // loggen wir nur — der nächste Frame läuft wieder durch.
        val ok = webSocket?.send(text) ?: false
        if (!ok) Log.w(TAG, "send() zurückgewiesen (type=$type)")
    }

    fun sendLidarFrame(deviceId: String, points: List<Float>, scattering: Boolean) {
        send(
            "lidar",
            com.example.agent.network.models.LidarFrame(
                device_id = deviceId,
                timestamp = System.currentTimeMillis() / 1000.0,
                points = points,
                scattering_detected = scattering,
            ),
        )
    }

    fun sendMmwaveTargets(deviceId: String, targets: List<SerialManager.MmwaveTarget>) {
        if (targets.isEmpty()) return
        send(
            "mmwave",
            WsMmwavePayload(
                deviceId = deviceId,
                timestamp = System.currentTimeMillis() / 1000.0,
                targets = targets.map { WsMmwaveTarget(it.x, it.y, it.z, it.velocity) },
            ),
        )
    }

    fun sendBleTokens(deviceId: String, tokens: List<BleTokenManager.TokenData>) {
        if (tokens.isEmpty()) return
        send(
            "ble",
            WsBlePayload(
                deviceId = deviceId,
                timestamp = System.currentTimeMillis() / 1000.0,
                tokens = tokens.map {
                    WsBleToken(it.mac, it.rssi, it.accelX, it.accelY, it.accelZ, it.battery)
                },
            ),
        )
    }

    fun sendUwbPhase(deviceId: String, phase: Float) {
        send(
            "uwb_phase",
            WsUwbPhasePayload(
                deviceId = deviceId,
                timestamp = System.currentTimeMillis() / 1000.0,
                phase = phase,
            ),
        )
    }

    fun sendTelemetry(deviceId: String, battery: Float, thermal: Float, scattering: Boolean) {
        send(
            "telemetry",
            WsTelemetryPayload(
                deviceId = deviceId,
                battery = battery,
                thermalC = thermal,
                scattering = scattering,
            ),
        )
    }

    /** Triangulation: fusionierte Positionsschätzung (Wi-Fi RTT / BLE). */
    fun sendPositionEstimate(deviceId: String, estimate: com.example.agent.triangulation.PositionEstimate) {
        send(
            "position_update",
            WsPositionPayload(
                deviceId = deviceId,
                timestamp = System.currentTimeMillis() / 1000.0,
                source = estimate.source.name,
                x = estimate.x,
                y = estimate.y,
                z = estimate.z,
                accuracyM = estimate.accuracyM,
                confidence = estimate.confidence,
            ),
        )
    }

    /** Triangulation: Anker-Konfiguration für Karte/Visualizer. */
    fun sendTriangulationAnchors(
        deviceId: String,
        wifi: List<com.example.agent.triangulation.WifiRttTriangulator.RttAnchor>,
        ble: List<com.example.agent.triangulation.BleBeaconTriangulator.BeaconAnchor>,
    ) {
        val anchors = wifi.map { WsAnchor(it.id, "wifi", it.x, it.y, it.z) } +
            ble.map { WsAnchor(it.id, "ble", it.x, it.y, it.z) }
        if (anchors.isEmpty()) return
        send("triangulation_anchors", WsTriangulationAnchorsPayload(deviceId, anchors))
    }

    /** Aura: rekonstruierte RTI-Voxel an den Edge-Agent (→ Web-Visualizer). */
    fun sendAuraVoxels(deviceId: String, voxels: List<com.example.agent.aura.RtiSolver.Voxel>) {
        if (voxels.isEmpty()) return
        send(
            "aura_voxels",
            WsAuraVoxelsPayload(
                deviceId = deviceId,
                timestamp = System.currentTimeMillis() / 1000.0,
                voxels = voxels.map {
                    WsAuraVoxel(it.x, it.y, it.z, it.attenuation, it.weight)
                },
            ),
        )
    }

    /** Aura: extrudierte RF-Heatmap-Zellen an den Edge-Agent (→ Web-Visualizer). */
    fun sendAuraHeatmap(
        deviceId: String,
        cells: List<com.example.agent.aura.RfHeatmapBuilder.ExtrudedCell>,
    ) {
        if (cells.isEmpty()) return
        send(
            "aura_heatmap",
            WsAuraHeatmapPayload(
                deviceId = deviceId,
                timestamp = System.currentTimeMillis() / 1000.0,
                cells = cells.map {
                    WsAuraHeatmapCell(
                        x = it.centerX,
                        y = it.centerY,
                        z = it.baseZ,
                        height = it.heightM,
                        dbm = it.dbm,
                        size = it.cellSizeM,
                    )
                },
            ),
        )
    }

    fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        try {
            webSocket?.close(1000, "App beendet")
        } catch (_: Exception) {
            // bewusst geschluckt — beim App-Shutdown ist das OK
        }
        webSocket = null
        isConnected.set(false)
        isConnecting.set(false)
    }
}
