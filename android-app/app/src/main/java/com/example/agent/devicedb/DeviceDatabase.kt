package com.example.agent.devicedb

/**
 * Offline-Gerätedatenbank — Kotlin-Kern (docs/DEVICE_DATABASE.md).
 *
 * Spiegelung der Python-Implementierung (`edge-agent/device_db.py`) mit
 * identischer Semantik: GATT-Standard-Services, Hersteller-UUIDs (mit den
 * verifizierten Korrekturen — 0xFEAA = Eddystone/Google, Tile = 0xFEEC/
 * 0xFEED), Tracker-Profile, OUI-Lookup und eine In-Memory-Datenbank mit
 * MAC-/Service-/Volltext-Suche. Die Persistenz (SQLite/JSON) übernimmt in
 * der App die bestehende Room-Schicht (Roadmap).
 */
object DeviceDbCore {

    // ── GATT-Standard-Services (Bluetooth SIG, verifiziert) ─────

    data class GattCharacteristic(val uuid: String, val name: String, val properties: List<String>)

    data class GattService(val uuid: String, val name: String, val characteristics: List<GattCharacteristic>)

    val GATT_STANDARD_SERVICES: List<GattService> = listOf(
        GattService("0x1800", "Generic Access", listOf(
            GattCharacteristic("0x2A00", "Device Name", listOf("read")),
            GattCharacteristic("0x2A01", "Appearance", listOf("read")),
        )),
        GattService("0x1801", "Generic Attribute", listOf(
            GattCharacteristic("0x2A05", "Service Changed", listOf("indicate")),
        )),
        GattService("0x180A", "Device Information", listOf(
            GattCharacteristic("0x2A24", "Model Number String", listOf("read")),
            GattCharacteristic("0x2A25", "Serial Number String", listOf("read")),
            GattCharacteristic("0x2A26", "Firmware Revision String", listOf("read")),
            GattCharacteristic("0x2A29", "Manufacturer Name String", listOf("read")),
        )),
        GattService("0x180D", "Heart Rate", listOf(
            GattCharacteristic("0x2A37", "Heart Rate Measurement", listOf("notify")),
            GattCharacteristic("0x2A38", "Body Sensor Location", listOf("read")),
        )),
        GattService("0x180F", "Battery Service", listOf(
            GattCharacteristic("0x2A19", "Battery Level", listOf("read", "notify")),
        )),
        GattService("0x1816", "Cycling Speed and Cadence", listOf(
            GattCharacteristic("0x2A5B", "CSC Measurement", listOf("notify")),
            GattCharacteristic("0x2A5C", "CSC Feature", listOf("read")),
        )),
        GattService("0x1826", "Fitness Machine", listOf(
            GattCharacteristic("0x2ACC", "Fitness Machine Feature", listOf("read")),
            GattCharacteristic("0x2AD2", "Indoor Bike Data", listOf("notify")),
            GattCharacteristic("0x2AD9", "Fitness Machine Control Point", listOf("write", "indicate")),
        )),
        GattService("0x183A", "Environmental Sensing", listOf(
            GattCharacteristic("0x2A6E", "Temperature", listOf("read", "notify")),
            GattCharacteristic("0x2A6F", "Humidity", listOf("read", "notify")),
        )),
    )

    /** 16-Bit-UUID auf "0xXXXX" normalisieren; 128-Bit (Bindestriche) großschreiben. */
    fun normalizeUuid(uuid: String): String {
        var cleaned = uuid.trim().lowercase().replace(" ", "").replace("-", "")
        if (cleaned.startsWith("0x")) cleaned = cleaned.substring(2)
        return "0x" + cleaned.uppercase()
    }

    private val gattIndex: Map<String, GattService> =
        GATT_STANDARD_SERVICES.associateBy { normalizeUuid(it.uuid) }

    fun lookupGattService(uuid: String): GattService? = gattIndex[normalizeUuid(uuid)]

    // ── Company-IDs & Hersteller-UUIDs (verifizierte Korrekturen) ─

    val COMPANY_IDS: Map<Int, String> = mapOf(
        0x004C to "Apple, Inc.",
        0x0075 to "Samsung Electronics Co. Ltd.",
        0x00E0 to "Google",
    )

    data class VendorService(val uuid: String, val note: String)

