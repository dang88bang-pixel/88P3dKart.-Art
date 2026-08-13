package com.example.agent.network

/** Bewertet den Zustand eines Clients (Qualität, Verbindung, Batterie, Latenz). */
class ClientHealthEvaluator {

    data class ClientHealth(
        val status: String,     // "HEALTHY", "DEGRADED", "CRITICAL"
        val score: Float,
        val details: Map<String, Float>,
    )

    fun evaluate(client: ClientRegistration): ClientHealth {
        val qualityScore = client.dataQuality.coerceIn(0f, 1f)
        val connectionScore = if (client.status == ClientStatus.ONLINE) 1f else 0f
        val batteryScore = (client.batteryLevel / 100f).coerceIn(0f, 1f)
        val latencyScore = (1f - (client.networkLatency / 1000f).coerceIn(0f, 1f))

        val overall = qualityScore * 0.4f + connectionScore * 0.3f +
            batteryScore * 0.2f + latencyScore * 0.1f

        val status = when {
            overall > 0.8f -> "HEALTHY"
            overall > 0.5f -> "DEGRADED"
            else -> "CRITICAL"
        }

        return ClientHealth(
            status = status,
            score = overall,
            details = mapOf(
                "quality" to qualityScore,
                "connection" to connectionScore,
                "battery" to batteryScore,
                "latency" to latencyScore,
            ),
        )
    }
}
