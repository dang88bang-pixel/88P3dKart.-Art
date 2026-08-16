package com.example.agent.health

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceThermalPolicyTest {
    @Test
    fun severeAndHigherStatusesPauseLocalWorkload() {
        listOf(
            DeviceThermalStatus.SEVERE,
            DeviceThermalStatus.CRITICAL,
            DeviceThermalStatus.EMERGENCY,
            DeviceThermalStatus.SHUTDOWN,
        ).forEach { status ->
            assertEquals(
                WorkloadMode.PAUSED,
                DeviceThermalPolicy.workloadMode(status, WorkloadMode.NORMAL),
            )
        }
    }

    @Test
    fun moderateStatusReducesWorkload() {
        assertEquals(
            WorkloadMode.REDUCED,
            DeviceThermalPolicy.workloadMode(
                DeviceThermalStatus.MODERATE,
                WorkloadMode.NORMAL,
            ),
        )
    }

    @Test
    fun pausedModeRecoversOnlyAfterStatusFallsBelowModerate() {
        assertEquals(
            WorkloadMode.PAUSED,
            DeviceThermalPolicy.workloadMode(
                DeviceThermalStatus.MODERATE,
                WorkloadMode.PAUSED,
            ),
        )
        assertEquals(
            WorkloadMode.NORMAL,
            DeviceThermalPolicy.workloadMode(
                DeviceThermalStatus.LIGHT,
                WorkloadMode.PAUSED,
            ),
        )
    }

    @Test
    fun unknownStatusUsesConservativeReducedMode() {
        assertEquals(
            WorkloadMode.REDUCED,
            DeviceThermalPolicy.workloadMode(
                DeviceThermalStatus.UNKNOWN,
                WorkloadMode.NORMAL,
            ),
        )
    }
}