    val VENDOR_SERVICE_UUIDS: Map<String, List<VendorService>> = mapOf(
        "apple" to listOf(
            VendorService("0xFD6F", "Apple Nearby Interaction (Find My, AirTag)"),
            VendorService("0xFD5A", "Apple (zusätzliche Service-Klasse)"),
        ),
        "samsung" to listOf(
            VendorService("0xFE6E", "Samsung (SmartThings/SmartTag-Kontext)"),
            VendorService("0xFE6F", "Samsung (Spec-Angabe)"),
        ),
        "tile" to listOf(
            // Korrektur: 0xFEAA ist Eddystone (Google), Tile = 0xFEEC/0xFEED
            VendorService("0xFEED", "Tile (Bluetooth-SIG-zugewiesen)"),
            VendorService("0xFEEC", "Tile (Bluetooth-SIG-zugewiesen)"),
        ),
        "google" to listOf(
            VendorService("0xFDF0", "Google Fast Pair"),
            VendorService("0xFEAA", "Eddystone (Google) — in der Spec fälschlich Tile zugeordnet"),
        ),
    )

    // ── Tracker-Profile ──────────────────────────────────────────

    data class TrackerProfile(
        val id: String,
        val vendor: String,
        val companyId: Int?,   // null = nicht bestätigt
        val serviceUuids: List<String>,
        val detection: String,
        val resetProcedure: String,
        val verified: Boolean,
    )

    val TRACKER_PROFILES: List<TrackerProfile> = listOf(
        TrackerProfile("apple_airtag", "Apple", 0x004C, listOf("0xFD6F", "0xFD5A"),
            "Advertising: Company 0x004C + Service 0xFD6F (Find My)",
            "AirTag: Batterie 30 s entfernen, wieder einsetzen", true),
        TrackerProfile("samsung_smarttag2", "Samsung", 0x0075, listOf("0xFE6E", "0xFE6F"),
            "Advertising: Company 0x0075 + Service 0xFE6E (SmartThings Find)",
            "SmartTag2: Taste 5 s halten, bis die LED blinkt", true),
        TrackerProfile("tile", "Tile/Life360", null, listOf("0xFEED", "0xFEEC"),
            "Advertising: Service 0xFEED/0xFEEC (Bluetooth-SIG-zugewiesen an Tile)",
            "Tile: Taste 2× drücken, dann 5 s halten", true),
        TrackerProfile("google_pixel_tag", "Google", 0x00E0, listOf("0xFDF0"),
            "Advertising: Fast Pair 0xFDF0 + Google Find My",
            "Pixel Tag: Taste 10 s halten", true),
    )

    fun lookupTracker(
        companyId: Int? = null,
        serviceUuids: List<String> = emptyList(),
    ): List<TrackerProfile> {
        val normalized = serviceUuids.map { normalizeUuid(it) }
        return TRACKER_PROFILES.filter { profile ->
            (companyId != null && profile.companyId == companyId) ||
                normalized.any { it in profile.serviceUuids }
        }
    }

    // ─── OUI-Lookup ─────────────────────────────────────────────

    /** Kuratierter OUI-Seed (EU-relevante Auswahl; volle DB via Builder). */
    val SEED_OUI: Map<String, String> = mapOf(
        "00:1A:22" to "Honeywell International Inc.",
        "00:01:C0" to "Honeywell",
        "00:0C:29" to "VMware, Inc.",
        "00:50:56" to "VMware, Inc.",
        "00:15:5D" to "Microsoft Corporation",
        "00:1B:21" to "Cisco Systems, Inc.",
        "00:1D:09" to "Cisco Systems, Inc.",
        "00:25:45" to "Cisco Systems, Inc.",
        "00:10:DB" to "Juniper Networks",
        "00:0F:B5" to "TP-Link Technologies Co., Ltd.",
        "00:1D:0F" to "TP-Link Technologies Co., Ltd.",
        "00:11:22" to "Dell Inc.",
        "00:14:22" to "Dell Inc.",
    )

    /** MAC auf "AA:BB:CC:DD:EE:FF" normalisieren. */
    fun normalizeMac(mac: String): String {
        val cleaned = mac.filter { it.isLetterOrDigit() }.uppercase()
        require(cleaned.length == 12) { "Ungültige MAC-Adresse: $mac" }
        return cleaned.chunked(2).joinToString(":")
    }

    class OuiDatabase(entries: Map<String, String> = emptyMap()) {
        private val entries = entries.mapKeys { it.key.uppercase() }.toMutableMap()

