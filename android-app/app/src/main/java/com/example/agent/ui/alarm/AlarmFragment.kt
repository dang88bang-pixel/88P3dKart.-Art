package com.example.agent.ui.alarm

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.agent.MainActivity
import com.example.agent.R
import com.example.agent.network.models.AlarmEvidence
import kotlinx.coroutines.launch
import java.text.NumberFormat

/** Presents gateway-authoritative alarm state and operator command intents. */
class AlarmFragment : Fragment() {
    private lateinit var connection: TextView
    private lateinit var authority: TextView
    private lateinit var condition: TextView
    private lateinit var attention: TextView
    private lateinit var evidence: TextView
    private lateinit var event: TextView
    private lateinit var error: TextView
    private lateinit var acknowledge: Button
    private lateinit var snooze: Button

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_alarm, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        connection = view.findViewById(R.id.tv_alarm_connection)
        authority = view.findViewById(R.id.tv_alarm_authority)
        condition = view.findViewById(R.id.tv_alarm_condition)
        attention = view.findViewById(R.id.tv_alarm_attention)
        evidence = view.findViewById(R.id.tv_alarm_evidence)
        event = view.findViewById(R.id.tv_alarm_event)
        error = view.findViewById(R.id.tv_alarm_error)
        acknowledge = view.findViewById(R.id.btn_alarm_acknowledge)
        snooze = view.findViewById(R.id.btn_alarm_snooze)

        val host = requireActivity() as MainActivity
        acknowledge.setOnClickListener { host.acknowledgeDisplayedAlarm() }
        snooze.setOnClickListener { host.snoozeDisplayedAlarm() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                host.alarmUiState.collect(::render)
            }
        }
    }

    private fun render(state: AlarmUiState) {
        connection.text = getString(
            if (state.connected) R.string.alarm_connected_authoritative
            else R.string.alarm_disconnected_degraded,
        )

        val runtime = state.runtime
        val latestEvent = state.latestEvent
        val displayedCondition = runtime?.condition ?: latestEvent?.newState?.condition
        val displayedAttention = runtime?.attention ?: latestEvent?.newState?.attention
        val displayedAuthority = runtime?.authority ?: latestEvent?.authority
        val displayedAuthorityId = runtime?.authorityId ?: latestEvent?.authorityId
        val displayedSeverity = runtime?.severity ?: latestEvent?.severity

        authority.text = if (displayedAuthority == null) {
            getString(R.string.alarm_waiting)
        } else {
            getString(
                R.string.alarm_authority_format,
                displayedAuthority,
                displayedAuthorityId.orEmpty(),
                displayedSeverity.orEmpty(),
                state.latestRevision,
            )
        }
        condition.text = displayedCondition ?: "—"
        attention.text = getString(
            R.string.alarm_attention_format,
            displayedAttention ?: "—",
        )
        evidence.text = formatEvidence(runtime?.lastEvidence ?: latestEvent?.evidence)
        event.text = latestEvent?.let {
            getString(R.string.alarm_event_format, it.eventType, it.reasonCode, it.occurredAt)
        } ?: getString(R.string.alarm_no_event)

        error.text = state.errorMessage
        error.isVisible = state.errorMessage != null

        val commandable = displayedCondition in COMMANDABLE_CONDITIONS
        acknowledge.isEnabled = commandable &&
            displayedAttention != "ACKNOWLEDGED" &&
            !state.commandInProgress
        snooze.isEnabled = commandable && !state.commandInProgress
    }

    private fun formatEvidence(value: AlarmEvidence?): String {
        if (value == null) return getString(R.string.alarm_no_evidence)
        val formatter = NumberFormat.getNumberInstance().apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }
        val distance = value.valueMeters?.let { formatter.format(it) } ?: "—"
        val confidence = value.confidence?.let { formatter.format(it) } ?: "—"
        val flags = value.qualityFlags.ifEmpty { listOf("—") }.joinToString(", ")
        return getString(
            R.string.alarm_evidence_format,
            value.estimateStatus,
            distance,
            confidence,
            value.ageMs?.toString() ?: "—",
            flags,
        )
    }

    companion object {
        private val COMMANDABLE_CONDITIONS = setOf("ACTIVE", "DATA_LOSS", "PENDING_CLEAR")
    }
}
