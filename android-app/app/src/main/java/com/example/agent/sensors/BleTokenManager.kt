package com.example.agent.sensors

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import android.util.Log
import com.example.agent.bluetooth.BluetoothAccessory
import com.example.agent.bluetooth.BluetoothAccessoryManager
import com.example.agent.bluetooth.BluetoothAccessoryType
import com.example.agent.network.ClientRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/** Scans versioned nRF52840 token advertisements (company ID 0x0059). */
class BleTokenManager(private val context: Context) {

    /** Zubehör-Registry (BLE-Tokens, Sensor-Tags, Wearables). */
    val bluetoothAccessoryManager by lazy { BluetoothAccessoryManager(context) }
    private val accessoryManager get() = bluetoothAccessoryManager

    companion object {
        private const val TAG = "BleTokenManager"
        private const val COMPANY_ID = 0x0059
    }

    data class TokenData(
        val mac: String,
        val rssi: Int,
        val accelX: Float,
        val accelY: Float,
        val accelZ: Float,
        val battery: Int?,
        val sequence: Int,
        val protocolVersion: Int,
        val imuValid: Boolean,
    )

    private val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
    private val scanner get() = bluetoothAdapter?.bluetoothLeScanner
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var scanning = false

    private val _tokenUpdates = MutableSharedFlow<TokenData>(extraBufferCapacity = 50)
    val tokenUpdates: SharedFlow<TokenData> = _tokenUpdates.asSharedFlow()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord ?: return
            val payload = record.getManufacturerSpecificData(COMPANY_ID) ?: return
            val frame = BleTokenProtocol.decode(payload) ?: run {
                Log.w(TAG, "Rejected malformed/unsupported token payload (${payload.size} bytes)")
                return
            }

            scope.launch {
                _tokenUpdates.emit(
                    TokenData(
                        mac = result.device.address,
                        rssi = result.rssi,
                        accelX = frame.accelX,
                        accelY = frame.accelY,
                        accelZ = frame.accelZ,
                        battery = frame.batteryPercent,
                        sequence = frame.sequence,
                        protocolVersion = frame.version,
                        imuValid = frame.imuValid,
                    )
                )
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            Log.w(TAG, "BLE scan failed: $errorCode")
        }
    }

    @Synchronized
    fun startScan(): Boolean {
        if (scanning) return true
        if (!hasScanPermission()) {
            Log.w(TAG, "BLE scan permission missing; scan not started")
            return false
        }
        return try {
            val activeScanner = scanner ?: run {
                Log.w(TAG, "Bluetooth is disabled or BLE scanner unavailable")
                return false
            }
            val filter = ScanFilter.Builder()
                .setManufacturerData(
                    COMPANY_ID,
                    byteArrayOf(BleTokenProtocol.VERSION.toByte()),
                    byteArrayOf(0xff.toByte()),
                )
                .build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            activeScanner.startScan(listOf(filter), settings, scanCallback)
            scanning = true
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "BLE permission rejected", e)
            false
        }
    }

    @Synchronized
    fun stopScan() {
        if (!scanning) return
        scanning = false
        if (!hasScanPermission()) return
        try {
            scanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not stop BLE scan", e)
        }
    }

    fun close() {
        stopScan()
        scope.cancel()
    }

    private fun hasScanPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            android.Manifest.permission.BLUETOOTH_SCAN
        } else {
            android.Manifest.permission.ACCESS_FINE_LOCATION
        }
        return ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
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

