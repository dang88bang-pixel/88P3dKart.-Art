package com.example.agent.aura

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Aura-Datagramm-Format für den SDR-Tunnel-Link (siehe docs/AURA.md §3.3).
 *
 * Header (12 Byte, Big-Endian):
 * ```
 * Bytes 0..3   Sequenznummer (UInt32) — fortlaufender Zähler, Lückenerkennung
 * Bytes 4..11  Zeitstempel (UInt64)   — Systemzeit in Mikrosekunden zum
 *                                        Erfassungszeitpunkt (Phasen-Sync)
 * ```
 *
 * Payload: rohe IQ-Werte (1 Byte I, 1 Byte Q, verschachtelt).
 * MTU 1420 − 12 Byte Header = 1408 Byte Nutzlast = **704 IQ-Paare pro Paket** —
 * unter der kritischen Fragmentierungsgrenze der meisten WLAN-Netzwerke.
 *
 * Durchsatz-Referenz: 2,4 MS/s × 8 Bit × 2 (I+Q) ≈ 38,4 Mbit/s
 * ([bitsPerSecond]).
 */
object IqDatagram {

    const val HEADER_SIZE = 12
    const val TUNNEL_MTU = 1420

    /** Maximale Payload-Größe: 1420 − 12 = 1408 Byte. */
    const val MAX_PAYLOAD_SIZE = TUNNEL_MTU - HEADER_SIZE

    /** IQ-Paare pro Paket bei 8-Bit-Samples: 1408 / 2 = 704. */
    const val IQ_PAIRS_PER_DATAGRAM = MAX_PAYLOAD_SIZE / 2

    const val MAX_DATAGRAM_SIZE = TUNNEL_MTU

    /** Ein dekodiertes Datagramm. */
    data class Datagram(
        val sequence: Int,
        val timestampMicros: Long,
        /** Interleaved 8-Bit-IQ: [I0, Q0, I1, Q1, ...]. Länge immer gerade. */
        val iq: ByteArray,
    ) {
        val pairCount: Int get() = iq.size / 2
    }

    /** Bitrate des SDR-Datenstroms (I+Q). */
    fun bitsPerSecond(sampleRateMsps: Double, bitsPerSample: Int = 8): Double =
        sampleRateMsps * 1e6 * bitsPerSample * 2.0

    /**
     * Kodiert ein Datagramm inkl. 12-Byte-Header.
     * @param iq muss höchstens [MAX_PAYLOAD_SIZE] Byte lang und geradzahlig sein.
     */
    fun encode(sequence: Int, timestampMicros: Long, iq: ByteArray): ByteArray {
        require(iq.size <= MAX_PAYLOAD_SIZE) {
            "Payload zu groß: ${iq.size} > $MAX_PAYLOAD_SIZE Byte (MTU $TUNNEL_MTU)"
        }
        require(iq.size % 2 == 0) { "IQ-Payload muss geradzahlig sein (I+Q-Paare)" }

        val buffer = ByteBuffer.allocate(HEADER_SIZE + iq.size).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(sequence)
        buffer.putLong(timestampMicros)
        buffer.put(iq)
        return buffer.array()
    }

    /**
     * Dekodiert ein empfangenes UDP-Paket.
     * @return [Datagram] oder null bei unvollständigem Header.
     */
    fun decode(bytes: ByteArray, length: Int = bytes.size): Datagram? {
        if (length < HEADER_SIZE) return null
        val buffer = ByteBuffer.wrap(bytes, 0, length).order(ByteOrder.BIG_ENDIAN)
        val sequence = buffer.int
        val timestamp = buffer.long
        val iq = ByteArray(length - HEADER_SIZE)
        buffer.get(iq)
        return Datagram(sequence, timestamp, iq)
    }

    /**
     * Lückenstatistik für die Paketverlust-Erkennung auf der Empfängerseite.
     * Thread-sicher — Aufruf aus der Empfangs-Coroutine.
     */
    class GapTracker {

        private var initialized = false
        private var lastSequence = 0
        private var _lostPackets = 0L
        private var _reorderedPackets = 0L

        /** Empfangene Pakete gesamt. */
        var receivedPackets = 0L
            private set

        /** Als Lücke erkannte (verlorene) Pakete. */
        val lostPackets: Long get() = _lostPackets

        /** Pakete, die außerhalb der Reihenfolge ankamen (Jitter/Routing). */
        val reorderedPackets: Long get() = _reorderedPackets

        val lossRate: Float
            get() {
                val total = receivedPackets + _lostPackets
                return if (total == 0L) 0f else _lostPackets.toFloat() / total
            }

        @Synchronized
        fun track(sequence: Int): Int {
            receivedPackets++
            if (!initialized) {
                initialized = true
                lastSequence = sequence
                return 0
            }
            // UInt32-Differenz — wickelt Sequenznummer-Überläufe korrekt ab
            // (UInt-Subtraktion rechnet mod 2³², ohne Vorzeichenerweiterung).
            val diff = (sequence.toUInt() - lastSequence.toUInt()).toLong()
            if (diff == 0L) return 0 // Duplikat
            if (diff > 0x80000000L) {
                // Paket kam in falscher Reihenfolge an (negative Differenz)
                _reorderedPackets++
                return 0
            }
            val gap = (diff - 1).toInt()
            if (gap > 0) _lostPackets += gap
            lastSequence = sequence
            return gap
        }

        @Synchronized
        fun reset() {
            initialized = false
            lastSequence = 0
            _lostPackets = 0L
            _reorderedPackets = 0L
            receivedPackets = 0L
        }
    }
}
