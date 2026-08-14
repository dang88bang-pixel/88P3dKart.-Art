package com.example.agent.resource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourcePoliciesTest {

    @Test
    fun `bewegungszustaende folgen den geschwindigkeitsschwellen`() {
        assertEquals(ResourcePolicies.MotionState.STATIONARY, ResourcePolicies.motionStateOf(0.0f))
        assertEquals(ResourcePolicies.MotionState.WALKING, ResourcePolicies.motionStateOf(0.9f))
        assertEquals(ResourcePolicies.MotionState.RUNNING, ResourcePolicies.motionStateOf(2.0f))
        assertEquals(ResourcePolicies.MotionState.VEHICLE, ResourcePolicies.motionStateOf(8.0f))
    }

    @Test
    fun `scan-raten skalieren mit batterie und temperatur`() {
        val fast = ResourcePolicies.computeScanRates(2.0f, 100, 25f)
        val slow = ResourcePolicies.computeScanRates(2.0f, 20, 50f)
        assertTrue("Akku/Temperatur drosseln nicht: ${fast.lidarRate} <= ${slow.lidarRate}",
            fast.lidarRate > slow.lidarRate)
        assertTrue(slow.lidarRate >= 1f)
        // Stillstand: minimale Basis-Raten
        val stationary = ResourcePolicies.computeScanRates(0.0f, 100, 25f)
        assertEquals(2f, stationary.lidarRate, 1e-3f)
        assertEquals(0.5f, stationary.meshRate, 1e-3f)
    }

    @Test
    fun `einsparungsberechnung liegt im intervall 0 bis 1`() {
        val rates = ResourcePolicies.computeScanRates(0.0f, 100, 25f)
        val savings = ResourcePolicies.savings(rates)
        assertTrue("LiDAR-Einsparung zu gering: ${savings["lidar"]}", savings.getValue("lidar") > 0.5f)
        assertTrue(savings.getValue("total") in 0f..1f)
        // Keine Division durch Null an der Baseline
        val baselineSavings = ResourcePolicies.savings(ResourcePolicies.BASELINE_RATES)
        assertEquals(0f, baselineSavings.getValue("total"), 1e-6f)
    }

    @Test
    fun `energieprofil-schwellen entsprechen der spec`() {
        fun state(battery: Int, cpu: Float = 0.3f, temp: Float = 30f, charging: Boolean = false) =
            ResourcePolicies.ResourceState(cpu, 0.4f, battery, temp, charging)

        assertEquals(ResourcePolicies.PowerProfile.EMERGENCY,
            ResourcePolicies.determinePowerProfile(state(10)))
        assertEquals(ResourcePolicies.PowerProfile.POWER_SAVE,
            ResourcePolicies.determinePowerProfile(state(20)))
        assertEquals(ResourcePolicies.PowerProfile.POWER_SAVE,
            ResourcePolicies.determinePowerProfile(state(60, cpu = 0.8f)))
        assertEquals(ResourcePolicies.PowerProfile.POWER_SAVE,
            ResourcePolicies.determinePowerProfile(state(60, temp = 45f)))
        assertEquals(ResourcePolicies.PowerProfile.PERFORMANCE,
            ResourcePolicies.determinePowerProfile(state(60, cpu = 0.4f, charging = true)))
        assertEquals(ResourcePolicies.PowerProfile.BALANCED,
            ResourcePolicies.determinePowerProfile(state(60)))
    }

    @Test
    fun `profil-raten und qualitaet`() {
        assertEquals(ResourcePolicies.BASELINE_RATES,
            ResourcePolicies.scanRatesForProfile(ResourcePolicies.PowerProfile.PERFORMANCE))
        assertEquals(0f, ResourcePolicies.scanRatesForProfile(ResourcePolicies.PowerProfile.EMERGENCY).meshRate, 1e-6f)
        assertEquals(1f, ResourcePolicies.qualityForProfile(ResourcePolicies.PowerProfile.PERFORMANCE), 1e-6f)
        assertEquals(0.1f, ResourcePolicies.qualityForProfile(ResourcePolicies.PowerProfile.EMERGENCY), 1e-6f)
    }

    // ── ROI ──────────────────────────────────────────────────────

    @Test
    fun `roi-gewichtung mit linearem falloff`() {
        val roiMap = RoiWeightMap(maxRois = 2, minPriority = 0.3f)
        roiMap.add(RoiWeightMap.Roi(0f, 0f, 0f, 2f, 1f, RoiWeightMap.RoiType.PERSON))
        assertEquals(1f, roiMap.weightAt(0f, 0f, 0f), 1e-6f)
        assertEquals(0.5f, roiMap.weightAt(1f, 0f, 0f), 1e-6f)
        assertEquals(0.5f, roiMap.weightAt(10f, 0f, 0f), 1e-6f)
    }

    @Test
    fun `roi-kapazitaet behaelt die hoechsten prioritaeten`() {
        val roiMap = RoiWeightMap(maxRois = 2)
        roiMap.add(RoiWeightMap.Roi(0f, 0f, 0f, 1f, 1f, RoiWeightMap.RoiType.HAZARD))
        roiMap.add(RoiWeightMap.Roi(5f, 5f, 0f, 1f, 0.5f, RoiWeightMap.RoiType.EXIT))
        roiMap.add(RoiWeightMap.Roi(-5f, -5f, 0f, 1f, 0.9f, RoiWeightMap.RoiType.ENTRANCE))
        assertEquals(2, roiMap.size())
        assertEquals(0.9f, roiMap.weightAt(-5f, -5f, 0f), 1e-6f)
        assertEquals(0.5f, roiMap.weightAt(5f, 5f, 0f), 1e-6f) // verdrängt
    }

    @Test
    fun `roi ignoriert niedrige priorität und duplikate`() {
        val roiMap = RoiWeightMap(minPriority = 0.3f)
        roiMap.add(RoiWeightMap.Roi(0f, 0f, 0f, 1f, 0.2f, RoiWeightMap.RoiType.PERSON))
        assertEquals(0, roiMap.size())
        roiMap.add(RoiWeightMap.Roi(1f, 1f, 1f, 1f, 0.8f, RoiWeightMap.RoiType.COMMAND_POST))
        roiMap.add(RoiWeightMap.Roi(1f, 1f, 1f, 1f, 0.9f, RoiWeightMap.RoiType.COMMAND_POST))
        assertEquals(1, roiMap.size())
    }
}
