package com.example.agent.ui.alarm

import com.example.agent.network.models.AlarmEvent
import com.example.agent.network.models.AlarmRuntime

/** In-memory presentation state; the gateway remains the alarm authority. */
data class AlarmUiState(
    val connected: Boolean = false,
    val latestEvent: AlarmEvent? = null,
    val runtime: AlarmRuntime? = null,
    val commandInProgress: Boolean = false,
    val errorMessage: String? = null,
) {
    val latestRevision: Long
        get() = maxOf(latestEvent?.stateRevision ?: 0L, runtime?.stateRevision ?: 0L)
}
