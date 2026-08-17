package com.example.agent.bluetooth

import android.bluetooth.le.ScanResult
import android.os.ParcelUuid
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Parser für BLE-Advertisements aller unterstützten Zubehörtypen.
 *
 * Unterstützt:
 * - 3dxAgent Legacy (9 Byte, Company 0x0059, accel + battery)
 * - 3dxAgent Pro V2 (16+ Byte, accel + temp + flags + extras)
 * - iBeacon (Apple Company 0x004C, 0x02 0x15 ...)
 * - Eddystone UID / URL / TLM (Service UUID 0xFEAA)
 * - Standard Battery Service, Env Sensing
 * - TX Power, Device Name
 */
object BleAdvertisementParser {

    data class ParsedAdvertisement(
        val mac: String,
        val name: String?,
        val rssi: Int,
        val txPower: Int?,
        val accessoryType: BluetoothAccessoryType,
        val isConnectable: Boolean,
        val protocolVersion: Int,
        val rawManufacturerData: ByteArray?,
        val accessory: BluetoothAccessory,
    )

    fun parse(result: ScanResult): ParsedAdvertisement? {
        val record = result.scanRecord ?: return null
        val mac = result.device.address ?: return null
        val rssi = result.rssi
        val name = result.device.name ?: record.deviceName
        val isConnectable = result.isConnectable
        val txPower = record.txPowerLevel.takeIf { it != Int.MIN_VALUE }

        // Manufacturer Data priorisieren
        val mfg59 = record.getManufacturerSpecificData(ManufacturerIds.NORDIC_3DX)
        val mfgApple = record.getManufacturerSpecificData(ManufacturerIds.APPLE_IBEACON)

        // Service Data für Eddystone
        val eddystoneData: ByteArray? = record.serviceData[ParcelUuid.fromString("0000feaa-0000-1000-8000-00805f9b34fb")]
            ?: record.serviceData[ParcelUuid(UUID.fromString("0000FEAA-0000-1000-8000-00805f9b34fb"))]

        var type: BluetoothAccessoryType = BluetoothAccessoryType.fromAdvertisementName(name)
        var protocolVersion = 1
        var accessory = BluetoothAccessory(
            macAddress = mac,
            type = type,
            name = name ?: mac,
            rssi = rssi,
            txPower = txPower,
            isConnectable = isConnectable,
        )

        if (mfg59 != null && mfg59.isNotEmpty()) {
            val parsed = parse3dxAgentData(mfg59, accessory)
            accessory = parsed.first
            type = parsed.second
            protocolVersion = accessory.protocolVersion
        } else if (mfgApple != null && mfgApple.size >= 23 && mfgApple[0] == 0x02.toByte() && mfgApple[1] == 0x15.toByte()) {
            accessory = parseIBeaconData(mfgApple, accessory)
            type = BluetoothAccessoryType.ASSET_TAG
            protocolVersion = 1
        }

        if (eddystoneData != null && eddystoneData.isNotEmpty()) {
            accessory = parseEddystoneData(eddystoneData, accessory)
            if (type == BluetoothAccessoryType.GENERIC_BLE) {
                type = BluetoothAccessoryType.ASSET_TAG
            }
        }

        // Service UUIDs analysieren für genauere Typisierung
        val serviceUuids = record.serviceUuids?.map { it.uuid } ?: emptyList()
        for (uuid in serviceUuids) {
            when (uuid.toString().lowercase()) {
                GattUuids.BATTERY_SERVICE -> { /* generisch batteriepowered */ }
                GattUuids.ENV_SENSING -> if (type == BluetoothAccessoryType.GENERIC_BLE) type = BluetoothAccessoryType.SENSOR_TAG
                GattUuids.HEART_RATE_SERVICE -> if (type == BluetoothAccessoryType.GENERIC_BLE) type = BluetoothAccessoryType.WEARABLE
                GattUuids.HID_SERVICE -> type = BluetoothAccessoryType.HID
                GattUuids.CUSTOM_3DX_SERVICE -> if (type == BluetoothAccessoryType.GENERIC_BLE) type = BluetoothAccessoryType.TOKEN_PRO
            }
        }

        // Wenn gar nichts erkannt, aber Manufacturer 0x0059 ohne Daten → Token Classic
        if (mfg59 == null && name?.startsWith("3dx", true) == true) {
            type = BluetoothAccessoryType.TOKEN_PRO
        }

        accessory = accessory.copy(type = type, protocolVersion = protocolVersion)
        accessory.updateDistanceEstimate()
        accessory.manufacturerData = mfg59 ?: mfgApple

        return ParsedAdvertisement(
            mac = mac,
            name = name,
            rssi = rssi,
            txPower = txPower,
            accessoryType = type,
            isConnectable = isConnectable,
            protocolVersion = protocolVersion,
            rawManufacturerData = mfg59 ?: mfgApple,
            accessory = accessory,
        )
    }

