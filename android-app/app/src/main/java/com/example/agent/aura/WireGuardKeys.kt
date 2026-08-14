package com.example.agent.aura

import java.math.BigInteger
import java.security.SecureRandom
import java.util.Base64

/**
 * Curve25519-Schlüsselgenerierung für den Aura-WireGuard-Tunnel (RFC 7748).
 *
 * Reine Kotlin-Implementierung (BigInteger) — bewusst ohne Android-Krypto-API,
 * damit das Modul als JVM-Unit-Test lauffähig ist und auf allen API-Leveln
 * identisch arbeitet. Für den Produktivbetrieb kann die X25519-Implementierung
 * der Plattform (`KeyPairGenerator.getInstance("X25519")`, API 33+) substituiert
 * werden; das Wire-Format (Base64, little-endian) ist identisch.
 */
object WireGuardKeys {

    /** Primzahl des Felds: 2²⁵⁵ − 19. */
    private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))

    /** A24-Konstante der Montgomery-Kurve Curve25519: (486662 − 2) / 4 = 121665. */
    private val A24: BigInteger = BigInteger.valueOf(121665)

    /** X-Koordinate des Basispunkts (u = 9). */
    private val BASE_U: BigInteger = BigInteger.valueOf(9)

    private val random = SecureRandom()

    data class KeyPair(
        val privateKeyBase64: String,
        val publicKeyBase64: String,
    )

    /** Erzeugt ein WireGuard-kompatibles Schlüsselpaar. */
    fun generateKeyPair(): KeyPair {
        val privateKey = ByteArray(32).also { random.nextBytes(it) }
        clamp(privateKey)
        val publicKey = publicFromPrivate(privateKey)
        return KeyPair(
            privateKeyBase64 = Base64.getEncoder().encodeToString(privateKey),
            publicKeyBase64 = Base64.getEncoder().encodeToString(publicKey),
        )
    }

    /**
     * Öffentlicher Schlüssel (u-Koordinate, 32 Byte little-endian) aus einem
     * bereits geclampten Private Key.
     */
    fun publicFromPrivate(clampedPrivateKey: ByteArray): ByteArray {
        require(clampedPrivateKey.size == 32) { "Private Key muss 32 Byte lang sein" }
        val u = scalarMult(clampedPrivateKey, BASE_U)
        return toLittleEndian(u, 32)
    }

    /** RFC 7748-Clamping: k[0] &= 248, k[31] &= 127, k[31] |= 64. */
    fun clamp(key: ByteArray) {
        require(key.size == 32) { "Private Key muss 32 Byte lang sein" }
        key[0] = (key[0].toInt() and 248).toByte()
        key[31] = (key[31].toInt() and 127 or 64).toByte()
    }

    /**
     * Montgomery-Leiter (RFC 7748 §5) für die Skalarmultiplikation auf
     * Curve25519. BigInteger-Arithmetik — langsam, aber korrekt und nur beim
     * einmaligen Schlüssel-Setup relevant.
     */
    internal fun scalarMult(k: ByteArray, u: BigInteger): BigInteger {
        var x1 = u.mod(P)
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = 0

        for (t in 254 downTo 0) {
            val kt = (k[t / 8].toInt() ushr (t % 8)) and 1
            swap = swap xor kt
            if (swap == 1) {
                val tx = x2; x2 = x3; x3 = tx
                val tz = z2; z2 = z3; z3 = tz
            }
            swap = kt

            val a = x2.add(z2).mod(P)
            val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2).mod(P)
            val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb).mod(P)
            val c = x3.add(z3).mod(P)
            val d = x3.subtract(z3).mod(P)
            val da = d.multiply(a).mod(P)
            val cb = c.multiply(b).mod(P)
            val x3n = da.add(cb).mod(P).pow(2).mod(P)
            val z3n = x1.multiply(da.subtract(cb).mod(P).pow(2)).mod(P)
            val x2n = aa.multiply(bb).mod(P)
            val z2n = e.multiply(aa.add(A24.multiply(e))).mod(P)

            x3 = x3n
            z3 = z3n
            x2 = x2n
            z2 = z2n
        }
        if (swap == 1) {
            val tx = x2; x2 = x3; x3 = tx
            val tz = z2; z2 = z3; z3 = tz
        }
        return x2.multiply(z2.modInverse(P)).mod(P)
    }

    /** BigInteger → 32 Byte little-endian (WireGuard-Format). */
    private fun toLittleEndian(value: BigInteger, length: Int): ByteArray {
        val bytes = ByteArray(length)
        val bigEndian = value.toByteArray() // vorzeichenbehaftet, BE
        // führende 0x00 (Vorzeichen) und ggf. Auffüllung berücksichtigen
        val src = if (bigEndian.size > length) {
            bigEndian.copyOfRange(bigEndian.size - length, bigEndian.size)
        } else {
            ByteArray(length - bigEndian.size) + bigEndian
        }
        for (i in 0 until length) bytes[i] = src[length - 1 - i]
        return bytes
    }
}
