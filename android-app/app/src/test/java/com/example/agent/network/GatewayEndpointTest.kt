package com.example.agent.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GatewayEndpointTest {
    @Test
    fun `only origin-form HTTPS gateway base is accepted`() {
        assertEquals(
            "https://gateway.example:8443/",
            GatewayEndpoint.normalizeHttpsBase("https://gateway.example:8443"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            GatewayEndpoint.normalizeHttpsBase("http://gateway.example")
        }
        assertThrows(IllegalArgumentException::class.java) {
            GatewayEndpoint.normalizeHttpsBase("https://user:secret@gateway.example")
        }
        assertThrows(IllegalArgumentException::class.java) {
            GatewayEndpoint.normalizeHttpsBase("https://gateway.example/untrusted/path")
        }
    }

    @Test
    fun `websocket endpoint preserves authority and enforces WSS`() {
        assertEquals(
            "wss://gateway.example:8443/ws/agent/events",
            GatewayEndpoint.websocketEvents("https://gateway.example:8443/"),
        )
    }
}
