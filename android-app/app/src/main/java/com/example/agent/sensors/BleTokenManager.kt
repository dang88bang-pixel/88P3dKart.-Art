package com.example.agent.sensors

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * BLE-Token-Scanner für nRF52840-Token (Company ID 0x0059).
 * Extrahiert IMU-Beschleunigungswerte, RSSI und Batteriestand.
 */
class BleTokenManager(private val context: Context) {

    companion object {
        private const val COMPANY_ID = 0x0059
    }

    data class TokenData(
        val mac: String,
        val rssi: Int,
        val accelX: Float,
        val accelY: Float,
        val accelZ: Float,
        val battery: Int,
    )

    private val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
    private val scanner = bluetoothAdapter.bluetoothLeScanner
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _tokenUpdates = MutableSharedFlow<TokenData>(extraBufferCapacity = 50)
    val tokenUpdates: SharedFlow<TokenData> = _tokenUpdates.asSharedFlow()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord ?: return
            val data = record.getManufacturerSpecificData(COMPANY_ID) ?: return
            if (data.size < 9) return

            val accelX = (data[2].toShort() / 1000f)
            val accelY = (data[4].toShort() / 1000f)
            val accelZ = (data[6].toShort() / 1000f)
            val battery = data[8].toInt() and 0xFF

            scope.launch {
                _tokenUpdates.emit(
                    TokenData(result.device.address, result.rssi, accelX, accelY, accelZ, battery)
                )
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w("BleTokenManager", "Scan fehlgeschlagen: $errorCode")
        }
    }

    fun startScan() {
        val fineLocation = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fineLocation) {
            Log.w("BleTokenManager", "ACCESS_FINE_LOCATION fehlt — Scan übersprungen")
            return
        }
        try {
            scanner.startScan(scanCallback)
        } catch (e: SecurityException) {
            Log.e("BleTokenManager", "Berechtigung fehlt: ${e.message}")
        }
    }

    fun stopScan() {
        try {
            scanner.stopScan(scanCallback)
        } catch (_: SecurityException) {}
    }
}
