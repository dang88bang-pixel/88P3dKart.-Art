package com.example.agent.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Strict gateway URL normalization shared by enrollment, REST, and WebSocket clients. */
object GatewayEndpoint {
    fun normalizeHttpsBase(value: String): String {
        require(value.length in 1..2048) { "Gateway URL length is invalid" }
        val parsed = value.trim().toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Gateway URL is invalid")
        require(parsed.scheme == "https") { "Gateway must use HTTPS" }
        require(parsed.username.isEmpty() && parsed.password.isEmpty()) {
            "Gateway URL must not contain user information"
        }
        require(parsed.query == null && parsed.fragment == null) {
            "Gateway URL must not contain a query or fragment"
        }
        require(parsed.encodedPath == "/") { "Gateway base URL must not contain a path" }
        return parsed.newBuilder().encodedPath("/").build().toString()
    }

    fun websocketEvents(baseUrl: String): String = parsedBase(baseUrl).newBuilder()
        .scheme("wss")
        .encodedPath("/ws/agent/events")
        .build()
        .toString()

    fun api(baseUrl: String, path: String): HttpUrl {
        require(path.startsWith("/api/v1/")) { "Only gateway API paths are accepted" }
        return parsedBase(baseUrl).newBuilder().encodedPath(path).build()
    }

    private fun parsedBase(baseUrl: String): HttpUrl =
        normalizeHttpsBase(baseUrl).toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Gateway URL is invalid")
}
