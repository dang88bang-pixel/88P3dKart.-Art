package com.example.agent.aura

/**
 * WireGuard-Tunnel-Konfigurations-Blueprint für den Aura-Tunnel-Link
 * (docs/AURA.md §3.2).
 *
 * Point-to-Point-Ansatz: ein Smartphone agiert als Hotspot, oder beide Geräte
 * sind in einem lokalen Ad-hoc-Netzwerk verbunden. Feste MTU 1420 verhindert
 * Fragmentierung (massive Paketverluste bei SDR-Streams); PersistentKeepalive
 * 25 s hält den Tunnel bei kurzzeitigen Funkunterbrechungen stabil.
 *
 * Das erzeugte INI-Format ist direkt mit `com.wireguard.android:tunnel`
 * kompatibel (Go-/wg-Format).
 */
object WireGuardConfigBuilder {

    const val TUNNEL_MTU = 1420
    const val LISTEN_PORT = 51820
    const val PERSISTENT_KEEPALIVE_SECONDS = 25

    const val ADDRESS_LEAD = "10.0.0.1/32"   // Smartphone A (Leitstelle)
    const val ADDRESS_SCANNER = "10.0.0.2/32" // Smartphone B (Scanner-Knoten)

    /** Android-Hotspot-Subnetz: 192.168.43.0/24 (Client-Adressen ab .2). */
    const val DEFAULT_ENDPOINT_SCANNER = "192.168.43.2:$LISTEN_PORT"

    data class TunnelBlueprint(
        val leadPrivateKey: String,
        val leadPublicKey: String,
        val scannerPrivateKey: String,
        val scannerPublicKey: String,
        val leadConfig: String,
        val scannerConfig: String,
    )

    /**
     * Erzeugt Schlüsselpaar + beide Peer-Konfigurationen.
     * @param scannerEndpoint Endpoint des Scanner-Knotens aus Sicht der Leitstelle
     *        (Hotspot-Netz: 192.168.43.2:51820).
     * @param leadEndpoint Endpoint der Leitstelle aus Sicht des Scanner-Knotens
     *        (Hotspot-Gateway: 192.168.43.1:51820).
     */
    fun createBlueprint(
        scannerEndpoint: String = DEFAULT_ENDPOINT_SCANNER,
        leadEndpoint: String = "192.168.43.1:$LISTEN_PORT",
    ): TunnelBlueprint {
        val leadKeys = WireGuardKeys.generateKeyPair()
        val scannerKeys = WireGuardKeys.generateKeyPair()
        return TunnelBlueprint(
            leadPrivateKey = leadKeys.privateKeyBase64,
            leadPublicKey = leadKeys.publicKeyBase64,
            scannerPrivateKey = scannerKeys.privateKeyBase64,
            scannerPublicKey = scannerKeys.publicKeyBase64,
            leadConfig = buildConfig(
                privateKey = leadKeys.privateKeyBase64,
                address = ADDRESS_LEAD,
                listenPort = LISTEN_PORT,
                peerPublicKey = scannerKeys.publicKeyBase64,
                allowedIps = ADDRESS_SCANNER,
                peerEndpoint = scannerEndpoint,
            ),
            scannerConfig = buildConfig(
                privateKey = scannerKeys.privateKeyBase64,
                address = ADDRESS_SCANNER,
                listenPort = LISTEN_PORT,
                peerPublicKey = leadKeys.publicKeyBase64,
                allowedIps = ADDRESS_LEAD,
                peerEndpoint = leadEndpoint,
            ),
        )
    }

    /** Baut eine vollständige WireGuard-INI für einen Peer. */
    fun buildConfig(
        privateKey: String,
        address: String,
        listenPort: Int = LISTEN_PORT,
        peerPublicKey: String,
        allowedIps: String,
        peerEndpoint: String? = null,
        mtu: Int = TUNNEL_MTU,
        persistentKeepalive: Int = PERSISTENT_KEEPALIVE_SECONDS,
    ): String = buildString {
        appendLine("[Interface]")
        appendLine("PrivateKey = $privateKey")
        appendLine("Address = $address")
        appendLine("ListenPort = $listenPort")
        appendLine("MTU = $mtu")
        appendLine()
        appendLine("[Peer]")
        appendLine("PublicKey = $peerPublicKey")
        appendLine("AllowedIPs = $allowedIps")
        if (peerEndpoint != null) appendLine("Endpoint = $peerEndpoint")
        appendLine("PersistentKeepalive = $persistentKeepalive")
    }
}
