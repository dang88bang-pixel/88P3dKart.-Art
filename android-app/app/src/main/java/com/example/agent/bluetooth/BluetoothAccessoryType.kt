package com.example.agent.bluetooth

/**
 * 3dxAgent Bluetooth-Zubehör Typen
 *
 * Erweitertes Typ-System für alle unterstützten BLE- und Classic-Geräte.
 * Jeder Typ definiert Standard-Advertising-IDs, GATT Services und Datenrate.
 */
enum class BluetoothAccessoryType(
    val code: Int,
    val displayName: String,
    val defaultFrequencyHz: Float,
    val isBatteryPowered: Boolean,
    val supportsGatt: Boolean,
    val description: String,
) {
    /** nRF52840 Token (legacy, Company ID 0x0059, 9 Byte) */
    TOKEN_CLASSIC(0, "BLE-Token Classic", 1f, true, false, "BMI270 + Batterie, legacy RSSI"),

    /** nRF52840 Token Pro – erweiterter 16-Byte Payload mit Temp + Flags */
    TOKEN_PRO(1, "BLE-Token Pro", 2f, true, true, "IMU + Temp + Button + Battery"),

    /** Umwelt-Sensor-Tag – BME280/SHT4x, Luftqualität, Licht */
    SENSOR_TAG(2, "Sensor Tag", 0.5f, true, true, "Temp/Feuchte/Luft/Beleuchtung/Druck"),

    /** Wearable – Smartwatch/Tracker mit HRM, SpO2, Steps */
    WEARABLE(3, "Wearable", 10f, true, true, "Herzfrequenz, Schritte, HRV"),

    /** Asset-Tag – iBeacon / Eddystone für Asset-Tracking */
    ASSET_TAG(4, "Asset-Tag", 1f, true, false, "iBeacon + Eddystone UID/URL/TLM"),

    /** Fernbedienung / Controller – Buttons, Joystick */
    REMOTE_CONTROLLER(5, "Remote Controller", 20f, true, true, "Gamepad, Key-Fob, Joystick"),

    /** BLE-Relay – Smartphone, das BLE-Daten via MQTT weiterleitet */
    RELAY(6, "BLE-Relay", 5f, true, false, "Smartphone als BLE-MQTT-Brücke"),

    /** Gateway-Bridge – BLE ↔ WiFi/LoRa/Zigbee */
    GATEWAY_BRIDGE(7, "Gateway Bridge", 0.2f, false, true, "Protokoll-Übersetzer Multi-Radio"),

    /** LE Audio / Auracast Beacon */
    AUDIO_BEACON(8, "Audio Beacon", 1f, true, true, "LE Audio Broadcast / Auracast"),

    /** BT Classic SPP-Gerät (serielle Daten) */
    CLASSIC_SPP(9, "Classic SPP", 10f, true, false, "Bluetooth Classic RFCOMM/SPP"),

    /** BT Headset / HSP / A2DP – Audio accessory */
    HEADSET(10, "Headset", 1f, true, false, "Audio Eingabe/Ausgabe für Kommandos"),

    /** HID – Tastatur, Maus, Presenter */
    HID(11, "HID Device", 50f, true, false, "Bluetooth HID Eingabegerät"),

    /** Generisches unbekanntes BLE-Gerät */
    GENERIC_BLE(99, "Generic BLE", 1f, true, false, "Unklassifiziertes BLE-Gerät"),

    /** Generisches Classic BT-Gerät */
    GENERIC_CLASSIC(100, "Generic Classic", 1f, true, false, "Unklassifiziertes Classic-Gerät");

    companion object {
        fun fromCode(code: Int): BluetoothAccessoryType =
            values().find { it.code == code } ?: GENERIC_BLE

        fun fromAdvertisementName(name: String?): BluetoothAccessoryType {
            if (name == null) return GENERIC_BLE
            return when {
                name.startsWith("3dxAgent-Token", ignoreCase = true) -> TOKEN_PRO
                name.startsWith("3dx-T-", ignoreCase = true) -> TOKEN_CLASSIC
                name.contains("SENSOR", ignoreCase = true) -> SENSOR_TAG
                name.contains("Wearable", ignoreCase = true) -> WEARABLE
                name.contains("Asset", ignoreCase = true) -> ASSET_TAG
                name.contains("Remote", ignoreCase = true) -> REMOTE_CONTROLLER
                name.contains("Gateway", ignoreCase = true) -> GATEWAY_BRIDGE
                else -> GENERIC_BLE
            }
        }
    }
}

/** GATT Service UUIDs – 3dxAgent Custom + Standard */
object GattUuids {
    const val BATTERY_SERVICE = "0000180f-0000-1000-8000-00805f9b34fb"
    const val BATTERY_LEVEL = "00002a19-0000-1000-8000-00805f9b34fb"
    const val DEVICE_INFO = "0000180a-0000-1000-8000-00805f9b34fb"
    const val FIRMWARE_REV = "00002a26-0000-1000-8000-00805f9b34fb"
    const val ENV_SENSING = "0000181a-0000-1000-8000-00805f9b34fb"
    const val TEMPERATURE_CHAR = "00002a6e-0000-1000-8000-00805f9b34fb"
    const val HUMIDITY_CHAR = "00002a6f-0000-1000-8000-00805f9b34fb"
    const val HEART_RATE_SERVICE = "0000180d-0000-1000-8000-00805f9b34fb"
    const val HEART_RATE_MEASUREMENT = "00002a37-0000-1000-8000-00805f9b34fb"
    const val CUSTOM_3DX_SERVICE = "8d81e7c0-b7c8-4b26-b0ea-e8b10bc7f1e0"
    const val CUSTOM_DATA_CHAR = "8d81e7c1-b7c8-4b26-b0ea-e8b10bc7f1e1"
    const val CUSTOM_CONFIG_CHAR = "8d81e7c2-b7c8-4b26-b0ea-e8b10bc7f1e2"
    const val CUSTOM_COMMAND_CHAR = "8d81e7c3-b7c8-4b26-b0ea-e8b10bc7f1e3"
    const val HID_SERVICE = "00001812-0000-1000-8000-00805f9b34fb"
    const val DFU_SERVICE = "00001530-1212-efde-1523-785feabcd123"
}

/** Manufacturer IDs */
object ManufacturerIds {
    const val NORDIC_3DX = 0x0059 // Nordic – 3dxAgent Firma
    const val APPLE_IBEACON = 0x004C
    const val EDDYSTONE = 0x00E0 // Google (Service Data)
}

/** Flags im erweiterten Protocol V2 */
object AccessoryFlags {
    const val MOVING: Int = 1 shl 0
    const val BUTTON_PRESSED: Int = 1 shl 1
    const val LOW_BATTERY: Int = 1 shl 2
    const val TAMPER: Int = 1 shl 3
    const val CALIBRATING: Int = 1 shl 4
    const val OTA_AVAILABLE: Int = 1 shl 5
    const val FALL_DETECTED: Int = 1 shl 6
    const val SOS: Int = 1 shl 7
}
