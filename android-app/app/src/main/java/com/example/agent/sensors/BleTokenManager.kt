package com.example.agent.sensors

import android.content.Context
import android.util.Log
import com.example.agent.bluetooth.BluetoothAccessory
import com.example.agent.bluetooth.BluetoothAccessoryManager
import com.example.agent.bluetooth.BluetoothAccessoryType
import com.example.agent.network.ClientRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * BLE-Token-Manager – jetzt Wrapper um den neuen BluetoothAccessoryManager.
 *
 * Backward-kompatibel: stellt weiterhin TokenData Flow bereit,
 * nutzt intern aber das erweiterte Bluetooth-Zubehör-Ökosystem.
 *
 * Unterstützt automatisch:
 * - TOKEN_CLASSIC (0x0059 legacy)
 * - TOKEN_PRO (V2)
 * - ASSET_TAG (iBeacon/Eddystone)
 * - Alle anderen Zubehörtypen über accessoryUpdates Flow
 */
class BleTokenManager(
    context: Context,
    clientRegistry: ClientRegistry? = null,
) {
    companion object {
        private const val TAG = "BleTokenManager"
    }

    data class TokenData(
        val mac: String,
        val rssi: Int,
        val accelX: Float,
        val accelY: Float,
        val accelZ: Float,
        val battery: Int,
        val type: String = "token",
        val temperature: Float? = null,
        val flags: Int = 0,
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val accessoryManager = BluetoothAccessoryManager(context, clientRegistry)

    private val _tokenUpdates = MutableSharedFlow<TokenData>(extraBufferCapacity = 50)
    val tokenUpdates: SharedFlow<TokenData> = _tokenUpdates.asSharedFlow()

    /** Vollständiger Zubehör-Stream (alle Typen) */
    private val _accessoryUpdates = MutableSharedFlow<BluetoothAccessory>(extraBufferCapacity = 100)
    val accessoryUpdates: SharedFlow<BluetoothAccessory> = _accessoryUpdates.asSharedFlow()

    /** Direkter Zugriff auf den neuen Manager für erweiterte Features */
    val bluetoothAccessoryManager: BluetoothAccessoryManager = accessoryManager

    init {
        scope.launch {
            accessoryManager.accessories.collect { list ->
                list.forEach { acc ->
                    // Token-Filter für Legacy Flow
                    if (acc.type in setOf(
                            BluetoothAccessoryType.TOKEN_CLASSIC,
                            BluetoothAccessoryType.TOKEN_PRO,
                            BluetoothAccessoryType.ASSET_TAG,
                        )
                    ) {
                        _tokenUpdates.emit(
                            TokenData(
                                mac = acc.macAddress,
                                rssi = acc.rssi,
                                accelX = acc.accelX,
                                accelY = acc.accelY,
                                accelZ = acc.accelZ,
                                battery = acc.batteryLevel,
                                type = acc.type.name,
                                temperature = acc.temperatureC,
                                flags = acc.flags,
                            )
                        )
                    }
                    _accessoryUpdates.emit(acc)
                }
            }
        }

        scope.launch {
            accessoryManager.events.collect { ev ->
                when (ev) {
                    is BluetoothAccessoryManager.AccessoryEvent.SosTriggered ->
                        Log.w(TAG, "SOS von ${ev.accessory.macAddress}")
                    is BluetoothAccessoryManager.AccessoryEvent.ButtonPressed ->
                        Log.i(TAG, "Button von ${ev.accessory.macAddress}")
                    else -> {}
                }
            }
        }
    }

    fun startScan() {
        Log.i(TAG, "Starte erweiterten BLE Scan via BluetoothAccessoryManager")
        accessoryManager.startScan(BluetoothAccessoryManager.ScanMode.BALANCED)
    }

    fun startHighAccuracyScan() {
        Log.i(TAG, "Starte High-Accuracy Scan (alle Zubehörtypen)")
        accessoryManager.startScan(BluetoothAccessoryManager.ScanMode.HIGH_ACCURACY)
    }

    fun startOfflineTrackingScan() {
        accessoryManager.startScan(BluetoothAccessoryManager.ScanMode.OFFLINE_TRACKING)
    }

    fun stopScan() {
        accessoryManager.stopScan()
    }

    /** Zugriff auf erkannt Zubehör */
    fun getAllAccessories(): List<BluetoothAccessory> = accessoryManager.getAllAccessories()
    fun getTokens(): List<BluetoothAccessory> = accessoryManager.getAccessoriesByType(BluetoothAccessoryType.TOKEN_PRO) +
        accessoryManager.getAccessoriesByType(BluetoothAccessoryType.TOKEN_CLASSIC)
    fun getSensorTags(): List<BluetoothAccessory> = accessoryManager.getAccessoriesByType(BluetoothAccessoryType.SENSOR_TAG)
    fun getWearables(): List<BluetoothAccessory> = accessoryManager.getAccessoriesByType(BluetoothAccessoryType.WEARABLE)
    fun getAssetTags(): List<BluetoothAccessory> = accessoryManager.getAccessoriesByType(BluetoothAccessoryType.ASSET_TAG)
    fun getAllTypes(): Map<BluetoothAccessoryType, List<BluetoothAccessory>> =
        BluetoothAccessoryType.values().associateWith { type -> accessoryManager.getAccessoriesByType(type) }.filter { it.value.isNotEmpty() }
}

