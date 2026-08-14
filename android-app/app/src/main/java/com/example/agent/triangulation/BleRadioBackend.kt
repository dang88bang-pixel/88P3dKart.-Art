package com.example.agent.triangulation

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log

/**
 * Abstraktion der BLE-Funkkanäle des CT45P (docs/TRIANGULATION.md §2.5).
 *
 * Das Gerät besitzt Bluetooth 5.1 plus eine optionale zweite BLE-Schnittstelle:
 * - Primärkanal: Gerätesteuerung/Datenübertragung (bestehender
 *   `BleTokenManager`),
 * - Sekundärkanal: dediziertes Scanning zur Triangulation — paralleles
 *   Empfangen von RSSI-Signalen ohne die aktive Verbindung zu unterbrechen.
 *
 * `StandardAndroidBleBackend` nutzt mehrere parallele `ScanCallback`-Instanzen
 * der Standard-API (eine Funkhardware, parallele Scanner). Ob die zweite
 * Hardware-Funkschnittstelle über das Honeywell Mobility SDK getrennt
 * ansprechbar ist, ist geräteabhängig — dafür ist ein eigener Backend-Typ
 * vorgesehen (Status ⏳, siehe Roadmap).
 */
interface BleRadioBackend {

    val available: Boolean

    /** @return true, wenn der Scan gestartet wurde */
    fun startScan(callback: ScanCallback, settings: ScanSettings, filter: ScanFilter?): Boolean

    fun stopScan(callback: ScanCallback)
}

/**
 * Standard-Android-Implementierung über `BluetoothLeScanner`.
 * Android erlaubt mehrere parallele Scan-Callbacks (mehrere Scanner auf einer
 * Funkhardware); Berechtigungsfehler (BLUETOOTH_SCAN) werden abgefangen.
 */
class StandardAndroidBleBackend(context: Context) : BleRadioBackend {

    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val scanner = adapter?.bluetoothLeScanner

    override val available: Boolean
        get() = adapter != null && scanner != null

    override fun startScan(callback: ScanCallback, settings: ScanSettings, filter: ScanFilter?): Boolean {
        val s = scanner ?: return false
        return try {
            if (filter != null) {
                s.startScan(listOf(filter), settings, callback)
            } else {
                s.startScan(null, settings, callback)
            }
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "BLUETOOTH_SCAN fehlt: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "BLE-Scan-Fehler: ${e.message}")
            false
        }
    }

    override fun stopScan(callback: ScanCallback) {
        runCatching { scanner?.stopScan(callback) }
    }

    companion object {
        private const val TAG = "BleBackend"
    }
}
