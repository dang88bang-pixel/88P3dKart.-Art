package com.example.agent.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.example.agent.network.ClientRegistry
import com.example.agent.network.ClientStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Zentraler Manager für alle Bluetooth-Zubehörtypen – BLE + Classic.
 *
 * Features:
 * - Adaptives Scanning: SCAN_MODE_LOW_LATENCY für aktive Ortung, LOW_POWER für Hintergrund
 * - Multi-Filter: 3dxAgent (0x0059), Battery Service, Env Sensing, Heart Rate, Custom 3dx, iBeacon, Eddystone (0xFEAA)
 * - Automatische Registry-Integration (ClientRegistry)
 * - Health Monitoring via AccessoryHealthMonitor
 * - GATT-Verbindungen für Konfiguration & Sensor-Streaming
 * - Classic SPP für HC-05/Headsets/Presenter
 *
 * Nutzung in CT45P / Android:
 * ```
 * val manager = BluetoothAccessoryManager(context, clientRegistry)
 * manager.startScan(mode = BluetoothAccessoryManager.ScanMode.HIGH_ACCURACY)
 * manager.accessories.collect { list -> ... }
 * ```
 */
class BluetoothAccessoryManager(
    private val context: Context,
    private val clientRegistry: ClientRegistry? = null,
) {

    companion object {
        private const val TAG = "BTAccessoryManager"
        private const val MAX_DEVICES = 150
        private const val EXPIRY_MS = 60_000L
    }

    enum class ScanMode {
        LOW_POWER,        // Hintergrund, seltene Updates
        BALANCED,         // Standard
        HIGH_ACCURACY,    // Aktive Ortung, schneller Scan
        OFFLINE_TRACKING, // Nur Tokens + Asset-Tags, Aggressiv-Scan
    }

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothPermissions.getBluetoothAdapter(context)
    private val scanner = bluetoothAdapter?.bluetoothLeScanner

    private val gattManager = GattServiceManager(context)
    private val bondingManager = AccessoryBondingManager(context)
    private val classicManager = BluetoothClassicManager(context)
    private val healthMonitor = AccessoryHealthMonitor(clientRegistry)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // State
    private val accessoryMap = ConcurrentHashMap<String, BluetoothAccessory>()
    private val _accessories = MutableStateFlow<List<BluetoothAccessory>>(emptyList())
    val accessories: StateFlow<List<BluetoothAccessory>> = _accessories.asStateFlow()

    private val _events = MutableSharedFlow<AccessoryEvent>(extraBufferCapacity = 100)
    val events: SharedFlow<AccessoryEvent> = _events.asSharedFlow()

    private var currentScanMode: ScanMode = ScanMode.BALANCED
    private var isScanning = false

    sealed class AccessoryEvent {
        data class Discovered(val accessory: BluetoothAccessory) : AccessoryEvent()
        data class Updated(val accessory: BluetoothAccessory) : AccessoryEvent()
        data class Lost(val mac: String) : AccessoryEvent()
        data class Connected(val mac: String) : AccessoryEvent()
        data class Disconnected(val mac: String) : AccessoryEvent()
        data class SosTriggered(val accessory: BluetoothAccessory) : AccessoryEvent()
        data class ButtonPressed(val accessory: BluetoothAccessory) : AccessoryEvent()
        data class Gatched(val mac: String, val data: Map<String, Any?>) : AccessoryEvent()
    }

    init {
        // GATT Events forwarden
        scope.launch {
            gattManager.dataEvents.collect { gattEvent ->
                val existing = accessoryMap[gattEvent.mac]
                if (existing != null) {
                    val updated = applyGattData(existing, gattEvent)
                    accessoryMap[gattEvent.mac] = updated
                    refreshStateFlow()
                    _events.emit(AccessoryEvent.Gatched(gattEvent.mac, gattEvent.payload))
                    if (updated.batteryLevel < 20) _events.emit(AccessoryEvent.Updated(updated))
                }
            }
        }
        // Bonding Events
        scope.launch {
            bondingManager.events.collect { ev ->
                Log.i(TAG, "Bonding event: $ev")
            }
        }
        // Classic SPP
        scope.launch {
            classicManager.classicEvents.collect { ev ->
                when (ev) {
                    is BluetoothClassicManager.ClassicEvent.DataReceived -> {
                        val existing = accessoryMap[ev.mac]
                        if (existing != null) {
                            // z.B. Button-Befehle als String "BTN:1"
                            if (ev.asString.startsWith("BTN") || ev.asString.contains("SOS")) {
                                val updated = existing.copy(flags = existing.flags or AccessoryFlags.SOS)
                                accessoryMap[ev.mac] = updated
                                _events.emit(AccessoryEvent.SosTriggered(updated))
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
        healthMonitor.startPeriodicCleanup()
    }

    /** Scan-Filter für alle unterstützten Services / Manufacturer IDs */
    private fun buildScanFilters(): List<ScanFilter> {
        val filters = mutableListOf<ScanFilter>()

        // 3dxAgent Tokens – Company ID 0x0059
        // Android ScanFilter kann nur Manufacturer ID filtern, nicht Daten. Wir bauen leeren Filter für Nordic.
        filters += ScanFilter.Builder().setManufacturerData(ManufacturerIds.NORDIC_3DX, byteArrayOf(), byteArrayOf()).build()

        // iBeacon / Apple – Company 0x004C
        filters += ScanFilter.Builder().setManufacturerData(ManufacturerIds.APPLE_IBEACON, byteArrayOf(), byteArrayOf()).build()

        // Eddystone – Service UUID 0xFEAA
        filters += ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString("0000feaa-0000-1000-8000-00805f9b34fb")).build()

        // Standard Services: Battery, Env Sensing, HRM, Custom 3dxAgent
        listOf(GattUuids.BATTERY_SERVICE, GattUuids.ENV_SENSING, GattUuids.HEART_RATE_SERVICE, GattUuids.CUSTOM_3DX_SERVICE)
            .forEach { svc ->
                filters += ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(svc)).build()
            }

        // Für HIGH_ACCURACY + OFFLINE_TRACKING zusätzlich leeren Filter (alle Geräte, dann Parser filtert)
        if (currentScanMode == ScanMode.HIGH_ACCURACY || currentScanMode == ScanMode.OFFLINE_TRACKING) {
            filters += ScanFilter.Builder().build() // Matches everything
        }

        return filters
    }

    private fun buildScanSettings(): ScanSettings {
        val mode = when (currentScanMode) {
            ScanMode.LOW_POWER -> ScanSettings.SCAN_MODE_LOW_POWER
            ScanMode.BALANCED -> ScanSettings.SCAN_MODE_BALANCED
            ScanMode.HIGH_ACCURACY, ScanMode.OFFLINE_TRACKING -> ScanSettings.SCAN_MODE_LOW_LATENCY
        }
        return ScanSettings.Builder()
            .setScanMode(mode)
            .setReportDelay(0L)
            .setMatchMode(
                if (currentScanMode == ScanMode.HIGH_ACCURACY) ScanSettings.MATCH_MODE_AGGRESSIVE
                else ScanSettings.MATCH_MODE_STICKY
            )
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .build()
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { handleScanResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "BLE Scan failed: $errorCode")
            isScanning = false
            // Auto-Retry
            scope.launch {
                delay(2000)
                if (currentScanMode != ScanMode.LOW_POWER) startScan(currentScanMode)
            }
        }
    }

    private fun handleScanResult(result: ScanResult) {
        try {
            val parsed = BleAdvertisementParser.parse(result) ?: return
            val mac = parsed.mac
            var accessory = parsed.accessory

            // Filter für OFFLINE_TRACKING: nur Tokens + Asset-Tags
            if (currentScanMode == ScanMode.OFFLINE_TRACKING &&
                parsed.accessoryType !in setOf(
                    BluetoothAccessoryType.TOKEN_CLASSIC,
                    BluetoothAccessoryType.TOKEN_PRO,
                    BluetoothAccessoryType.ASSET_TAG,
                )
            ) return

            // Merge mit bestehendem (bewahre GATT Daten, wenn vorhanden)
            val existing = accessoryMap[mac]
            if (existing != null) {
                accessory = existing.copy(
                    rssi = accessory.rssi,
                    lastSeenMs = System.currentTimeMillis(),
                    txPower = accessory.txPower ?: existing.txPower,
                    // Bewegungsdaten aus aktuellem Advertisement wichtiger als GATT
                    accelX = if (accessory.accelX != 0f) accessory.accelX else existing.accelX,
                    accelY = if (accessory.accelY != 0f) accessory.accelY else existing.accelY,
                    accelZ = if (accessory.accelZ != 0f) accessory.accelZ else existing.accelZ,
                    batteryLevel = accessory.batteryLevel,
                    flags = accessory.flags,
                    name = accessory.name.takeIf { it != mac } ?: existing.name,
                    temperatureC = accessory.temperatureC ?: existing.temperatureC,
                    humidityPct = accessory.humidityPct ?: existing.humidityPct,
                    pressureHpa = accessory.pressureHpa ?: existing.pressureHpa,
                    heartRateBpm = accessory.heartRateBpm ?: existing.heartRateBpm,
                    type = if (existing.type != BluetoothAccessoryType.GENERIC_BLE) existing.type else accessory.type,
                ).apply {
                    updateDistanceEstimate()
                }
                val isNewSOS = (accessory.flags and AccessoryFlags.SOS) != 0 && (existing.flags and AccessoryFlags.SOS) == 0
                val isNewButton = (accessory.flags and AccessoryFlags.BUTTON_PRESSED) != 0 && (existing.flags and AccessoryFlags.BUTTON_PRESSED) == 0
                accessoryMap[mac] = accessory
                refreshStateFlow()

                scope.launch {
                    if (isNewSOS) _events.emit(AccessoryEvent.SosTriggered(accessory))
                    else if (isNewButton) _events.emit(AccessoryEvent.ButtonPressed(accessory))
                    else _events.emit(AccessoryEvent.Updated(accessory))
                }
            } else {
                // Neue Entdeckung
                if (accessoryMap.size >= MAX_DEVICES) {
                    // Entferne ältestes
                    val oldest = accessoryMap.minByOrNull { it.value.lastSeenMs }?.key
                    oldest?.let { accessoryMap.remove(it) }
                }
                accessoryMap[mac] = accessory
                refreshStateFlow()

                // Auto-Registrierung in ClientRegistry
                try {
                    val apiKey = bondingManager.generateApiKey(mac, accessory.type)
                    clientRegistry?.register(accessory.toClientRegistration(apiKey))
                } catch (_: Exception) {}

                scope.launch { _events.emit(AccessoryEvent.Discovered(accessory)) }
                Log.i(TAG, "Neues Zubehör entdeckt: ${accessory.name} ${accessory.type} RSSI=${accessory.rssi}")
            }

            // Health
            healthMonitor.updateAccessories(accessoryMap.values.toList())

            // Optional: ClientRegistry Status update
            try {
                clientRegistry?.updateStatus(mac, ClientStatus.ONLINE)
            } catch (_: Exception) {}

        } catch (e: Exception) {
            Log.w(TAG, "Scan parse Fehler: ${e.message}")
        }
    }

    private fun applyGattData(acc: BluetoothAccessory, ev: GattServiceManager.GattDataEvent): BluetoothAccessory {
        var updated = acc
        when (ev.type) {
            "battery" -> {
                val level = ev.payload["battery"] as? Int
                if (level != null) updated = updated.copy(batteryLevel = level)
            }
            "firmware" -> {
                updated = updated.copy(firmwareVersion = ev.payload["firmware_version"] as? String)
            }
            "environment" -> {
                (ev.payload["temperature_c"] as? Number)?.let { updated = updated.copy(temperatureC = it.toFloat()) }
                (ev.payload["humidity_pct"] as? Number)?.let { updated = updated.copy(humidityPct = it.toFloat()) }
                (ev.payload["pressure_hpa"] as? Number)?.let { updated = updated.copy(pressureHpa = it.toFloat()) }
                (ev.payload["air_quality_ppm"] as? Number)?.let { updated = updated.copy(airQualityPpm = it.toFloat()) }
            }
            "wearable" -> {
                (ev.payload["heart_rate_bpm"] as? Int)?.let { updated = updated.copy(heartRateBpm = it) }
                (ev.payload["steps"] as? Int)?.let { updated = updated.copy(steps = it) }
            }
            "custom" -> {
                // Mapping bereits in GattServiceManager.parseCustomDataChar
                (ev.payload["accel_x"] as? Number)?.let { updated = updated.copy(accelX = it.toFloat()) }
                (ev.payload["accel_y"] as? Number)?.let { updated = updated.copy(accelY = it.toFloat()) }
                (ev.payload["accel_z"] as? Number)?.let { updated = updated.copy(accelZ = it.toFloat()) }
                (ev.payload["temperature_c"] as? Number)?.let { updated = updated.copy(temperatureC = it.toFloat()) }
                (ev.payload["humidity_pct"] as? Number)?.let { updated = updated.copy(humidityPct = it.toFloat()) }
                (ev.payload["button_state"] as? Int)?.let { updated = updated.copy(buttonState = it) }
            }
            "connected" -> updated = updated.copy(isConnected = true)
            "disconnected" -> updated = updated.copy(isConnected = false)
        }
        return updated.apply { updateDistanceEstimate() }
    }

    private fun refreshStateFlow() {
        _accessories.value = accessoryMap.values.sortedByDescending { it.rssi }.toList()
    }

    @SuppressLint("MissingPermission")
    fun startScan(mode: ScanMode = ScanMode.BALANCED) {
        if (!BluetoothPermissions.hasAllPermissions(context)) {
            Log.w(TAG, "Berechtigungen fehlen – Scan abgebrochen")
            return
        }
        if (!BluetoothPermissions.isBluetoothEnabled(context)) {
            Log.w(TAG, "Bluetooth deaktiviert – Scan abgebrochen")
            return
        }
        currentScanMode = mode
        val scanner = this.scanner ?: run {
            Log.w(TAG, "Scanner null")
            return
        }
        try {
            if (isScanning) scanner.stopScan(scanCallback)
        } catch (_: Exception) {}

        try {
            val filters = buildScanFilters()
            val settings = buildScanSettings()
            scanner.startScan(filters, settings, scanCallback)
            isScanning = true
            Log.i(TAG, "BLE Scan gestartet Modus=$mode Filter=${filters.size}")

            // Classic Discovery parallel für Headsets/Remotes wenn HIGH_ACCURACY
            if (mode == ScanMode.HIGH_ACCURACY) {
                classicManager.startDiscovery()
            }

            // Expiry-Cleaner
            scope.launch {
                while (isScanning) {
                    delay(10_000)
                    val now = System.currentTimeMillis()
                    val expired = accessoryMap.filter { now - it.value.lastSeenMs > EXPIRY_MS }.keys.toList()
                    expired.forEach { mac ->
                        accessoryMap.remove(mac)
                        scope.launch { _events.emit(AccessoryEvent.Lost(mac)) }
                        try { clientRegistry?.updateStatus(mac, ClientStatus.OFFLINE) } catch (_: Exception) {}
                    }
                    if (expired.isNotEmpty()) refreshStateFlow()
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Berechtigungsfehler: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Scan start Fehler: ${e.message}", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!isScanning) return
        isScanning = false
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
        try { classicManager.cancelDiscovery() } catch (_: Exception) {}
        Log.i(TAG, "BLE Scan gestoppt")
    }

    fun getAccessory(mac: String): BluetoothAccessory? = accessoryMap[mac]

    fun getAccessoriesByType(type: BluetoothAccessoryType): List<BluetoothAccessory> =
        accessoryMap.values.filter { it.type == type }

    fun getAllAccessories(): List<BluetoothAccessory> = accessoryMap.values.toList()

    @SuppressLint("MissingPermission")
    fun connect(mac: String): Boolean {
        val acc = accessoryMap[mac] ?: return false
        val adapter = bluetoothAdapter ?: return false
        val device = try { adapter.getRemoteDevice(mac) } catch (_: Exception) { null } ?: return false

        return when (acc.type) {
            BluetoothAccessoryType.CLASSIC_SPP, BluetoothAccessoryType.HEADSET,
            BluetoothAccessoryType.HID, BluetoothAccessoryType.GENERIC_CLASSIC -> {
                classicManager.connectSpp(device)
            }
            else -> {
                gattManager.connect(device) != null
            }
        }
    }

    fun disconnect(mac: String) {
        gattManager.disconnect(mac)
        classicManager.disconnect(mac)
        accessoryMap[mac]?.let {
            accessoryMap[mac] = it.copy(isConnected = false)
            refreshStateFlow()
        }
    }

    fun pair(mac: String): Boolean {
        val adapter = bluetoothAdapter ?: return false
        val device = try { adapter.getRemoteDevice(mac) } catch (_: Exception) { return false }
        return bondingManager.bondDevice(device)
    }

    fun unpair(mac: String): Boolean {
        val adapter = bluetoothAdapter ?: return false
        val device = try { adapter.getRemoteDevice(mac) } catch (_: Exception) { return false }
        return bondingManager.removeBond(device)
    }

    fun sendConfig(mac: String, jsonConfig: String): Boolean = gattManager.writeConfig(mac, jsonConfig)

    fun sendCommand(mac: String, command: ByteArray): Boolean = gattManager.sendCommand(mac, command)

    fun getHealthMonitor(): AccessoryHealthMonitor = healthMonitor

    fun getBondingManager(): AccessoryBondingManager = bondingManager

    fun getGattManager(): GattServiceManager = gattManager

    fun getClassicManager(): BluetoothClassicManager = classicManager

    fun cleanup() {
        stopScan()
        gattManager.disconnectAll()
        classicManager.disconnectAll()
        bondingManager.unregister()
    }
}
