package com.example.agent.sensors

import android.content.Context
import android.util.Log
import com.example.agent.tactical.MedicalMonitoringService

/**
 * Factory for real medical drivers (Polar H10, Garmin, UART medical dongle).
 * Uses real Android BLE GATT + USB OTG.
 * Falls back ONLY to a passive no-op RealMedicalMonitoringService when no real hardware is paired.
 * All real vitals must arrive via real sensors → updateVitalData().
 */
object MedicalDriverFactory {

    fun create(
        context: Context,
        onVitalUpdate: (heartRate: Int, hrv: Float, spo2: Int, temp: Float) -> Unit
    ): MedicalMonitoringService {

        // 1. Try Polar H10 (very common)
        try {
            return PolarH10Manager(context, onVitalUpdate)
        } catch (e: Exception) {
            Log.d("MedicalDriverFactory", "PolarH10 not available: ${e.message}")
        }

        // 2. Try Garmin (Forerunner, Fenix, HRM-Pro etc.)
        try {
            return GarminManager(context, onVitalUpdate)
        } catch (e: Exception) {
            Log.d("MedicalDriverFactory", "Garmin not available: ${e.message}")
        }

        // 3. Try UART medical dongle / custom medical sensor
        try {
            return UartMedicalDriver(context, onVitalUpdate)
        } catch (e: Exception) {
            Log.d("MedicalDriverFactory", "UartMedicalDriver not available: ${e.message}")
        }

        // 4. Fallback: stub
        Log.w("MedicalDriverFactory", "No real medical driver found — using stub. Real data must come via updateVitalData().")
        return com.example.agent.tactical.RealMedicalMonitoringService()
    }
}
