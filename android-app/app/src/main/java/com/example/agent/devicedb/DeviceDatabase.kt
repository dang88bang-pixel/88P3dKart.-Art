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
    //
    // Kuratierte Auswahl aus der Bluetooth-Numbers-DB (Nordic/SIG).
    // Spec-v17-Korrekturen: 0x0000 = "Ericsson AB"; 0x017A = "Telemonitor";
    // Xiaomi = 0x038F (nicht 0xFDAB); HP = 0x0065 (nicht 0xFDB4);
    // 0xFDB0/0xFDB5 existieren nicht als Company-IDs.

    val COMPANY_IDS: Map<Int, String> = mapOf(
        0x0000 to "Ericsson AB",
        0x0001 to "Nokia Mobile Phones",
        0x0002 to "Intel Corp.",
        0x0003 to "IBM Corp.",
        0x000D to "Texas Instruments Inc.",
        0x001F to "AVM Berlin",
        0x0025 to "NXP B.V.",
        0x004C to "Apple, Inc.",
        0x0059 to "Nordic Semiconductor ASA",
        0x005C to "Belkin International, Inc.",
        0x0065 to "HP, Inc.",
        0x0067 to "GN Hearing",
        0x006B to "Polar Electro OY",
        0x0075 to "Samsung Electronics Co. Ltd.",
        0x0093 to "Universal Electronics, Inc.",
        0x00C4 to "LG Electronics",
        0x00CE to "Eve Systems GmbH",
        0x00D0 to "Dexcom, Inc.",
        0x00E0 to "Google",
        0x0171 to "Amazon.com Services LLC",
        0x017A to "Telemonitor, Inc.",
        0x017B to "taskit GmbH",
        0x017E to "BluDotz Ltd",
        0x038F to "Xiaomi Inc.",
        0x03BB to "Abbott",
        0x03D5 to "Wyzelink Systems Inc.",
        0x0520 to "Target Corporation",
        0x0526 to "Honeywell International Inc.",
        0x0544 to "OrthoSensor, Inc.",
        0x0568 to "Bodyport Inc.",
        0x05C8 to "SOMFY SAS",
        0x0739 to "Jiangsu Qinheng Co., Ltd.",
        0x0A53 to "KKM COMPANY LIMITED",
        0x0A54 to "SQL Technologies Corp.",
    )

    /** Company-ID normalisieren: "0x004C", "004C", "76" oder Int → Int (null = ungültig). */
    fun normalizeCompanyId(value: String?): Int? {
        val text = value?.trim()?.lowercase()?.replace(" ", "") ?: return null
        if (text.isEmpty()) return null
        return try {
            when {
                text.startsWith("0x") -> text.removePrefix("0x").toInt(16)
                text.all { it in "0123456789" } -> text.toInt(10)
                text.all { it in "0123456789abcdef" } -> text.toInt(16)
                else -> null
            }
        } catch (_: NumberFormatException) {
            null
        }
    }

    /** Company-ID → Herstellername (null = unbekannt/ungültig). */
    fun lookupCompany(value: String?): String? {
        val companyId = normalizeCompanyId(value) ?: return null
        return COMPANY_IDS[companyId]
    }

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
        val frequencyBands: List<String> = emptyList(),
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

    fun search(query: String, category: String? = null, technology: String? = null): List<DeviceRecord> {
        val q = query.trim().lowercase()
        val tech = technology?.trim()?.lowercase() ?: ""
        return records.values.filter { record ->
            if (category != null && record.category != category.uppercase()) return@filter false
            if (tech.isNotEmpty() && record.technologies.none { tech == it.trim().lowercase() }) return@filter false
            val haystack = listOf(record.name, record.type, record.category, record.vendor, record.model)
                .joinToString(" ").lowercase()
            q.isEmpty() || q in haystack
        }
    }

    fun categories(): Map<String, Int> =
        records.values.groupingBy { it.category }.eachCount()

    /** Zählt Records je Technologie (ein Record kann mehrere Technologien haben). */
    fun technologies(): Map<String, Int> =
        records.values.flatMap { it.technologies }
            .groupingBy { it.trim() }
            .eachCount()

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
            // ── Thread/Matter (Spec 17.x, verifiziert) ──────────────
            DeviceRecord("eve_door_window", "Eve Door & Window (Matter)", "sensor", "SMART_HOME",
                "Eve Systems GmbH", "20EBT4101", listOf("Thread", "Matter"), source = "vendor-docs",
                notes = "Matter-over-Thread; Company-ID 0x00CE (SIG)"),
            DeviceRecord("eve_thermo", "Eve Thermo (Matter)", "thermostat", "SMART_HOME",
                "Eve Systems GmbH", "20EAM9901", listOf("Thread", "Matter"), source = "vendor-docs"),
            DeviceRecord("nanoleaf_essentials_a19", "Nanoleaf Essentials A19", "light", "SMART_HOME",
                "Nanoleaf", "NL45", listOf("Thread", "Matter"), source = "vendor-docs"),
            DeviceRecord("aqara_hub_m3", "Aqara Smart Hub M3", "hub", "SMART_HOME",
                "Aqara", "HM-G01D", listOf("Thread", "Matter", "Zigbee", "WiFi"), source = "vendor-docs",
                notes = "Matter-Bridge + Thread-Border-Router"),
            DeviceRecord("ikea_dirigera", "IKEA DIRIGERA Hub", "hub", "SMART_HOME",
                "IKEA", "E2201", listOf("Thread", "Matter", "Zigbee", "WiFi"), source = "vendor-docs"),
            DeviceRecord("apple_homepod_mini", "HomePod mini", "hub", "SMART_HOME",
                "Apple", "A2374", listOf("Thread", "Matter", "WiFi", "BLE"), source = "vendor-docs"),
            DeviceRecord("apple_tv_4k_3g", "Apple TV 4K (3. Gen)", "hub", "SMART_HOME",
                "Apple", "A2737", listOf("Thread", "Matter", "WiFi"), source = "vendor-docs"),
            DeviceRecord("google_tv_streamer", "Google TV Streamer (4K)", "hub", "SMART_HOME",
                "Google", "GA05662", listOf("Thread", "Matter", "WiFi"), source = "vendor-docs"),
            DeviceRecord("google_nest_hub_2", "Google Nest Hub (2. Gen)", "hub", "SMART_HOME",
                "Google", "AQC3", listOf("Thread", "Matter", "WiFi"), source = "vendor-docs"),
            DeviceRecord("amazon_echo_4g", "Amazon Echo (4. Gen)", "hub", "SMART_HOME",
                "Amazon", "D9N29T", listOf("Thread", "Matter", "Zigbee", "WiFi"), source = "vendor-docs"),
            DeviceRecord("amazon_eero", "Amazon eero", "router", "SMART_HOME",
                "Amazon (eero)", "K010001", listOf("Thread", "Matter", "WiFi"), source = "vendor-docs"),
            DeviceRecord("philips_hue_bridge", "Hue Bridge (Matter)", "hub", "SMART_HOME",
                "Signify (Philips)", "BSB002", listOf("Zigbee", "Matter", "WiFi"), source = "vendor-docs"),
            DeviceRecord("sonoff_zb_bridge_ultra", "SONOFF Zigbee Bridge Ultra", "hub", "SMART_HOME",
                "SONOFF", "ZB Bridge-U", listOf("Zigbee", "Matter"), source = "vendor-docs"),
            DeviceRecord("shelly_plug_s_gen3", "Shelly Plug S Gen3 (Matter)", "plug", "SMART_HOME",
                "Shelly", "Plug S Gen3", listOf("Matter", "WiFi"), source = "vendor-docs",
                notes = "Spec-Modellangabe „MTR“ unbestätigt — als Gen3-Baureihe geführt"),
            DeviceRecord("tp_link_tapo_p110", "Tapo P110 (Matter)", "plug", "SMART_HOME",
                "TP-Link", "P110M", listOf("Matter", "WiFi"), source = "vendor-docs"),
            DeviceRecord("tado_thermostat_x", "Tado Thermostat X", "thermostat", "SMART_HOME",
                "Tado", "Tado X", listOf("Thread", "Matter"), source = "vendor-docs"),
            DeviceRecord("wiz_light_matter", "WiZ Smart Bulb (Matter)", "light", "SMART_HOME",
                "WiZ (Signify)", "A19 Matter", listOf("Matter", "WiFi"), source = "vendor-docs"),
            DeviceRecord("yale_assure_lock_2_matter", "Yale Assure Lock 2 (Matter)", "lock", "SMART_HOME",
                "Yale", "YRD410", listOf("Thread", "Matter", "BLE"), source = "vendor-docs"),
            DeviceRecord("belkin_wemo_stage", "Wemo Stage Scene Controller", "remote", "SMART_HOME",
                "Belkin", "WSC010", listOf("Thread", "Matter"), source = "vendor-docs"),
            DeviceRecord("level_lock_plus", "Level Lock+", "lock", "SMART_HOME",
                "Level Home", "A0284", listOf("Thread", "Matter", "BLE"), source = "vendor-docs"),
            DeviceRecord("ikea_myggspray", "IKEA MYGGSPRAY", "sensor", "SMART_HOME",
                "IKEA", "MYGGSPRAY", listOf("Thread", "Matter"), source = "vendor-docs",
                notes = "Bewegungssensor, Nachfolger Vallhorn; Marktstart Jan 2026"),
            DeviceRecord("ikea_myggbett", "IKEA MYGGBETT", "sensor", "SMART_HOME",
                "IKEA", "MYGGBETT", listOf("Thread", "Matter"), source = "vendor-docs",
                notes = "Tür-/Fenstersensor, Nachfolger Parasoll"),
            DeviceRecord("ikea_klippbok", "IKEA KLIPPBOK", "sensor", "SMART_HOME",
                "IKEA", "KLIPPBOK", listOf("Thread", "Matter"), source = "vendor-docs",
                notes = "Wasserleck-Sensor, Nachfolger Badring"),
            DeviceRecord("ikea_timmerflotte", "IKEA TIMMERFLOTTE", "sensor", "SMART_HOME",
                "IKEA", "TIMMERFLOTTE", listOf("Thread", "Matter"), source = "vendor-docs",
                notes = "Temperatur-/Feuchtesensor mit Display"),
            DeviceRecord("ikea_alpstuga", "IKEA ALPSTUGA", "sensor", "SMART_HOME",
                "IKEA", "ALPSTUGA", listOf("Thread", "Matter"), source = "vendor-docs",
                notes = "Luftqualitätsmonitor (CO2, PM2.5)"),
            // ── LoRaWAN (TTN Device Repository; EU868) ───────────────
            DeviceRecord("dragino_lps8", "Dragino LPS8 Indoor Gateway", "gateway", "LORAWAN",
                "Dragino", "LPS8", listOf("LoRaWAN"), frequencyBands = listOf("EU868"),
                source = "ttn-device-repository", notes = "8-Kanal-Indoor-Gateway, OpenWrt"),
            DeviceRecord("dragino_lht65", "Dragino LHT65", "sensor", "LORAWAN",
                "Dragino", "LHT65", listOf("LoRaWAN"), frequencyBands = listOf("EU868"),
                source = "ttn-device-repository", notes = "Temperatur/Feuchte, Batterie"),
            DeviceRecord("rak_rak7268", "RAKwireless RAK7268 LTE WisGate Edge Lite 2", "gateway", "LORAWAN",
                "RAKwireless", "RAK7268", listOf("LoRaWAN"), frequencyBands = listOf("EU868"),
                source = "ttn-device-repository", notes = "Indoor-Gateway mit LTE-Backhaul"),
            DeviceRecord("rak_rak7205", "RAKwireless RAK7205", "tracker", "LORAWAN",
                "RAKwireless", "RAK7205", listOf("LoRaWAN"), frequencyBands = listOf("EU868"),
                source = "ttn-device-repository", notes = "GPS-Tracker mit Beschleunigungssensor"),
            DeviceRecord("minew_lsg01", "Minew LSG01 Air Quality", "sensor", "LORAWAN",
                "Minew", "LSG01", listOf("LoRaWAN"), frequencyBands = listOf("EU868"),
                source = "ttn-device-repository", notes = "6-in-1-Luftqualitätssensor (Spec-Angabe)"),
            DeviceRecord("minew_lsd01", "Minew LSD01 Door Sensor", "sensor", "LORAWAN",
                "Minew", "LSD01", listOf("LoRaWAN"), frequencyBands = listOf("EU868"),
                source = "ttn-device-repository", notes = "Tür-/Fenstersensor (Spec-Angabe)"),
            DeviceRecord("minew_ltb01g", "Minew LTB01-G GPS Asset Tracker", "tracker", "LORAWAN",
                "Minew", "LTB01-G", listOf("LoRaWAN"), frequencyBands = listOf("EU868"),
                source = "ttn-device-repository", notes = "Asset-Tracker (Spec-Angabe)"),
            DeviceRecord("elsist_em300_th", "Elsist EM300-TH", "sensor", "LORAWAN",
                "Elsist", "EM300-TH", listOf("LoRaWAN"), frequencyBands = listOf("EU868"),
                source = "ttn-device-repository"),
            DeviceRecord("elsist_em300_mcs", "Elsist EM300-MCS", "sensor", "LORAWAN",
                "Elsist", "EM300-MCS", listOf("LoRaWAN"), frequencyBands = listOf("EU868"),
                source = "ttn-device-repository"),
            DeviceRecord("elsist_em300_sld", "Elsist EM300-SLD", "sensor", "LORAWAN",
                "Elsist", "EM300-SLD", listOf("LoRaWAN"), frequencyBands = listOf("EU868"),
                source = "ttn-device-repository"),
            DeviceRecord("elsist_em300_di", "Elsist EM300-DI", "sensor", "LORAWAN",
                "Elsist", "EM300-DI", listOf("LoRaWAN"), frequencyBands = listOf("EU868"),
                source = "ttn-device-repository"),
            DeviceRecord("imst_ioke868", "IMST iOKE868 Smart Meter Reader", "meter_reader", "LORAWAN",
                "IMST", "iOKE868", listOf("LoRaWAN"), frequencyBands = listOf("EU868"),
                source = "ttn-device-repository", notes = "Zählerauslesung über LoRaWAN"),
            DeviceRecord("wilsen_node", "WILSEN.node", "sensor", "LORAWAN",
                "Pepperl+Fuchs (WILSEN)", "WILSEN.node", listOf("LoRaWAN"), frequencyBands = listOf("EU868"),
                source = "ttn-device-repository", notes = "Sensorknoten für Füllstand/Abstand"),
            DeviceRecord("netvox_r718n37", "Netvox R718N37", "meter", "LORAWAN",
                "Netvox", "R718N37", listOf("LoRaWAN"), frequencyBands = listOf("EU868"),
                source = "ttn-device-repository", notes = "Drehstromzähler-Messkopf (Spec-Angabe)"),
            DeviceRecord("multitech_rbs3010", "MultiTech RBS3010 Door/Window (EU868)", "sensor", "LORAWAN",
                "MultiTech", "RBS3010", listOf("LoRaWAN"), frequencyBands = listOf("EU868"),
                source = "ttn-device-repository", notes = "Radio-Bridge-Sensorlinie"),
            DeviceRecord("m5stack_atom_dtu_lorawan", "M5Stack ATOM DTU LoRaWAN (EU868)", "dtu", "LORAWAN",
                "M5Stack", "A152-EU868", listOf("LoRaWAN"), frequencyBands = listOf("EU868"),
                source = "vendor-docs", notes = "Entwicklungs-DTU, ATOM-Basis"),
            DeviceRecord("zenner_iot_gateway_outdoor", "ZENNER IoT Gateway Outdoor", "gateway", "LORAWAN",
                "ZENNER", "IoT Gateway outdoor", listOf("LoRaWAN"), frequencyBands = listOf("EU868"),
                source = "spec", verified = false,
                notes = "Gateway-Modellbezeichnung „16“ nicht verifiziert"),
            // ── Wireless M-Bus / Smart Metering (OMS, 868 MHz) ───────
            DeviceRecord("zenner_wmbus_water_meter", "ZENNER Wasserzähler (wM-Bus)", "water_meter", "METERING",
                "ZENNER", "EDC B.One (Modul)", listOf("Wireless M-Bus"), frequencyBands = listOf("868 MHz"),
                source = "vendor-docs", notes = "Ultraschall-Wasserzähler; EDC B.One wM-Bus/LoRaWAN wählbar"),
            DeviceRecord("zenner_wmbus_heat_meter", "ZENNER Wärmezähler (wM-Bus)", "heat_meter", "METERING",
                "ZENNER", "Heizko + wM-Bus", listOf("Wireless M-Bus"), frequencyBands = listOf("868 MHz"),
                source = "vendor-docs", notes = "Fernauslesung nach OMS"),
            DeviceRecord("elvaco_cme_series", "Elvaco CMe-Serie", "meter_module", "METERING",
                "Elvaco", "CMe", listOf("Wireless M-Bus"), frequencyBands = listOf("868 MHz"),
                source = "vendor-docs", notes = "Zähler-Kommunikationsmodule (wM-Bus)"),
            DeviceRecord("weptech_myna", "WEPTECH Myna", "sensor", "METERING",
                "WEPTECH", "Myna", listOf("Wireless M-Bus"), frequencyBands = listOf("868 MHz"),
                source = "spec", verified = false, notes = "Temperatursensor (Spec-Angabe, Modell unverifiziert)"),
            DeviceRecord("weptech_munia", "WEPTECH Munia", "sensor", "METERING",
                "WEPTECH", "Munia", listOf("Wireless M-Bus"), frequencyBands = listOf("868 MHz"),
                source = "spec", verified = false, notes = "Temperatur/Feuchte (Spec-Angabe, Modell unverifiziert)"),
            DeviceRecord("solvimus_mbus_gewb", "Solvimus MBUS-GEWB Gateway", "gateway", "METERING",
                "Solvimus", "MBUS-GEWB", listOf("Wireless M-Bus"), frequencyBands = listOf("868 MHz"),
                source = "spec", verified = false, notes = "wM-Bus-Gateway (Spec-Angabe)"),
            DeviceRecord("solvimus_mbus_ge5b", "Solvimus MBUS-GE5B Gateway", "gateway", "METERING",
                "Solvimus", "MBUS-GE5B", listOf("Wireless M-Bus"), frequencyBands = listOf("868 MHz"),
                source = "spec", verified = false, notes = "wM-Bus-Gateway (Spec-Angabe)"),
            DeviceRecord("stackforce_wmbus_stack", "Stackforce wM-Bus Protocol Stack", "software_stack", "METERING",
                "Stackforce", "wM-Bus Stack", listOf("Wireless M-Bus"), frequencyBands = listOf("868 MHz"),
                source = "vendor-docs", notes = "Protokoll-Stack für OMS/wM-Bus (EN 13757)"),
            // ── ISM 433 MHz (generische Klassen; SRD 433,05–434,79 MHz) ─
            DeviceRecord("ism433_garage_remote", "Garagentor-Funkfernbedienung", "remote_control", "ISM_433",
                "Generic (433 MHz ISM)", "class", listOf("ISM 433 MHz"), frequencyBands = listOf("433.05–434.79 MHz"),
                source = "regulation", notes = "Generische Klasse"),
            DeviceRecord("ism433_shutter_remote", "Rollladen-Funkfernbedienung", "remote_control", "ISM_433",
                "Generic (433 MHz ISM)", "class", listOf("ISM 433 MHz"), frequencyBands = listOf("433.05–434.79 MHz"),
                source = "regulation", notes = "Generische Klasse"),
            DeviceRecord("ism433_door_sensor", "Fenster-/Türsensor (433 MHz)", "sensor", "ISM_433",
                "Generic (433 MHz ISM)", "class", listOf("ISM 433 MHz"), frequencyBands = listOf("433.05–434.79 MHz"),
                source = "regulation", notes = "Generische Klasse"),
            DeviceRecord("ism433_smoke_detector", "Rauchmelder (433 MHz)", "smoke_detector", "ISM_433",
                "Generic (433 MHz ISM)", "class", listOf("ISM 433 MHz"), frequencyBands = listOf("433.05–434.79 MHz"),
                source = "regulation", notes = "Generische Klasse"),
            DeviceRecord("ism433_baby_monitor", "Babyphone (433 MHz)", "audio_device", "ISM_433",
                "Generic (433 MHz ISM)", "class", listOf("ISM 433 MHz"), frequencyBands = listOf("433.05–434.79 MHz"),
                source = "regulation", notes = "Generische Klasse (analoge/433-MHz-Modelle)"),
            DeviceRecord("ism433_radar_motion", "Radar-Bewegungsmelder (433 MHz)", "motion_detector", "ISM_433",
                "Generic (433 MHz ISM)", "class", listOf("ISM 433 MHz"), frequencyBands = listOf("433.05–434.79 MHz"),
                source = "regulation", notes = "Generische Klasse (CW-/Doppler-Radar)"),
            // ── Medizinische BLE-Geräte ─────────────────────────────
            DeviceRecord("dexcom_g7", "Dexcom G7 CGM", "cgm", "MEDICAL",
                "Dexcom", "G7", listOf("BLE"), source = "vendor-docs",
                notes = "Kontinuierliche Glukosemessung; Company-ID 0x00D0 (SIG)"),
            DeviceRecord("abbott_freestyle_libre3", "FreeStyle Libre 3", "cgm", "MEDICAL",
                "Abbott", "Libre 3", listOf("BLE", "NFC"), source = "vendor-docs",
                notes = "Glukosesensor; Company-ID 0x03BB (SIG)"),
            DeviceRecord("oura_ring_gen4", "Oura Ring Gen4", "smart_ring", "MEDICAL",
                "Oura Health", "Gen4", listOf("BLE"), source = "vendor-docs",
                notes = "Company-ID der Spec (0xFDB0) existiert nicht — nicht übernommen"),
            DeviceRecord("whoop_40", "WHOOP 4.0", "wearable", "MEDICAL",
                "WHOOP", "4.0", listOf("BLE"), source = "vendor-docs"),
            DeviceRecord("biobeat_patch", "BioBeat Brustpatch", "vital_monitor", "MEDICAL",
                "BioBeat", "BB-613WP", listOf("BLE"), source = "vendor-docs"),
            DeviceRecord("empatica_embraceplus", "Empatica EmbracePlus", "vital_monitor", "MEDICAL",
                "Empatica", "EmbracePlus", listOf("BLE"), source = "vendor-docs",
                notes = "Medizinisches Wearable (FDA-zugelassen)"),
            DeviceRecord("hearing_aid_le_audio", "Bluetooth-Hörgerät (LE Audio/HAP)", "hearing_aid", "MEDICAL",
                "Phonak/Signia/ReSound u. a.", "class", listOf("BLE"), source = "vendor-docs",
                notes = "Generische Klasse; LE Audio (HAP) nach Bluetooth 5.2+"),
        )
    }
}