        fun add(prefix: String, vendor: String) {
            entries[prefix.uppercase()] = vendor
        }

        /** Längere Präfixe (MA-M/MA-S) gewinnen. */
        fun lookup(mac: String): String? {
            val normalized = normalizeMac(mac)
            return entries.entries
                .sortedByDescending { it.key.length }
                .firstOrNull { normalized.startsWith(it.key) }
                ?.value
        }

        fun size(): Int = entries.size
    }
}

/**
 * In-Memory-Gerätedatenbank (Seed + Suche) — Persistenz via Room (Roadmap).
 */
class DeviceDatabase {

    data class DeviceRecord(
        val id: String,
        val name: String,
        val type: String,
        val category: String,
        val vendor: String,
        val model: String = "",
        val technologies: List<String> = emptyList(),
        val serviceUuids: List<String> = emptyList(),
        val macPrefix: String? = null,
        val source: String = "seed",
        val verified: Boolean = true,
        val notes: String = "",
    )

    private val records = LinkedHashMap<String, DeviceRecord>()

    init {
        SEED_RECORDS.forEach { upsert(it) }
    }

    fun upsert(record: DeviceRecord) {
        records[record.id] = record
    }

    fun byId(id: String): DeviceRecord? = records[id]

    fun byMac(mac: String): List<DeviceRecord> {
        val normalized = DeviceDbCore.normalizeMac(mac)
        return records.values.filter { it.macPrefix != null && normalized.startsWith(it.macPrefix!!) }
    }

    fun byService(uuid: String): List<DeviceRecord> {
        val normalized = DeviceDbCore.normalizeUuid(uuid)
        return records.values.filter { normalized in it.serviceUuids }
    }

    fun search(query: String, category: String? = null): List<DeviceRecord> {
        val q = query.trim().lowercase()
        return records.values.filter { record ->
            if (category != null && record.category != category.uppercase()) return@filter false
            val haystack = listOf(record.name, record.type, record.category, record.vendor, record.model)
                .joinToString(" ").lowercase()
            q.isEmpty() || q in haystack
        }
    }

    fun categories(): Map<String, Int> =
        records.values.groupingBy { it.category }.eachCount()

    fun size(): Int = records.size

    companion object {
        val SEED_RECORDS: List<DeviceRecord> = listOf(
            DeviceRecord("ikea_tradfri_e1603", "TRÅDFRI LED Bulb E27", "light", "SMART_HOME",
                "IKEA", "E1603", listOf("Zigbee"), source = "zigbee2mqtt",
                notes = "Reset: Pairing-Taste 10 s"),
            DeviceRecord("philips_hue_9290012573", "Hue White E27", "light", "SMART_HOME",
                "Signify (Philips)", "9290012573", listOf("Zigbee", "BLE"), source = "zigbee2mqtt"),
            DeviceRecord("aqara_wsdcgq11lm", "Aqara Temperature/Humidity", "sensor", "SMART_HOME",
                "Xiaomi/Aqara", "WSDCGQ11LM", listOf("Zigbee"), source = "zigbee2mqtt",
                notes = "Reset: Taste 5× drücken"),
            DeviceRecord("sonoff_s31", "Sonoff S31 Lite", "plug", "SMART_HOME",
                "SONOFF", "S31", listOf("WiFi"), source = "vendor-docs",
                notes = "Reset: Taste 5 s → AP-Modus"),
            DeviceRecord("xiaomi_m365", "Mi Electric Scooter M365", "escooter", "VEHICLE",
                "Xiaomi", "M365", listOf("BLE"), listOf("0xFFE0"),
                source = "community", verified = false,
                notes = "Frames aus Community-Reverse-Engineering (m365py) — nicht zertifiziert"),
            DeviceRecord("ninebot_es2", "Ninebot ES2", "escooter", "VEHICLE",
                "Segway-Ninebot", "ES2", listOf("BLE"), listOf("0xFFE0"),
                source = "community", verified = false),
            DeviceRecord("qorvo_dwm3000", "DWM3000 UWB Module", "uwb_module", "UWB",
                "Qorvo", "DWM3000 (DW3110)", listOf("UWB"), source = "vendor-docs",
                notes = "IEEE 802.15.4z, FiRa-kompatibel"),
            DeviceRecord("nxp_sr150", "Trimension SR150", "uwb_module", "UWB",
                "NXP", "SR150", listOf("UWB"), source = "vendor-docs"),
        )
    }
}
