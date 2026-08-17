package com.example.agent.ui.live

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PointCloudFrameDecoderTest {
    @Test
    fun decodesExactFiniteFrame() {
        val frame = ByteBuffer.allocate(4 + 6 * 4).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(2)
            .putFloat(1f).putFloat(2f).putFloat(3f)
            .putFloat(-1f).putFloat(-2f).putFloat(-3f)
            .array()

        assertArrayEquals(
            floatArrayOf(1f, 2f, 3f, -1f, -2f, -3f),
            PointCloudFrameDecoder.decode(frame),
            0f,
        )
    }

    @Test
    fun rejectsTruncationTrailingBytesNegativeCountAndNonFiniteValues() {
        assertNull(PointCloudFrameDecoder.decode(byteArrayOf(1, 2, 3)))
        assertNull(PointCloudFrameDecoder.decode(ByteBuffer.allocate(4).putInt(-1).array()))

        val truncated = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(1).putFloat(1f).array()
        assertNull(PointCloudFrameDecoder.decode(truncated))

        val trailing = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0).put(0).array()
        assertNull(PointCloudFrameDecoder.decode(trailing))

        val nan = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(1).putFloat(Float.NaN).putFloat(0f).putFloat(0f).array()
        assertNull(PointCloudFrameDecoder.decode(nan))
    }
}
