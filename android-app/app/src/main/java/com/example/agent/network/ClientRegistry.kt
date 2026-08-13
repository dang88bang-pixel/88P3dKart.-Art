package com.example.agent.network

import java.util.concurrent.ConcurrentHashMap

/** Zentrale Registry aller verbundenen Clients. */
class ClientRegistry {

    private val clients = ConcurrentHashMap<String, ClientRegistration>()
    private val signals = ConcurrentHashMap<String, MutableList<ClientSignal>>()

    fun register(client: ClientRegistration): Boolean {
        if (clients.containsKey(client.clientId)) return false
        clients[client.clientId] = client
        signals[client.clientId] = mutableListOf()
        return true
    }

    fun unregister(clientId: String) {
        clients.remove(clientId)
        signals.remove(clientId)
    }

    fun updateStatus(clientId: String, status: ClientStatus) {
        clients[clientId]?.status = status
        clients[clientId]?.lastSeen = System.currentTimeMillis()
    }

    fun getClient(clientId: String): ClientRegistration? = clients[clientId]
    fun getAllClients(): List<ClientRegistration> = clients.values.toList()
    fun getOnlineClients(): List<ClientRegistration> =
        clients.values.filter { it.status == ClientStatus.ONLINE }

    fun addSignal(clientId: String, signal: ClientSignal) {
        val list = signals.getOrPut(clientId) { mutableListOf() }
        synchronized(list) {
            list.add(signal)
            if (list.size > 1000) list.removeAt(0)
        }
    }

    fun getSignals(clientId: String, limit: Int = 100): List<ClientSignal> {
        val list = signals[clientId] ?: return emptyList()
        return synchronized(list) { list.takeLast(limit) }
    }
}
