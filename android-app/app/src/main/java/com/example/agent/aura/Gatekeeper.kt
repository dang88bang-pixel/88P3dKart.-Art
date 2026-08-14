package com.example.agent.aura

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.ArrayDeque

/**
 * Gatekeeper-Modul — digitaler Schutzschild für Funkraum und Netzwerk
 * (docs/AURA.md §5).
 *
 * Zwei Aufgaben:
 * 1. **RF-Anomalieerkennung:** kontinuierliche Spektrumsbeobachtung der
 *    ISM-Bänder 433/868 MHz; unerwartete, starke oder periodische Sender
 *    erzeugen Alerts (Triangulation über mehrere Scanner-Knoten möglich).
 * 2. **Netzwerkintegrität:** Validierung von Verbindungen innerhalb des
 *    WireGuard-Tunnels (IP/Port-Tupel, DNS-Heuristiken, Port-Scan-Erkennung),
 *    bevor Pakete die physische Netzwerkschnittstelle erreichen.
 *
 * Der Paketinspektor ist als reine Analyse-Engine implementiert und wird in der
 * Produktions-App an das `VpnService`-Interface (`com.wireguard.android:tunnel`)
 * gekoppelt (siehe docs/AURA.md §8 — Status ⏳).
 */
class Gatekeeper {

    companion object {
        private const val TAG = "Gatekeeper"

        /** Frequenz-Abweichung in Hz, ab der ein starker Sender als Anomalie gilt. */
        private const val ANOMALY_POWER_THRESHOLD = 8f

        /** Port-Scan-Schwelle: X verschiedene Zielports eines Senders in [SCAN_WINDOW_MS]. */
        private const val SCAN_PORT_THRESHOLD = 12
        private const val SCAN_WINDOW_MS = 10_000L

        /** Verdächtige DNS-Namensbestandteile (Tracking/Steuer-Server-Heuristik). */
        private val SUSPICIOUS_DNS_TOKENS = listOf(
            "track", "analytics", "telemetry", "metric", "beacon",
            "adservice", "doubleclick", "crashlytics", "adjust", "appsflyer",
        )
    }

    enum class Verdict { ALLOW, WARN, BLOCK }

    enum class Severity { INFO, WARNING, CRITICAL }

    /** Ein Alert für UI, Edge-Agent und 3D-Visualisierung. */
    data class GatekeeperAlert(
        val timestampMs: Long,
        val category: Category,
        val severity: Severity,
        val message: String,
        val frequencyHz: Double? = null,
        val sourceIp: String? = null,
        val sourcePort: Int? = null,
    ) {
        enum class Category { RF_ANOMALY, NETWORK_INTRUSION, PORT_SCAN, DNS_SUSPICIOUS }
    }

    /** Bewertetes Verbindungs-/Paket-Tupel. */
    data class PacketVerdict(
        val verdict: Verdict,
        val reason: String,
    )

    private val _alerts = MutableSharedFlow<GatekeeperAlert>(extraBufferCapacity = 100)
    val alerts: SharedFlow<GatekeeperAlert> = _alerts.asSharedFlow()

    // ── RF-Anomalieerkennung ─────────────────────────────────────────

    /** Bekannte/zulässige Sendermuster (Frequenz-Bandbreiten-Fenster). */
    private val knownTransmitters = mutableListOf<Pair<Double, Double>>()

    private var lastAlertMs = 0L
    private val alertCooldownMs = 5_000L

    /**
     * Registriert ein als legitim eingestuftes Sendermuster (z. B. eigene
     * Smart-Home-Sensoren), das keine Alerts mehr auslösen soll.
     */
    fun whitelistTransmitter(centerHz: Double, bandwidthHz: Double) {
        knownTransmitters.add(centerHz to bandwidthHz)
    }

