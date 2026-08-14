package com.example.agent.device

import com.example.agent.device.DeviceModels.CapabilityType
import com.example.agent.device.DeviceModels.Device
import com.example.agent.device.DeviceModels.DeviceCapability
import com.example.agent.device.DeviceModels.DeviceCategory
import com.example.agent.device.DeviceModels.DeviceStatus
import com.example.agent.device.DeviceModels.DeviceType
import com.example.agent.device.DeviceModels.Position3D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceRegistryTest {

    private val readCap = DeviceCapability(CapabilityType.READ_DATA, "Daten lesen")
    private val execCap = DeviceCapability(CapabilityType.EXECUTE_COMMAND, "Befehl ausführen")

    private fun device(
        id: String = "dev-1",
        name: String = "Token 1",
        type: DeviceType = DeviceType.BLE_TOKEN,
        category: DeviceCategory = DeviceCategory.SENSOR,
        capabilities: List<DeviceCapability>? = listOf(readCap),
        status: DeviceStatus = DeviceStatus.ONLINE,
        lastSeenMs: Long = 1_000_000L,
        batteryLevel: Int? = null,
    ) = Device(
        id = id, name = name, type = type, category = category,
        position = Position3D(0f, 0f, 0f), status = status,
        capabilities = capabilities, lastSeenMs = lastSeenMs,
        batteryLevel = batteryLevel,
    )

    // ── Upsert ───────────────────────────────────────────────────

    @Test
    fun `upsert fuegt ein und aktualisiert felder`() {
        val registry = DeviceRegistry()
        registry.upsertDevice(device())
        assertEquals(1, registry.devices.value.size)

        registry.upsertDevice(device(name = "Token Neu", batteryLevel = 80))
        val updated = registry.devices.value.first()
        assertEquals("Token Neu", updated.name)
        assertEquals(80, updated.batteryLevel)
    }

    @Test
    fun `upsert behaelt capabilities wenn weggelassen`() {
        val registry = DeviceRegistry()
        registry.upsertDevice(device(capabilities = listOf(readCap, execCap)))
        // Update ohne Capabilities → vorhandene bleiben (Spec-Fix)
        registry.upsertDevice(device(name = "Update", capabilities = null))
        assertEquals(
            setOf(CapabilityType.READ_DATA, CapabilityType.EXECUTE_COMMAND),
            registry.devices.value.first().capabilities!!.map { it.type }.toSet(),
        )
        // Explizite neue Liste → ersetzt
        registry.upsertDevice(device(capabilities = listOf(readCap)))
        assertEquals(1, registry.devices.value.first().capabilities!!.size)
    }

    // ── Layer ────────────────────────────────────────────────────

    @Test
    fun `layer-sichtbarkeit propagiert auf die kategorie`() {
        val registry = DeviceRegistry()
        registry.upsertDevice(device(id = "a"))
        registry.upsertDevice(device(id = "b", type = DeviceType.WIFI_AP, category = DeviceCategory.NETWORK))

        assertTrue(registry.setLayerVisibility("sensors", false))
        val sensor = registry.get("a")!!
        val network = registry.get("b")!!
        assertFalse(sensor.isVisible)
        assertTrue(network.isVisible)
        assertEquals(listOf("b"), registry.visibleDevices().map { it.id })

        assertFalse(registry.setLayerVisibility("gibt_es_nicht", false))
    }

    // ── Selektion ────────────────────────────────────────────────

    @Test
    fun `entfernen raeumt die selektion`() {
        val registry = DeviceRegistry()
        registry.upsertDevice(device())
        registry.selectDevice("dev-1")
        assertNotNull(registry.selectedDevice.value)
        assertTrue(registry.removeDevice("dev-1"))
        assertNull(registry.selectedDevice.value)
        assertFalse(registry.removeDevice("dev-1"))
    }

    // ── Staleness ────────────────────────────────────────────────

    @Test
    fun `markStale setzt online-geraete ohne lebenszeichen offline`() {
        val registry = DeviceRegistry()
        registry.upsertDevice(device(id = "fresh", lastSeenMs = 1_000_000L))
        registry.upsertDevice(device(id = "stale", lastSeenMs = 100_000L))
        val changed = registry.markStale(nowMs = 1_000_000L, staleAfterMs = 120_000L)
        assertEquals(1, changed)
        assertEquals(DeviceStatus.ONLINE, registry.get("fresh")!!.status)
        assertEquals(DeviceStatus.OFFLINE, registry.get("stale")!!.status)
    }

    // ── Action-Engine ────────────────────────────────────────────

    @Test
    fun `action-engine prueft capabilities`() {
        val registry = DeviceRegistry()
        registry.upsertDevice(device(capabilities = listOf(readCap))) // kein EXECUTE_COMMAND
        val engine = DeviceActionEngine(registry)

        assertTrue(engine.execute("dev-1", "read_status").success)
        assertTrue(engine.execute("dev-1", "locate").success)
        val blocked = engine.execute("dev-1", "toggle_led")
        assertFalse(blocked.success)
        assertTrue(blocked.message.contains("unterstützt diese Aktion nicht"))
    }

    @Test
    fun `action-engine behandelt unbekannte geraete und aktionen`() {
        val registry = DeviceRegistry()
        val engine = DeviceActionEngine(registry)
        assertFalse(engine.execute("gibts-nicht", "read_status").success)
        registry.upsertDevice(device())
        assertFalse(engine.execute("dev-1", "gibts-nicht").success)
    }

    @Test
    fun `standard-aktionen funktionieren deterministisch`() {
        val registry = DeviceRegistry()
        registry.upsertDevice(device(capabilities = listOf(readCap, execCap)))
        val engine = DeviceActionEngine(registry)

        val status = engine.execute("dev-1", "read_status")
        assertTrue(status.success)
        assertEquals("ONLINE", status.data["status"])

        val locate = engine.execute("dev-1", "locate")
        assertEquals("0.0", locate.data["x"])

        val toggle = engine.execute("dev-1", "toggle_led", mapOf("state" to "true"))
        assertTrue(toggle.success)
        assertEquals("LED an", toggle.message)

        val hide = engine.execute("dev-1", "set_visibility", mapOf("visible" to "false"))
        assertTrue(hide.success)
        assertFalse(registry.get("dev-1")!!.isVisible)
    }

    @Test
    fun `actionsForDevice filtert nach capability`() {
        val registry = DeviceRegistry()
        registry.upsertDevice(device(capabilities = listOf(readCap)))
        val engine = DeviceActionEngine(registry)
        val ids = engine.actionsForDevice(registry.get("dev-1")!!).map { it.id }.toSet()
        assertTrue("read_status" in ids && "locate" in ids)
        assertTrue("toggle_led" !in ids && "set_visibility" !in ids)
    }

    // ── Source-Mapper ────────────────────────────────────────────

    @Test
    fun `mapper bildet ble-token korrekt ab`() {
        val device = DeviceSourceMapper.fromBle(
            DeviceSourceMapper.BleSource(mac = "AA:BB:CC:DD:EE:FF", rssi = -62, battery = 87)
        )
        assertEquals("AA:BB:CC:DD:EE:FF", device.id)
        assertEquals("BLE-Token EE:FF", device.name)
        assertEquals(DeviceType.BLE_TOKEN, device.type)
        assertEquals(DeviceCategory.SENSOR, device.category)
        assertEquals(87, device.batteryLevel)
        assertEquals(-62, device.signalStrength)
        assertEquals(2, device.capabilities!!.size)
    }

    @Test
    fun `mapper normalisiert netzwerktypen und kategorien`() {
        val ap = DeviceSourceMapper.fromNetwork(
            DeviceSourceMapper.NetworkSource("ap-1", "Office 5G", "wifi_ap", -45.0, 1_000L)
        )
        assertEquals(DeviceType.WIFI_AP, ap.type)
        assertEquals(DeviceCategory.NETWORK, ap.category)

        val unknown = DeviceSourceMapper.fromNetwork(
            DeviceSourceMapper.NetworkSource("x", "X", "gibt_es_nicht", -50.0, 1_000L)
        )
        assertEquals(DeviceType.UNKNOWN, unknown.type)
        assertEquals(DeviceCategory.OTHER, unknown.category)
    }

    @Test
    fun `mapper leitet den staleness-status ab`() {
        val now = 1_000_000L
        val fresh = DeviceSourceMapper.fromNetwork(
            DeviceSourceMapper.NetworkSource("f", "F", "ble_device", -50.0, now - 10_000L)
        )
        assertEquals(DeviceStatus.ONLINE, fresh.status)
        val stale = DeviceSourceMapper.fromNetwork(
            DeviceSourceMapper.NetworkSource("s", "S", "ble_device", -50.0, now - 500_000L)
        )
        assertEquals(DeviceStatus.OFFLINE, stale.status)
    }

    @Test
    fun `mapper bildet mmwave-targets korrekt ab`() {
        val device = DeviceSourceMapper.fromTarget(
            DeviceSourceMapper.TargetSource("t-1", 1.5f, 2.5f, 0f, 1.2f)
        )
        assertEquals(DeviceType.MMWAVE_RADAR, device.type)
        assertEquals(1.5f, device.position.x, 1e-6f)
        assertTrue(device.name.contains("1.2"))
    }
}
