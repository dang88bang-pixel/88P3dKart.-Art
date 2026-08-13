package com.example.agent.offline

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.URLDecoder

/**
 * Leichtgewichtiger lokaler REST-Server (Port 8081) auf dem CT45P.
 *
 * Hinweis: Für Produktivsysteme kann dieser durch NanoHTTPD oder Ktor ersetzt
 * werden; diese Implementierung nutzt nur die JDK-Standard-API (ServerSocket),
 * um ohne zusätzliche schwere Abhängigkeiten offline lauffähig zu sein.
 */
class LocalApiServer(private val port: Int = 8081) {

    companion object {
        private const val TAG = "LocalApiServer"
    }

    /** Pfad → Handler, der die JSON-Antwort als String zurückgibt. */
    private val routes = LinkedHashMap<String, (JSONObject) -> JSONObject>()

    private var serverSocket: ServerSocket? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    fun registerRoute(path: String, handler: (JSONObject) -> JSONObject) {
        routes[path] = handler
    }

    fun start() {
        if (running) return
        running = true
        thread = Thread {
            try {
                serverSocket = ServerSocket(port)
                Log.d(TAG, "Lokaler API-Server auf Port $port")
                while (running) {
                    val socket = serverSocket?.accept() ?: break
                    Thread { handle(socket) }.start()
                }
            } catch (e: Exception) {
                if (running) Log.e(TAG, "API-Server-Fehler: ${e.message}")
            }
        }
        thread?.isDaemon = true
        thread?.start()
    }

    private fun handle(socket: java.net.Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1].substringBefore("?")

            // Header überspringen
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }

            val handler = routes[path]
            val body = JSONObject()

            val status = if (handler != null) "200 OK" else "404 Not Found"
            val responseBody = handler?.invoke(body)?.toString() ?: """{"error":"not found"}"""

            val out: OutputStream = socket.getOutputStream()
            val payload = responseBody.toByteArray(Charsets.UTF_8)
            val head = (
                "HTTP/1.1 $status\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Content-Length: ${payload.size}\r\n" +
                "Connection: close\r\n\r\n"
            ).toByteArray(Charsets.UTF_8)
            out.write(head)
            out.write(payload)
            out.flush()
            socket.close()
        } catch (_: Exception) {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        Log.d(TAG, "API-Server gestoppt")
    }
}