    /** 3dxAgent Protokolle: V1 legacy 9 Byte, V2 extended 16+ Byte */
    private fun parse3dxAgentData(data: ByteArray, base: BluetoothAccessory): Pair<BluetoothAccessory, BluetoothAccessoryType> {
        var acc = base
        var type = base.type

        if (data.size < 9 && data.size != 16 && data.size < 17) {
            // Versuche trotzdem legacy falls klein
            if (data.size >= 9) {
                // legacy – aber bei uns kommt data bereits ohne Company-ID-Präfix (ScanRecord entfernt sie?)
                // Android liefert nur Value, nicht ID. In unserem Fall: 9 Byte = [accX LSB, MSB, ... battery]
                // Doch neue Implementierung liefert kompletten Value.
                // Unterscheiden: V1 legacy hat 9 Byte: accX(2)+accY(2)+accZ(2)+battery(1)+2 unbekannt? Siehe alte Doku: 9 Bytes. Wir dekodieren adaptiv.
            }
        }

        try {
            if (data.size >= 16) {
                // Versuche V2 zu erkennen: byte 0 = protocol version 1/2, byte1 = accessory type
                // Legacy V1 kann kein zuverlässiges version byte haben, aber wir haben Größencheck.
                val possibleVersion = data[0].toInt() and 0xFF
                val possibleTypeCode = data[1].toInt() and 0xFF

                if (possibleVersion in 1..3) {
                    // V2 Erweiterung
                    acc = acc.copy(protocolVersion = possibleVersion)
                    type = BluetoothAccessoryType.fromCode(possibleTypeCode)

                    // Layout V2: [0]=ver, [1]=type, [2..3]=ax, [4..5]=ay, [6..7]=az, [8]=bat, [9..10]=temp int16/100, [11]=flags, [12..]
                    if (data.size >= 9) {
                        acc = acc.copy(
                            accelX = shortAt(data, 2) / 1000f,
                            accelY = shortAt(data, 4) / 1000f,
                            accelZ = shortAt(data, 6) / 1000f,
                            batteryLevel = (data[8].toInt() and 0xFF).coerceIn(0, 100),
                        )
                    }
                    if (data.size >= 12) {
                        acc = acc.copy(
                            temperatureC = shortAt(data, 9) / 100f,
                            flags = data[11].toInt() and 0xFF,
                        )
                    }
                    if (data.size >= 14) {
                        val extra = data[12].toInt() and 0xFF
                        when (type) {
                            BluetoothAccessoryType.WEARABLE -> acc = acc.copy(heartRateBpm = extra)
                            BluetoothAccessoryType.SENSOR_TAG -> acc = acc.copy(humidityPct = extra.toFloat())
                            BluetoothAccessoryType.REMOTE_CONTROLLER -> acc = acc.copy(buttonState = extra)
                            else -> {}
                        }
                    }
                    if (data.size >= 16) {
                        // Optional zweite Extra Messung
                        val extra2 = shortAt(data, 13) // kann humidity*100 oder steps/2 etc.
                        when (type) {
                            BluetoothAccessoryType.SENSOR_TAG -> acc = acc.copy(pressureHpa = extra2 / 10f)
                            BluetoothAccessoryType.WEARABLE -> acc = acc.copy(steps = (extra2.toInt() and 0xFFFF))
                            else -> {}
                        }
                    }
                } else {
                    // Falls version byte unplausibel → doch Legacy 9-Byte Payload
                    acc = parseLegacy9Byte(data, acc)
                    type = BluetoothAccessoryType.TOKEN_CLASSIC
                    acc = acc.copy(protocolVersion = 1)
                }
            } else if (data.size >= 7) {
                acc = parseLegacy9Byte(data, acc)
                type = BluetoothAccessoryType.TOKEN_CLASSIC
                acc = acc.copy(protocolVersion = 1)
            }
        } catch (e: Exception) {
            // Fallback legacy
            acc = tryParseLenient(data, acc)
        }

        return Pair(acc, type)
    }

