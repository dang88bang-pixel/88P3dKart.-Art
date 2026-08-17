package com.example.agent.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TiMmwaveParserTest {
    @Test
    fun parsesSdk3DetectedPointsAcrossChunks() {
        val parser = TiMmwaveParser()
        val packet = packet(
            frameNumber = 42,
            targets = listOf(
                floatArrayOf(1.5f, -2f, 0.25f, 0.75f),
                floatArrayOf(3f, 4f, 5f, -1.25f),
            ),
        )

        assertTrue(parser.consume(packet.copyOfRange(0, 13)).isEmpty())
        assertTrue(parser.consume(packet.copyOfRange(13, 57)).isEmpty())
        val frames = parser.consume(packet.copyOfRange(57, packet.size))

        assertEquals(1, frames.size)
        assertEquals(42L, frames.single().frameNumber)
        assertEquals(2, frames.single().targets.size)
        assertEquals(1.5f, frames.single().targets[0].x, 0f)
        assertEquals(-2f, frames.single().targets[0].y, 0f)
        assertEquals(0.75f, frames.single().targets[0].velocity, 0f)
        assertEquals(-1.25f, frames.single().targets[1].velocity, 0f)
    }

    @Test
    fun discardsGarbageAndMalformedHeaderThenResynchronizes() {
        val parser = TiMmwaveParser()
        val malformed = packet(1, emptyList()).also {
            putUInt32Le(it, 12, 12) // impossible total length
        }
        val valid = packet(2, listOf(floatArrayOf(1f, 2f, 3f, 4f)))

        val frames = parser.consume(byteArrayOf(9, 8, 7) + malformed + valid)

        assertEquals(1, frames.size)
        assertEquals(2L, frames.single().frameNumber)
    }

    @Test
    fun rejectsNonFinitePointData() {
        val parser = TiMmwaveParser()
        val packet = packet(9, listOf(floatArrayOf(Float.NaN, 0f, 0f, 0f)))
        assertTrue(parser.consume(packet).isEmpty())
    }

    private fun packet(frameNumber: Int, targets: List<FloatArray>): ByteArray {
        val payloadLength = targets.size * 16
        val unpaddedLength = 40 + if (targets.isEmpty()) 0 else 8 + payloadLength
        val totalLength = ((unpaddedLength + 31) / 32) * 32
        val result = ByteArray(totalLength)
        byteArrayOf(0x02, 0x01, 0x04, 0x03, 0x06, 0x05, 0x08, 0x07)
            .copyInto(result)
        putUInt32Le(result, 8, 0x03050004)
        putUInt32Le(result, 12, totalLength)
        putUInt32Le(result, 16, 0xA6843)
        putUInt32Le(result, 20, frameNumber)
        putUInt32Le(result, 28, targets.size)
        putUInt32Le(result, 32, if (targets.isEmpty()) 0 else 1)

        if (targets.isNotEmpty()) {
            putUInt32Le(result, 40, 1)
            putUInt32Le(result, 44, payloadLength)
            var offset = 48
            for (target in targets) {
                require(target.size == 4)
                for (value in target) {
                    putUInt32Le(result, offset, value.toRawBits())
                    offset += 4
                }
            }
        }
        return result
    }

    private fun putUInt32Le(destination: ByteArray, offset: Int, value: Int) {
        destination[offset] = value.toByte()
        destination[offset + 1] = (value ushr 8).toByte()
        destination[offset + 2] = (value ushr 16).toByte()
        destination[offset + 3] = (value ushr 24).toByte()
    }
}
