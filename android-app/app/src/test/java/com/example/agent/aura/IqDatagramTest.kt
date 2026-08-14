package com.example.agent.aura

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class IqDatagramTest {

    @Test
    fun `konstanten entsprechen Spezifikation`() {
        assertEquals(12, IqDatagram.HEADER_SIZE)
        assertEquals(1420, IqDatagram.TUNNEL_MTU)
        assertEquals(1408, IqDatagram.MAX_PAYLOAD_SIZE)
        assertEquals(704, IqDatagram.IQ_PAIRS_PER_DATAGRAM)
    }

    @Test
    fun `bitrate fuer RTL-SDR v5 bei 2_4 MSps ist 38_4 MBit_s`() {
        assertEquals(38_400_000.0, IqDatagram.bitsPerSecond(2.4), 1.0)
    }

    @Test
    fun `encode und decode sind invers`() {
        val iq = ByteArray(1408) { (it * 7 % 256 - 128).toByte() }
        val seq = 123456
        val ts = 1_700_000_000_123_456L

        val encoded = IqDatagram.encode(seq, ts, iq)
        assertEquals(1420, encoded.size)

        val decoded = IqDatagram.decode(encoded)
        assertNotNull(decoded)
        assertEquals(seq, decoded!!.sequence)
        assertEquals(ts, decoded.timestampMicros)
        assertArrayEquals(iq, decoded.iq)
        assertEquals(704, decoded.pairCount)
    }

    @Test
    fun `decode toleriert gekuerzte Payload`() {
        val datagram = IqDatagram.encode(7, 42L, ByteArray(200))
        val truncated = datagram.copyOf(12 + 120)
        val decoded = IqDatagram.decode(truncated)
        assertNotNull(decoded)
        assertEquals(120, decoded!!.iq.size)
    }

    @Test
    fun `decode liefert null bei unvollstaendigem Header`() {
        assertNull(IqDatagram.decode(ByteArray(11)))
        assertNull(IqDatagram.decode(ByteArray(0)))
    }

    @Test
    fun `encode verwirft zu grosse oder ungerade Payloads`() {
        assertThrows(IllegalArgumentException::class.java) {
            IqDatagram.encode(1, 1L, ByteArray(1409))
        }
        assertThrows(IllegalArgumentException::class.java) {
            IqDatagram.encode(1, 1L, ByteArray(1399)) // ungerade
        }
    }

    @Test
    fun `gapTracker erkennt Luecken und Reordering`() {
        val tracker = IqDatagram.GapTracker()
        // lückenlos 1..5
        for (seq in 1..5) assertEquals(0, tracker.track(seq))
        // Lücke: 6..9 fehlen
        assertEquals(4, tracker.track(10))
        assertEquals(4L, tracker.lostPackets)
        // Reordering: 8 kommt nach 10 an
        assertEquals(0, tracker.track(8))
        assertEquals(1L, tracker.reorderedPackets)
        assertEquals(4L, tracker.lostPackets)
        assertEquals(0.0f, tracker.lossRate, 0.15f)
    }

    @Test
    fun `gapTracker behandelt Sequenznummer-Ueberlauf`() {
        val tracker = IqDatagram.GapTracker()
        tracker.track(Int.MAX_VALUE - 1)
        // UInt32-Wraparound: nächste Sequenz 0 (Differenz 2)
        assertEquals(1, tracker.track(0))
    }
}
