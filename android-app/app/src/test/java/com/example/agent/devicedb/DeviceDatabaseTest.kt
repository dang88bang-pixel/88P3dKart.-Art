package com.example.agent.devicedb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceDatabaseTest {

    // ── GATT ──────────────────────────────────────────────────────

    @Test
    fun `gatt-standard-services decken die spec-tabelle ab`() {
        val uuids = DeviceDbCore.GATT_STANDARD_SERVICES.map { it.uuid }.toSet()
        for (expected in listOf("0x1800", "0x1801", "0x180A", "0x180D", "0x180F", "0x1816", "0x1826", "0x183A")) {
            assertTrue(expected in uuids)
        }
        val hr = DeviceDbCore.lookupGattService("180d")
        assertNotNull(hr)
        assertEquals("Heart Rate", hr!!.name)
        assertEquals("0x2A37", hr.characteristics.first { it.uuid == "0x2A37" }.uuid)
        assertNull(DeviceDbCore.lookupGattService("0xFFFF"))
    }

    @Test
    fun `uuid-normalisierung fuer 16 und 128 bit`() {
        assertEquals("0x180D", DeviceDbCore.normalizeUuid("180d"))
        assertEquals("0xFEED", DeviceDbCore.normalizeUuid("0xfeed"))
        assertEquals(
            "0x000015301212EFDE1523785FEABCD123",
            DeviceDbCore.normalizeUuid("00001530-1212-efde-1523-785feabcd123"),
        )
    }

    // ── Tracker (mit Spec-Korrekturen) ───────────────────────────

    @Test
    fun `tile-profile nutzt die korrigierten uuids`() {
        val tile = DeviceDbCore.TRACKER_PROFILES.first { it.id == "tile" }
        assertTrue("0xFEED" in tile.serviceUuids && "0xFEEC" in tile.serviceUuids)
        assertTrue("0xFEAA" !in tile.serviceUuids) // Eddystone (Google), nicht Tile
        assertNull(tile.companyId) // 0x0055 der Spec unbestätigt
    }

    @Test
    fun `tracker-lookup nach company und service`() {
        assertTrue(DeviceDbCore.lookupTracker(companyId = 0x004C).any { it.id == "apple_airtag" })
        assertTrue(DeviceDbCore.lookupTracker(serviceUuids = listOf("0xFEED")).any { it.id == "tile" })
        assertTrue(DeviceDbCore.lookupTracker(companyId = 0x00E0).any { it.id == "google_pixel_tag" })
    }

    // ── OUI ──────────────────────────────────────────────────────

    @Test
    fun `oui-lookup bevorzugt laengere praefixe`() {
        val db = DeviceDbCore.OuiDatabase(mapOf(
            "00:1A:22" to "Honeywell",
            "00:1A:22:33" to "Honeywell (MA-M-Block)",
        ))
        assertEquals("Honeywell (MA-M-Block)", db.lookup("00:1A:22:33:44:55"))
        assertEquals("Honeywell", db.lookup("00:1A:22:AA:BB:CC"))
        assertNull(db.lookup("00:1A:23:AA:BB:12"))
    }

    @Test
    fun `seed-oui deckt bekannte hersteller ab`() {
        val db = DeviceDbCore.OuiDatabase(DeviceDbCore.SEED_OUI)
        assertTrue(db.lookup("00:1A:22:AB:CD:EF")!!.contains("Honeywell"))
        assertTrue(db.lookup("00:0C:29:00:00:01")!!.contains("VMware"))
        assertTrue(db.lookup("00:11:22:33:44:55")!!.contains("Dell"))
    }

    @Test
    fun `mac-normalisierung akzeptiert separator-varianten`() {
        assertEquals("AA:BB:CC:DD:EE:FF", DeviceDbCore.normalizeMac("aa:bb:cc:dd:ee:ff"))
        assertEquals("AA:BB:CC:DD:EE:FF", DeviceDbCore.normalizeMac("AABB.CCDD.EEFF"))
        assertEquals("AA:BB:CC:DD:EE:FF", DeviceDbCore.normalizeMac("aabbccddeeff"))
    }

    // ── Datenbank ────────────────────────────────────────────────

    @Test
    fun `seed-datenbank unterstuetzt kategorien und suche`() {
        val db = DeviceDatabase()
        assertTrue(db.size() >= 8)
        val categories = db.categories()
        assertTrue(categories.getOrDefault("SMART_HOME", 0) >= 4)
        assertTrue(categories.getOrDefault("UWB", 0) >= 2)
        assertTrue(db.search("TRÅDFRI").any { it.id == "ikea_tradfri_e1603" })
        assertTrue(db.search("dwm", category = "UWB").any { it.id == "qorvo_dwm3000" })
        assertTrue(db.search("dwm", category = "SMART_HOME").isEmpty())
    }

    @Test
    fun `service-lookup findet scooter mit community-flag`() {
        val db = DeviceDatabase()
        val scooters = db.byService("0xFFE0")
        assertTrue(scooters.any { it.id == "xiaomi_m365" })
        assertTrue(scooters.all { !it.verified }) // Community-Quelle bleibt markiert
    }

    @Test
    fun `mac-lookup nutzt praefixe`() {
        val db = DeviceDatabase()
        db.upsert(DeviceDatabase.DeviceRecord(
            id = "hw", name = "Honeywell Gerät", type = "scanner", category = "OTHER",
            vendor = "Honeywell", macPrefix = "00:1A:22",
        ))
        assertEquals(1, db.byMac("00:1A:22:AA:BB:CC").size)
        assertTrue(db.byMac("00:1A:23:AA:BB:CC").isEmpty())
    }

    // ── Spec v17: erweiterte Kategorien & Company-IDs ────────────

    @Test
    fun `company-ids enthalten die verifizierten v17-korrekturen`() {
        val ids = DeviceDbCore.COMPANY_IDS
        assertEquals("Ericsson AB", ids[0x0000])            // Spec: „Ericsson Technology Licensing"
        assertEquals("Telemonitor, Inc.", ids[0x017A])      // Spec: „Telemontior" (Tippfehler)
        assertEquals("Xiaomi Inc.", ids[0x038F])            // Spec: 0xFDAB (existiert nicht)
        assertEquals("HP, Inc.", ids[0x0065])               // Spec: 0xFDB4 (existiert nicht)
        for (bogus in listOf(0xFDAB, 0xFDB0, 0xFDB4, 0xFDB5)) {
            assertTrue(bogus !in ids)
        }
        assertEquals("Universal Electronics, Inc.", ids[0x0093])
        assertEquals("LG Electronics", ids[0x00C4])
        assertEquals("taskit GmbH", ids[0x017B])
        assertEquals("Dexcom, Inc.", ids[0x00D0])
        assertEquals("Abbott", ids[0x03BB])
        assertEquals("Honeywell International Inc.", ids[0x0526])
        assertEquals("KKM COMPANY LIMITED", ids[0x0A53])
        assertEquals("SQL Technologies Corp.", ids[0x0A54])
    }

    @Test
    fun `company-id-normalisierung und lookup`() {
        assertEquals(0x004C, DeviceDbCore.normalizeCompanyId("0x004c"))
        assertEquals(0x004C, DeviceDbCore.normalizeCompanyId("76"))
        assertEquals(0x00D0, DeviceDbCore.normalizeCompanyId(" 0x00d0 "))
        assertNull(DeviceDbCore.normalizeCompanyId("zz"))
        assertNull(DeviceDbCore.normalizeCompanyId(""))
        assertEquals("Apple, Inc.", DeviceDbCore.lookupCompany("0x004C"))
        assertEquals("Dexcom, Inc.", DeviceDbCore.lookupCompany("208"))
        assertNull(DeviceDbCore.lookupCompany("0xFDAB"))
        assertNull(DeviceDbCore.lookupCompany("xyz"))
    }

    @Test
    fun `seed enthaelt die erweiterten v17-kategorien`() {
        val db = DeviceDatabase()
        val categories = db.categories()
        assertTrue(categories.getOrDefault("LORAWAN", 0) >= 16)
        assertTrue(categories.getOrDefault("METERING", 0) >= 8)
        assertTrue(categories.getOrDefault("ISM_433", 0) >= 6)
        assertTrue(categories.getOrDefault("MEDICAL", 0) >= 7)
        val tech = db.technologies()
        assertTrue(tech.getOrDefault("Thread", 0) >= 20)
        assertTrue(tech.getOrDefault("Matter", 0) >= 20)
        assertTrue(tech.getOrDefault("LoRaWAN", 0) >= 16)
        assertTrue(tech.getOrDefault("Wireless M-Bus", 0) >= 8)
        assertTrue(tech.getOrDefault("ISM 433 MHz", 0) >= 6)
    }

    @Test
    fun `technologie-filter in der suche`() {
        val db = DeviceDatabase()
        val lorawan = db.search("", technology = "LoRaWAN")
        assertTrue(lorawan.isNotEmpty())
        assertTrue(lorawan.all { "LoRaWAN" in it.technologies })
        assertTrue(lorawan.all { it.frequencyBands == listOf("EU868") })
        val thread = db.search("", technology = "thread")
        assertTrue(thread.isNotEmpty())
        assertTrue(thread.all { "Thread" in it.technologies })
        assertTrue(db.search("", category = "MEDICAL", technology = "LoRaWAN").isEmpty())
        assertTrue(db.search("alpstuga", technology = "Matter").any { it.id == "ikea_alpstuga" })
    }

    @Test
    fun `v17-seed-spezifika`() {
        val db = DeviceDatabase()
        val ikea2025 = db.search("", category = "SMART_HOME").filter {
            it.vendor == "IKEA" && it.model in listOf("MYGGSPRAY", "MYGGBETT", "KLIPPBOK", "TIMMERFLOTTE", "ALPSTUGA")
        }
        assertEquals(5, ikea2025.size) // 5 Sensoren, nicht „7" wie in der Spec
        assertEquals(listOf("EU868"), db.byId("dragino_lps8")!!.frequencyBands)
        assertTrue(db.byId("wilsen_node")!!.vendor.startsWith("Pepperl+Fuchs"))
        assertTrue(db.byId("zenner_wmbus_water_meter")!!.verified)
        assertFalse(db.byId("weptech_myna")!!.verified)
        assertEquals("MEDICAL", db.byId("dexcom_g7")!!.category)
        assertEquals(listOf("BLE", "NFC"), db.byId("abbott_freestyle_libre3")!!.technologies)
    }
}
