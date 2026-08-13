package com.example.agent.offline

import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Lokaler WebSocket-Server (Port 8080) für die Offline-Kommunikation
 * mit dem Web-Visualizer (Three.js).
 */
class LocalWebSocketServer(
    private val onBinaryPointCloud: (ByteArray) -> Unit,
    private val onStateUpdate: (String) -> Unit,
) {
    companion object {
        private const val TAG = "LocalWSServer"
    }

    private var server: WebSocketServer? = null

    fun start(port: Int = 8080) {
        if (server != null) return

        server = object : WebSocketServer(InetSocketAddress(port)) {
            override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
                Log.d(TAG, "Client verbunden: ${conn.remoteSocketAddress}")
            }

            override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
                Log.d(TAG, "Client getrennt: $reason")
            }

            override fun onMessage(conn: WebSocket, message: String) {
                try { handleClientCommand(message) } catch (e: Exception) {
                    Log.e(TAG, "Befehl fehlgeschlagen: ${e.message}")
                }
            }

            override fun onMessage(conn: WebSocket, data: ByteBuffer) {
                // Binäre Daten vom Client (offline nicht benötigt)
            }

            override fun onError(conn: WebSocket?, ex: Exception) {
                Log.e(TAG, "WebSocket-Fehler: ${ex.message}")
            }

            override fun onStart() {
                Log.d(TAG, "WebSocket-Server gestartet auf Port $port")
            }
        }
        server?.start()
    }

    private fun handleClientCommand(message: String) {
        val json = JSONObject(message)
        when (json.optString("type")) {
            "scenario_start" -> {
                val payload = json.optJSONObject("payload")
                val scenario = payload?.optString("scenario") ?: "unknown"
                onStateUpdate("Szenario $scenario gestartet")
            }
            "scenario_stop" -> onStateUpdate("Szenario gestoppt")
        }
    }

    /** Punktwolke als Binary-Blob senden: [uint32 N][N*3 float32]. */
    fun broadcastPointCloud(points: FloatArray) {
        val ws = server ?: return
        val n = points.size / 3
        val buffer = ByteBuffer.allocate(4 + points.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(n)
        for (p in points) buffer.putFloat(p)
        val data = buffer.array()
        for (conn in ws.connections) {
            try { conn.send(data) } catch (_: Exception) {}
        }
    }

    fun broadcastState(state: Map<String, Any>) {
        val json = JSONObject(state).toString()
        for (conn in server?.connections ?: emptyList()) {
            try { conn.send(json) } catch (_: Exception) {}
        }
    }

    fun stop() {
        try { server?.stop() } catch (_: Exception) {}
        server = null
        Log.d(TAG, "WebSocket-Server gestoppt")
    }
}
