package com.example.agent.devicedb

import org.junit.Assert.assertEquals
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
}
