package com.example.agent.ui.live

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Strict decoder for [uint32 point count][x,y,z float32 little-endian] frames. */
object PointCloudFrameDecoder {
    private const val HEADER_BYTES = 4
    private const val FLOATS_PER_POINT = 3
    private const val BYTES_PER_FLOAT = 4
    private const val MAX_POINTS = 1_000_000

    fun decode(data: ByteArray): FloatArray? {
        if (data.size < HEADER_BYTES) return null
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val pointCount = buffer.int
        if (pointCount !in 0..MAX_POINTS) return null

        val payloadBytes = pointCount.toLong() * FLOATS_PER_POINT * BYTES_PER_FLOAT
        val expectedBytes = HEADER_BYTES + payloadBytes
        if (expectedBytes != data.size.toLong()) return null

        val result = FloatArray(pointCount * FLOATS_PER_POINT)
        for (index in result.indices) {
            val value = buffer.float
            if (!value.isFinite()) return null
            result[index] = value
        }
        return result
    }
}
