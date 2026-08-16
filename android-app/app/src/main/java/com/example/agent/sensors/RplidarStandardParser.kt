package com.example.agent.sensors

/** Stateful parser for RPLIDAR legacy/standard scan response nodes. */
class RplidarStandardParser(
    private val maxDistanceMeters: Float = 40f,
) {
    data class Sample(
        val angleDegrees: Float,
        val distanceMeters: Float,
        val quality: Int,
        val startsNewScan: Boolean,
    )

    private enum class State { WAITING_FOR_DESCRIPTOR, READING_NODES }

    private var state = State.WAITING_FOR_DESCRIPTOR
    private val buffer = ArrayList<Byte>()

    fun reset() {
        state = State.WAITING_FOR_DESCRIPTOR
        buffer.clear()
    }

    fun consume(bytes: ByteArray): List<Sample> {
        buffer.addAll(bytes.toList())
        val output = mutableListOf<Sample>()

        if (state == State.WAITING_FOR_DESCRIPTOR && !consumeDescriptor()) {
            trimDescriptorSearchBuffer()
            return output
        }

        while (buffer.size >= NODE_SIZE) {
            val sample = decodeNode(buffer, 0)
            if (sample == null) {
                // A damaged byte must not permanently shift all following nodes.
                buffer.removeAt(0)
            } else {
                repeat(NODE_SIZE) { buffer.removeAt(0) }
                if (sample.distanceMeters in MIN_DISTANCE_METERS..maxDistanceMeters) {
                    output += sample
                }
            }
        }
        return output
    }

    private fun consumeDescriptor(): Boolean {
        var index = 0
        while (index + 1 < buffer.size) {
            if (u8(buffer[index]) == DESCRIPTOR_SYNC_1 &&
                u8(buffer[index + 1]) == DESCRIPTOR_SYNC_2
            ) {
                if (buffer.size - index < DESCRIPTOR_SIZE) {
                    if (index > 0) repeat(index) { buffer.removeAt(0) }
                    return false
                }
                if (isMeasurementDescriptor(index)) {
                    repeat(index + DESCRIPTOR_SIZE) { buffer.removeAt(0) }
                    state = State.READING_NODES
                    return true
                }
            }
            index++
        }
        return false
    }

    private fun isMeasurementDescriptor(offset: Int): Boolean {
        val responseSizeAndMode = readUInt32Le(buffer, offset + 2)
        val responseSize = responseSizeAndMode and 0x3fff_ffff
        val sendMode = responseSizeAndMode ushr 30
        val responseType = u8(buffer[offset + 6])
        return responseSize == NODE_SIZE.toLong() && sendMode == MULTIPLE_RESPONSE_MODE &&
            responseType == MEASUREMENT_RESPONSE_TYPE
    }

    private fun trimDescriptorSearchBuffer() {
        // Keep enough trailing bytes for a descriptor split across callbacks.
        val excess = buffer.size - (DESCRIPTOR_SIZE - 1)
        if (excess > 0) repeat(excess) { buffer.removeAt(0) }
    }

    companion object {
        private const val NODE_SIZE = 5
        private const val DESCRIPTOR_SIZE = 7
        private const val DESCRIPTOR_SYNC_1 = 0xA5
        private const val DESCRIPTOR_SYNC_2 = 0x5A
        private const val MEASUREMENT_RESPONSE_TYPE = 0x81
        private const val MULTIPLE_RESPONSE_MODE = 1L
        private const val MIN_DISTANCE_METERS = 0.05f

        internal fun decodeNode(bytes: List<Byte>, offset: Int): Sample? {
            if (offset < 0 || bytes.size - offset < NODE_SIZE) return null

            val status = u8(bytes[offset])
            val startBit = status and 0x01
            val invertedStartBit = (status ushr 1) and 0x01
            if (startBit == invertedStartBit) return null

            val angleLowAndCheck = u8(bytes[offset + 1])
            if (angleLowAndCheck and 0x01 != 1) return null

            val quality = status ushr 2
            val angleQ6 = (angleLowAndCheck ushr 1) or (u8(bytes[offset + 2]) shl 7)
            val distanceQ2 = u8(bytes[offset + 3]) or (u8(bytes[offset + 4]) shl 8)
            val angle = angleQ6 / 64f
            if (angle !in 0f..<360f) return null

            return Sample(
                angleDegrees = angle,
                distanceMeters = distanceQ2 / 4000f,
                quality = quality,
                startsNewScan = startBit == 1,
            )
        }

        private fun readUInt32Le(bytes: List<Byte>, offset: Int): Long =
            u8(bytes[offset]).toLong() or
                (u8(bytes[offset + 1]).toLong() shl 8) or
                (u8(bytes[offset + 2]).toLong() shl 16) or
                (u8(bytes[offset + 3]).toLong() shl 24)

        private fun u8(value: Byte): Int = value.toInt() and 0xff
    }
}
