package com.example.agent.sensors

import com.example.agent.tactical.MedicalMonitoringService
import com.example.agent.tactical.RealMedicalMonitoringService
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vertragstest der MedicalDriverFactory (JVM).
 *
 * Die BLE-/UART-Medizintreiber (Polar H10, Garmin, UART-Dongle) sind in diesem
 * Stand dokumentiert NICHT enthalten — die Factory liefert daher immer den
 * passiven RealMedicalMonitoringService:
 *  - nie null,
 *  - erzeugt NIE synthetische Vitalwerte (Daten nur via updateVitalData()).
 */
class MedicalDriverFactoryTest {

    @Test
    fun create_returnsNonNullService_always() {
        val service = MedicalDriverFactory.create { _, _, _, _ -> }
        assertNotNull("MedicalDriverFactory must never return null", service)
        assertTrue("Service must implement MedicalMonitoringService", service is MedicalMonitoringService)
    }

    @Test
    fun fallback_isPassive_andDoesNotGenerateData() {
        val fallback = RealMedicalMonitoringService()
        fallback.startMonitoring { _, _, _, _ ->
            throw AssertionError(
                "Passiver Fallback darf niemals Daten emittieren — reale Werte nur via updateVitalData()."
            )
        }
        Thread.sleep(50)
        fallback.stopMonitoring()
        assertTrue(true)
    }
}
