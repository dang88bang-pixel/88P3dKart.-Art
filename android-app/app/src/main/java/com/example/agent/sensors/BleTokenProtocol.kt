package com.example.agent.sensors

/**
 * Versioned binary contract for the nRF52840 token manufacturer-data payload.
 *
 * Android's ScanRecord indexes manufacturer data by the two-byte Bluetooth
 * company identifier. The bytes decoded here therefore start *after* that ID.
 * All multi-byte values are little-endian.
 *
 * v1 payload (11 bytes):
 *   0      protocol version (1)
 *   1      flags: bit 0 battery valid, bit 1 IMU valid
 *   2..3   sequence (uint16)
 *   4..5   acceleration X (int16, milli-m/s²)
 *   6..7   acceleration Y (int16, milli-m/s²)
 *   8..9   acceleration Z (int16, milli-m/s²)
 *   10     battery percent (0..100), or 0xFF when unavailable
 */
object BleTokenProtocol {
    const val VERSION = 1
    const val PAYLOAD_SIZE = 11
    const val BATTERY_UNKNOWN = 0xFF

    private const val FLAG_BATTERY_VALID = 1 shl 0
    private const val FLAG_IMU_VALID = 1 shl 1
    private const val ACCEL_SCALE = 1000f

    data class Frame(
        val version: Int,
        val sequence: Int,
        val accelX: Float,
        val accelY: Float,
        val accelZ: Float,
        val imuValid: Boolean,
        val batteryPercent: Int?,
    )

    fun decode(payload: ByteArray): Frame? {
        if (payload.size != PAYLOAD_SIZE) return null

        val version = payload[0].toInt() and 0xFF
        if (version != VERSION) return null

        val flags = payload[1].toInt() and 0xFF
        val sequence = readUInt16Le(payload, 2)
        val batteryRaw = payload[10].toInt() and 0xFF
        val batteryValid = flags and FLAG_BATTERY_VALID != 0
        val battery = if (batteryValid && batteryRaw in 0..100) batteryRaw else null

        return Frame(
            version = version,
            sequence = sequence,
            accelX = readInt16Le(payload, 4) / ACCEL_SCALE,
            accelY = readInt16Le(payload, 6) / ACCEL_SCALE,
            accelZ = readInt16Le(payload, 8) / ACCEL_SCALE,
            imuValid = flags and FLAG_IMU_VALID != 0,
            batteryPercent = battery,
        )
    }

    private fun readUInt16Le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun readInt16Le(bytes: ByteArray, offset: Int): Short =
        readUInt16Le(bytes, offset).toShort()
}