    private fun parseLegacy9Byte(data: ByteArray, base: BluetoothAccessory): BluetoothAccessory {
        // Erwartet 7 oder 9 Bytes: [ax L H, ay L H, az L H, battery]
        // In Firmware main.c: mfg_data[0..1]=Company, aber ScanRecord entfernt ID.
        // Bei uns ist data bereits Value. Altes Layout im BleTokenManager: data[2]=ax LSB? Der alte Parser nahm data[2].toShort() etc.
        // Daher: falls 9 Byte: offset 0..1 sind ax, 2..3 ay, 4..5 az, 6 battery? Oder bei älterer Firmware mit Company-Präfix in ScanRecord?
        // Wir unterstützen beide Offsets try.
        return try {
            if (data.size >= 7) {
                // Prüfe ob erste 2 Bytes Company ID 0x0059 enthalten (dann offset 2)
                val startsWithCompany = data.size >= 9 && (data[0].toInt() and 0xFF) == 0x59 && (data[1].toInt() and 0xFF) == 0x00
                val off = if (startsWithCompany) 2 else 0
                val ax = shortAt(data, off)
                val ay = shortAt(data, off + 2)
                val az = shortAt(data, off + 4)
                val batt = if (data.size > off + 6) (data[off + 6].toInt() and 0xFF) else 100
                base.copy(
                    accelX = ax / 1000f,
                    accelY = ay / 1000f,
                    accelZ = az / 1000f,
                    batteryLevel = batt.coerceIn(0, 100),
                )
            } else base
        } catch (_: Exception) { base }
    }

    private fun tryParseLenient(data: ByteArray, base: BluetoothAccessory): BluetoothAccessory {
        return try {
            if (data.size >= 3) {
                base.copy(accelX = shortAt(data, 0) / 1000f)
            } else base
        } catch (_: Exception) { base }
    }

    private fun parseIBeaconData(data: ByteArray, base: BluetoothAccessory): BluetoothAccessory {
        // iBeacon: [0]=0x02 [1]=0x15 [2..17]=UUID [18..19]=major [20..21]=minor [22]=txPower
        if (data.size < 23) return base
        val buf = ByteBuffer.wrap(data)
        buf.order(ByteOrder.BIG_ENDIAN)
        buf.position(2)
        val uuidBytes = ByteArray(16)
        buf.get(uuidBytes)
        val uuidStr = formatUuid(uuidBytes)
        val major = (buf.short.toInt() and 0xFFFF)
        val minor = (buf.short.toInt() and 0xFFFF)
        val tx = buf.get().toInt()

        return base.copy(
            iBeaconUuid = uuidStr,
            iBeaconMajor = major,
            iBeaconMinor = minor,
            txPower = tx,
            batteryLevel = base.batteryLevel, // iBeacon hat keine Batterie
        )
    }

    private fun parseEddystoneData(data: ByteArray, base: BluetoothAccessory): BluetoothAccessory {
        if (data.isEmpty()) return base
        val frameType = data[0].toInt() and 0xFF
        return when (frameType) {
            0x00 -> { // UID
                if (data.size < 18) return base
                val tx = data[1].toInt()
                val namespace = data.sliceArray(2..11).joinToString("") { "%02x".format(it) }
                val instance = data.sliceArray(12..17).joinToString("") { "%02x".format(it) }
                base.copy(
                    txPower = tx,
                    eddystoneNamespace = namespace,
                    eddystoneInstance = instance,
                )
            }
            0x10 -> { // URL
                if (data.size < 4) return base
                val tx = data[1].toInt()
                val urlScheme = when (data[2].toInt() and 0xFF) {
                    0x00 -> "http://www."
                    0x01 -> "https://www."
                    0x02 -> "http://"
                    0x03 -> "https://"
                    else -> ""
                }
                val urlEncoded = data.sliceArray(3 until data.size).toString(Charsets.UTF_8)
                base.copy(txPower = tx, eddystoneUrl = urlScheme + urlEncoded)
            }
            0x20 -> { // TLM
                if (data.size < 14) return base
                val tempRaw = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
                val temp = if (tempRaw == 0x8000) null else tempRaw / 256f
                // Battery voltage [2..3], temp [4..5]...
                val batteryByte = ((data[2].toInt() and 0xFF) * 256 + (data[3].toInt() and 0xFF)) / 1000f
                // approximates battery %
                val battPct = ((batteryByte - 2.0f) / (3.0f - 2.0f) * 100f).toInt().coerceIn(0, 100)
                base.copy(
                    temperatureC = temp ?: base.temperatureC,
                    batteryLevel = battPct,
                )
            }
            else -> base
        }
    }

    private fun shortAt(data: ByteArray, off: Int): Short {
        if (off + 1 >= data.size) return 0
        return ((data[off].toInt() and 0xFF) or ((data[off + 1].toInt() and 0xFF) shl 8)).toShort()
    }

    private fun formatUuid(bytes: ByteArray): String {
        // 16 bytes → 8-4-4-4-12 hex
        val hex = bytes.joinToString("") { "%02x".format(it) }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20, 32)}"
    }
}
