package com.example.agent.sensors

/**
 * Streaming parser for the TI mmWave SDK 3.x out-of-box UART packet format.
 *
 * This parser intentionally supports one explicit profile: the 40-byte frame
 * header and floating-point detected-points TLV (type 1). A different TI lab
 * or SDK output format must use a separate parser rather than being guessed.
 */
class TiMmwaveParser {
    data class Target(val x: Float, val y: Float, val z: Float, val velocity: Float)
    data class Frame(val frameNumber: Long, val targets: List<Target>)

    private val buffer = ArrayList<Byte>()

    fun reset() = buffer.clear()

    fun consume(bytes: ByteArray): List<Frame> {
        if (bytes.size > MAX_BUFFERED_BYTES) {
            buffer.clear()
            return emptyList()
        }
        buffer.addAll(bytes.toList())
        if (buffer.size > MAX_BUFFERED_BYTES) {
            val excess = buffer.size - MAX_BUFFERED_BYTES
            repeat(excess) { buffer.removeAt(0) }
        }

        val frames = mutableListOf<Frame>()
        while (true) {
            val magicOffset = findMagic()
            if (magicOffset < 0) {
                retainMagicPrefix()
                break
            }
            if (magicOffset > 0) repeat(magicOffset) { buffer.removeAt(0) }
            if (buffer.size < HEADER_SIZE) break

            val totalLength = readUInt32Le(buffer, TOTAL_LENGTH_OFFSET)
            val objectCount = readUInt32Le(buffer, OBJECT_COUNT_OFFSET)
            val tlvCount = readUInt32Le(buffer, TLV_COUNT_OFFSET)
            if (totalLength !in HEADER_SIZE.toLong()..MAX_PACKET_BYTES.toLong() ||
                objectCount > MAX_OBJECTS || tlvCount > MAX_TLVS
            ) {
                buffer.removeAt(0)
                continue
            }
            if (buffer.size < totalLength.toInt()) break

            decodeFrame(totalLength.toInt(), objectCount.toInt(), tlvCount.toInt())
                ?.let(frames::add)
            repeat(totalLength.toInt()) { buffer.removeAt(0) }
        }
        return frames
    }

    private fun decodeFrame(totalLength: Int, objectCount: Int, tlvCount: Int): Frame? {
        var cursor = HEADER_SIZE
        var detectedPoints: List<Target>? = null

        repeat(tlvCount) {
            if (cursor + TLV_HEADER_SIZE > totalLength) return null
            val type = readUInt32Le(buffer, cursor)
            val payloadLengthLong = readUInt32Le(buffer, cursor + 4)
            if (payloadLengthLong > MAX_PACKET_BYTES) return null
            val payloadLength = payloadLengthLong.toInt()
            val payloadStart = cursor + TLV_HEADER_SIZE
            val payloadEnd = payloadStart.toLong() + payloadLength
            if (payloadEnd > totalLength || payloadEnd > Int.MAX_VALUE) return null

            if (type == DETECTED_POINTS_TLV) {
                if (detectedPoints != null || payloadLength != objectCount * POINT_SIZE) return null
                val points = ArrayList<Target>(objectCount)
                var pointOffset = payloadStart
                repeat(objectCount) {
                    val target = Target(
                        x = readFloatLe(buffer, pointOffset),
                        y = readFloatLe(buffer, pointOffset + 4),
                        z = readFloatLe(buffer, pointOffset + 8),
                        velocity = readFloatLe(buffer, pointOffset + 12),
                    )
                    if (!target.x.isFinite() || !target.y.isFinite() ||
                        !target.z.isFinite() || !target.velocity.isFinite()
                    ) return null
                    points += target
                    pointOffset += POINT_SIZE
                }
                detectedPoints = points
            }
            cursor = payloadEnd.toInt()
        }

        // Remaining bytes are the SDK's 32-byte packet-alignment padding.
        if (cursor > totalLength) return null
        if (objectCount > 0 && detectedPoints == null) return null

        return Frame(
            frameNumber = readUInt32Le(buffer, FRAME_NUMBER_OFFSET),
            targets = detectedPoints.orEmpty(),
        )
    }

    private fun findMagic(): Int {
        val lastStart = buffer.size - MAGIC.size
        for (offset in 0..lastStart) {
            var matches = true
            for (index in MAGIC.indices) {
                if (buffer[offset + index] != MAGIC[index]) {
                    matches = false
                    break
                }
            }
            if (matches) return offset
        }
        return -1
    }

    private fun retainMagicPrefix() {
        val keep = minOf(buffer.size, MAGIC.size - 1)
        if (buffer.size > keep) {
            repeat(buffer.size - keep) { buffer.removeAt(0) }
        }
    }

    companion object {
        private val MAGIC = byteArrayOf(0x02, 0x01, 0x04, 0x03, 0x06, 0x05, 0x08, 0x07)
        private const val HEADER_SIZE = 40
        private const val TLV_HEADER_SIZE = 8
        private const val POINT_SIZE = 16
        private const val TOTAL_LENGTH_OFFSET = 12
        private const val FRAME_NUMBER_OFFSET = 20
        private const val OBJECT_COUNT_OFFSET = 28
        private const val TLV_COUNT_OFFSET = 32
        private const val DETECTED_POINTS_TLV = 1L
        private const val MAX_OBJECTS = 1024L
        private const val MAX_TLVS = 64L
        private const val MAX_PACKET_BYTES = 1024 * 1024
        private const val MAX_BUFFERED_BYTES = MAX_PACKET_BYTES * 2

        private fun readUInt32Le(bytes: List<Byte>, offset: Int): Long =
            (bytes[offset].toInt() and 0xff).toLong() or
                ((bytes[offset + 1].toInt() and 0xff).toLong() shl 8) or
                ((bytes[offset + 2].toInt() and 0xff).toLong() shl 16) or
                ((bytes[offset + 3].toInt() and 0xff).toLong() shl 24)

        private fun readFloatLe(bytes: List<Byte>, offset: Int): Float =
            Float.fromBits(readUInt32Le(bytes, offset).toInt())
    }
}
