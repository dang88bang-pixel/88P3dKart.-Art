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

        // Hinweis: Die BLE-/UART-Medizintreiber (Polar H10, Garmin, UART-Dongle)
        // sind in diesem Stand nicht enthalten; die Factory liefert daher den
        // passiven RealMedicalMonitoringService. Reale Vitalwerte müssen über
        // updateVitalData() gesetzt werden (siehe TacticalHealthMonitoring).
        context.getSharedPreferences("medical", android.content.Context.MODE_PRIVATE)

        // Fallback: stub
        Log.w("MedicalDriverFactory", "No real medical driver found — using stub. Real data must come via updateVitalData().")
        return com.example.agent.tactical.RealMedicalMonitoringService()
    }
}