    /**
     * Wertet ein Leistungsspektrum aus und emittiert Alerts für Anomalien.
     * @return belegte Bänder ([RfBandClassifier.OccupiedBand])
     */
    fun onSpectrum(
        powerSpectrum: FloatArray,
        sampleRateHz: Float,
        centerFrequencyHz: Double,
        nowMs: Long = System.currentTimeMillis(),
    ): List<RfBandClassifier.OccupiedBand> {
        val bands = RfBandClassifier.classify(powerSpectrum, sampleRateHz, centerFrequencyHz)

        for (b in bands) {
            val isKnown = knownTransmitters.any { (f, bw) ->
                kotlin.math.abs(f - b.centerHz) < bw / 2 + b.bandwidthHz / 2
            }
            if (isKnown) continue

            when (b.band) {
                RfBandClassifier.Band.UNKNOWN -> {
                    if (b.powerRatio > ANOMALY_POWER_THRESHOLD) {
                        emit(
                            category = GatekeeperAlert.Category.RF_ANOMALY,
                            severity = Severity.WARNING,
                            message = "Unbekannter Sender außerhalb der ISM-Überwachungsbänder: " +
                                "${formatMHz(b.centerHz)} MHz, Bandbreite ${formatKHz(b.bandwidthHz)} kHz " +
                                "(${b.modulation})",
                            frequencyHz = b.centerHz,
                            nowMs = nowMs,
                        )
                    }
                }
                RfBandClassifier.Band.ISM_433, RfBandClassifier.Band.ISM_868 -> {
                    // In den ISM-Bändern: nur bei sehr hoher Leistung melden
                    // (starke, unerwartete Bursts = möglicher Störer)
                    if (b.powerRatio > ANOMALY_POWER_THRESHOLD * 2f) {
                        emit(
                            category = GatekeeperAlert.Category.RF_ANOMALY,
                            severity = Severity.INFO,
                            message = "Starker Sender auf ${formatMHz(b.centerHz)} MHz " +
                                "(${b.band}, ${b.modulation}) — Prüfung empfohlen",
                            frequencyHz = b.centerHz,
                            nowMs = nowMs,
                        )
                    }
                }
            }
        }
        return bands
    }

    // ── Netzwerkintegrität (VpnService-Kopplung in Produktion) ───────

    private val portHits = ArrayDeque<Triple<String, Int, Long>>() // (srcIp, dstPort, ts)

    /** Validiert ein IP/Port-Tupel innerhalb des Tunnels. */
    fun inspectEndpoint(
        sourceIp: String,
        sourcePort: Int,
        destinationIp: String,
        destinationPort: Int,
        nowMs: Long = System.currentTimeMillis(),
    ): PacketVerdict {
        portHits.addLast(Triple(sourceIp, destinationPort, nowMs))
        while (portHits.isNotEmpty() && nowMs - portHits.first.third > SCAN_WINDOW_MS) {
            portHits.removeFirst()
        }
        if (portHits.size > 512) portHits.removeFirst()

        val distinctPorts = portHits
            .filter { it.first == sourceIp }
            .map { it.second }
            .toSet()
        if (distinctPorts.size >= SCAN_PORT_THRESHOLD) {
            emit(
                category = GatekeeperAlert.Category.PORT_SCAN,
                severity = Severity.CRITICAL,
                message = "Möglicher Port-Scan: ${distinctPorts.size} verschiedene Zielports " +
                    "von $sourceIp innerhalb ${SCAN_WINDOW_MS / 1000}s",
                sourceIp = sourceIp,
                sourcePort = sourcePort,
                nowMs = nowMs,
            )
            return PacketVerdict(Verdict.BLOCK, "Port-Scan-Heuristik überschritten")
        }
        return PacketVerdict(Verdict.ALLOW, "Tuple unauffällig")
    }

    /** DNS-Heuristik: verdächtige Abfragenamen → WARN/BLOCK. */
    fun inspectDnsQuery(hostname: String): PacketVerdict {
        val lower = hostname.lowercase()
        val hit = SUSPICIOUS_DNS_TOKENS.firstOrNull { lower.contains(it) }
        return if (hit != null) {
            emit(
                category = GatekeeperAlert.Category.DNS_SUSPICIOUS,
                severity = Severity.WARNING,
                message = "Verdächtige DNS-Abfrage: \"$hostname\" (Token \"$hit\") — " +
                    "mögliches Tracking-/Steuer-Framework",
                nowMs = System.currentTimeMillis(),
            )
            PacketVerdict(Verdict.WARN, "DNS-Heuristik: Token \"$hit\"")
        } else {
            PacketVerdict(Verdict.ALLOW, "DNS-Name unauffällig")
        }
    }

    private fun emit(
        category: GatekeeperAlert.Category,
        severity: Severity,
        message: String,
        frequencyHz: Double? = null,
        sourceIp: String? = null,
        sourcePort: Int? = null,
        nowMs: Long,
    ) {
        if (nowMs - lastAlertMs < alertCooldownMs) return
        lastAlertMs = nowMs
        _alerts.tryEmit(
            GatekeeperAlert(
                timestampMs = nowMs,
                category = category,
                severity = severity,
                message = message,
                frequencyHz = frequencyHz,
                sourceIp = sourceIp,
                sourcePort = sourcePort,
            )
        )
    }

    private fun formatMHz(hz: Double): String = String.format(java.util.Locale.US, "%.3f", hz / 1e6)
    private fun formatKHz(hz: Double): String = String.format(java.util.Locale.US, "%.1f", hz / 1e3)
}
