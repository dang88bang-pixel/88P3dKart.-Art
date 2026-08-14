package com.example.agent.aura

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.util.Base64

class WireGuardKeysTest {

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0)
        return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    /** BigInteger aus 32 Byte little-endian (RFC 7748-Konvention). */
    private fun littleEndianToBigInteger(bytes: ByteArray): BigInteger {
        val reversed = bytes.reversedArray()
        return BigInteger(1, reversed)
    }

    @Test
    fun `RFC 7748 Testvektor 5_2 - X25519 Skalarmultiplikation`() {
        // a = a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4
        // u = e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c
        // X25519(a, u) = c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552
        val scalar = hexToBytes(
            "a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"
        )
        val uBytes = hexToBytes(
            "e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c"
        )
        val expected = "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552"

        WireGuardKeys.clamp(scalar) // X25519 klemmt den Skalar intern
        val result = WireGuardKeys.scalarMult(scalar, littleEndianToBigInteger(uBytes))
        val resultBytes = toLittleEndian(result)
        assertEquals(expected, bytesToHex(resultBytes))
    }

    @Test
    fun `RFC 7748 Testvektor 5_2 - oeffentlicher Schluessel X25519(a, 9)`() {
        val scalar = hexToBytes(
            "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"
        )
        val expectedPublic = "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"

        WireGuardKeys.clamp(scalar)
        val public = WireGuardKeys.publicFromPrivate(scalar)
        assertEquals(expectedPublic, bytesToHex(public))
    }

    @Test
    fun `RFC 7748 Testvektor 6_1 - Diffie-Hellman Shared Secret`() {
        // Alice-Privatschlüssel × Bob-Public-Key = gemeinsames Geheimnis
        val alicePrivate = hexToBytes(
            "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"
        )
        val bobPublic = hexToBytes(
            "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"
        )
        val expected = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"

        WireGuardKeys.clamp(alicePrivate)
        val shared = WireGuardKeys.scalarMult(alicePrivate, littleEndianToBigInteger(bobPublic))
        assertEquals(expected, bytesToHex(toLittleEndian(shared)))
    }

    @Test
    fun `generateKeyPair liefert geclampten 32-Byte-Key und passenden Public Key`() {
        val pair = WireGuardKeys.generateKeyPair()
        val priv = Base64.getDecoder().decode(pair.privateKeyBase64)
        val pub = Base64.getDecoder().decode(pair.publicKeyBase64)

        assertEquals(32, priv.size)
        assertEquals(32, pub.size)
        // Clamping-Bits
        assertEquals(0, priv[0].toInt() and 0b00000111)      // k[0] &= 248
        assertEquals(0, priv[31].toInt() and 0b10000000)     // k[31] &= 127
        assertEquals(64, priv[31].toInt() and 0b01000000)    // k[31] |= 64
        // Öffentlicher Schlüssel muss zur Ableitung passen
        assertArrayEquals(pub, WireGuardKeys.publicFromPrivate(priv))
    }

    @Test
    fun `configBlueprint erzeugt komplette INI-Konfigurationen fuer beide Peers`() {
        val blueprint = WireGuardConfigBuilder.createBlueprint()

        // Leitstelle (A)
        assertTrue(blueprint.leadConfig.contains("[Interface]"))
        assertTrue(blueprint.leadConfig.contains("Address = 10.0.0.1/32"))
        assertTrue(blueprint.leadConfig.contains("MTU = 1420"))
        assertTrue(blueprint.leadConfig.contains("ListenPort = 51820"))
        assertTrue(blueprint.leadConfig.contains("AllowedIPs = 10.0.0.2/32"))
        assertTrue(blueprint.leadConfig.contains("Endpoint = 192.168.43.2:51820"))
        assertTrue(blueprint.leadConfig.contains("PersistentKeepalive = 25"))
        assertTrue(blueprint.leadConfig.contains("PrivateKey = ${blueprint.leadPrivateKey}"))
        assertTrue(blueprint.leadConfig.contains("PublicKey = ${blueprint.scannerPublicKey}"))

        // Scanner (B)
        assertTrue(blueprint.scannerConfig.contains("Address = 10.0.0.2/32"))
        assertTrue(blueprint.scannerConfig.contains("AllowedIPs = 10.0.0.1/32"))
        assertTrue(blueprint.scannerConfig.contains("Endpoint = 192.168.43.1:51820"))
        assertTrue(blueprint.scannerConfig.contains("PrivateKey = ${blueprint.scannerPrivateKey}"))
        assertTrue(blueprint.scannerConfig.contains("PublicKey = ${blueprint.leadPublicKey}"))
    }

    /** BigInteger → 32 Byte little-endian (Testhilfe, RFC-Konvention). */
    private fun toLittleEndian(value: BigInteger): ByteArray {
        val bytes = ByteArray(32)
        val be = value.toByteArray()
        val src = if (be.size > 32) be.copyOfRange(be.size - 32, be.size)
        else ByteArray(32 - be.size) + be
        for (i in 0 until 32) bytes[i] = src[31 - i]
        return bytes
    }
}
