package com.example.agent.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleTokenProtocolTest {
    @Test
    fun decodesFirmwareV1Fixture() {
        // Firmware bytes after Android strips company ID 0x0059.
        val payload = byteArrayOf(
            0x01, 0x03,       // version, battery-valid + IMU-valid
            0x34, 0x12,       // sequence 0x1234
            0xD2.toByte(), 0x04, // +1.234 m/s²
            0x2E, 0xFB.toByte(), // -1.234 m/s²
            0x4F, 0x26,       // +9.807 m/s²
            87,               // battery percent
        )

        val frame = requireNotNull(BleTokenProtocol.decode(payload))

        assertEquals(1, frame.version)
        assertEquals(0x1234, frame.sequence)
        assertEquals(1.234f, frame.accelX, 0.0001f)
        assertEquals(-1.234f, frame.accelY, 0.0001f)
        assertEquals(9.807f, frame.accelZ, 0.0001f)
        assertTrue(frame.imuValid)
        assertEquals(87, frame.batteryPercent)
    }

    @Test
    fun preservesUnavailableMeasurementsAsInvalid() {
        val payload = byteArrayOf(
            0x01, 0x00,
            0x00, 0x00,
            0x00, 0x00,
            0x00, 0x00,
            0x00, 0x00,
            0xFF.toByte(),
        )

        val frame = requireNotNull(BleTokenProtocol.decode(payload))

        assertFalse(frame.imuValid)
        assertNull(frame.batteryPercent)
    }

    @Test
    fun rejectsBadLengthAndUnknownVersion() {
        assertNull(BleTokenProtocol.decode(ByteArray(BleTokenProtocol.PAYLOAD_SIZE - 1)))
        assertNull(BleTokenProtocol.decode(ByteArray(BleTokenProtocol.PAYLOAD_SIZE + 1)))

        val futureVersion = ByteArray(BleTokenProtocol.PAYLOAD_SIZE)
        futureVersion[0] = 2
        assertNull(BleTokenProtocol.decode(futureVersion))
    }

    @Test
    fun invalidBatteryValueIsNotExposed() {
        val payload = ByteArray(BleTokenProtocol.PAYLOAD_SIZE)
        payload[0] = 1
        payload[1] = 1 // battery-valid flag
        payload[10] = 101

        val frame = requireNotNull(BleTokenProtocol.decode(payload))
        assertNull(frame.batteryPercent)
    }
}
