package com.example.agent.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RplidarStandardParserTest {
    private val descriptor = byteArrayOf(
        0xA5.toByte(), 0x5A, 0x05, 0x00, 0x00, 0x40, 0x81.toByte(),
    )

    @Test
    fun parsesDescriptorAndNodesAcrossArbitraryChunks() {
        val parser = RplidarStandardParser()
        val firstNode = node(angleDegrees = 90f, distanceMeters = 1.25f, quality = 20, start = true)
        val secondNode = node(angleDegrees = 180f, distanceMeters = 2f, quality = 10, start = false)
        val stream = descriptor + firstNode + secondNode

        assertTrue(parser.consume(stream.copyOfRange(0, 4)).isEmpty())
        assertTrue(parser.consume(stream.copyOfRange(4, 9)).isEmpty())
        val samples = parser.consume(stream.copyOfRange(9, stream.size))

        assertEquals(2, samples.size)
        assertEquals(90f, samples[0].angleDegrees, 0.02f)
        assertEquals(1.25f, samples[0].distanceMeters, 0.0003f)
        assertEquals(20, samples[0].quality)
        assertTrue(samples[0].startsNewScan)
        assertEquals(180f, samples[1].angleDegrees, 0.02f)
        assertEquals(2f, samples[1].distanceMeters, 0.0003f)
    }

    @Test
    fun rejectsDamagedByteAndResynchronizes() {
        val parser = RplidarStandardParser()
        parser.consume(descriptor)
        val damaged = byteArrayOf(0x00) +
            node(45f, 1f, 12, false) + node(46f, 1.5f, 12, false)

        val samples = parser.consume(damaged)

        assertEquals(2, samples.size)
        assertEquals(45f, samples[0].angleDegrees, 0.02f)
        assertEquals(46f, samples[1].angleDegrees, 0.02f)
    }

    @Test
    fun filtersZeroAndOutOfRangeDistances() {
        val parser = RplidarStandardParser(maxDistanceMeters = 10f)
        val samples = parser.consume(
            descriptor + node(0f, 0f, 1, true) + node(1f, 12f, 1, false),
        )
        assertTrue(samples.isEmpty())
    }

    private fun node(
        angleDegrees: Float,
        distanceMeters: Float,
        quality: Int,
        start: Boolean,
    ): ByteArray {
        val angleQ6 = (angleDegrees * 64).toInt()
        val distanceQ2Millimeters = (distanceMeters * 4000).toInt()
        val status = (quality shl 2) or if (start) 0x01 else 0x02
        return byteArrayOf(
            status.toByte(),
            (((angleQ6 and 0x7f) shl 1) or 0x01).toByte(),
            (angleQ6 ushr 7).toByte(),
            distanceQ2Millimeters.toByte(),
            (distanceQ2Millimeters ushr 8).toByte(),
        )
    }
}
