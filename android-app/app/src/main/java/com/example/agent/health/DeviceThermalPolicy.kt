package com.example.agent.health

/** Android's OEM-defined, device-wide thermal status. It is not a CPU temperature. */
enum class DeviceThermalStatus {
    UNKNOWN,
    NONE,
    LIGHT,
    MODERATE,
    SEVERE,
    CRITICAL,
    EMERGENCY,
    SHUTDOWN,
}

enum class WorkloadMode {
    NORMAL,
    REDUCED,
    PAUSED,
}

data class DeviceHealthState(
    val thermalStatus: DeviceThermalStatus = DeviceThermalStatus.UNKNOWN,
    val batteryTemperatureC: Float? = null,
    val batteryPercent: Float? = null,
    val workloadMode: WorkloadMode = WorkloadMode.REDUCED,
)

/**
 * Maps platform thermal status to a local workload policy.
 *
 * Battery temperature remains separately labelled telemetry. It is deliberately not
 * interpreted as CPU/sensor temperature and does not drive this generic policy.
 */
object DeviceThermalPolicy {
    fun workloadMode(
        status: DeviceThermalStatus,
        previous: WorkloadMode,
    ): WorkloadMode = when {
        status >= DeviceThermalStatus.SEVERE -> WorkloadMode.PAUSED
        previous == WorkloadMode.PAUSED && status >= DeviceThermalStatus.MODERATE ->
            WorkloadMode.PAUSED
        status == DeviceThermalStatus.MODERATE -> WorkloadMode.REDUCED
        status == DeviceThermalStatus.UNKNOWN -> WorkloadMode.REDUCED
        else -> WorkloadMode.NORMAL
    }
}
