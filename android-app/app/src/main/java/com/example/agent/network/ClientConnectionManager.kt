package com.example.agent.network

import android.util.Log
import org.java_websocket.WebSocket
import java.util.concurrent.ConcurrentHashMap

/**
 * Verwaltet die Verbindungen (WebSocket/MQTT) der registrierten Clients.
 */
class ClientConnectionManager {

    companion object {
        private const val TAG = "ClientConnManager"
    }

    private val clients = ConcurrentHashMap<String, ClientRegistration>()
    private val webSocketSessions = ConcurrentHashMap<String, WebSocket>()

    fun registerClient(registration: ClientRegistration): Boolean {
        if (clients.containsKey(registration.clientId)) return false
        clients[registration.clientId] = registration
        Log.i(TAG, "Client registriert: ${registration.clientId} (${registration.type})")
        return true
    }

    fun handleWebSocketConnection(clientId: String, ws: WebSocket): Boolean {
        val client = clients[clientId] ?: return false
        webSocketSessions[clientId] = ws
        client.status = ClientStatus.ONLINE
        client.lastSeen = System.currentTimeMillis()
        Log.i(TAG, "WebSocket verbunden: $clientId")
        return true
    }

    fun getClient(clientId: String): ClientRegistration? = clients[clientId]
    fun getAllClients(): List<ClientRegistration> = clients.values.toList()
    fun getOnlineClients(): List<ClientRegistration> =
        clients.values.filter { it.status == ClientStatus.ONLINE }

    fun disconnectClient(clientId: String) {
        clients[clientId]?.status = ClientStatus.OFFLINE
        webSocketSessions.remove(clientId)?.close()
        Log.i(TAG, "Client getrennt: $clientId")
    }
}
