package com.example.agent.network.models

import com.google.gson.annotations.SerializedName
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Gateway-authoritative alarm transition projected to the CT45P control plane. */
@Serializable
data class AlarmEvent(
    @SerialName("schema_version") @SerializedName("schema_version") val schemaVersion: String,
    @SerialName("event_id") @SerializedName("event_id") val eventId: String,
    @SerialName("correlation_id") @SerializedName("correlation_id") val correlationId: String,
    val authority: String,
    @SerialName("authority_id") @SerializedName("authority_id") val authorityId: String,
    @SerialName("state_revision") @SerializedName("state_revision") val stateRevision: Long,
    @SerialName("policy_id") @SerializedName("policy_id") val policyId: String,
    @SerialName("policy_revision") @SerializedName("policy_revision") val policyRevision: Long,
    @SerialName("asset_id") @SerializedName("asset_id") val assetId: String,
    val severity: String,
    @SerialName("event_type") @SerializedName("event_type") val eventType: String,
    @SerialName("reason_code") @SerializedName("reason_code") val reasonCode: String,
    @SerialName("occurred_at") @SerializedName("occurred_at") val occurredAt: String,
    @SerialName("new_state") @SerializedName("new_state") val newState: AlarmEventState,
    val evidence: AlarmEvidence?,
    val actor: AlarmActor?,
)

@Serializable
data class AlarmEventState(
    val condition: String,
    val attention: String,
    @SerialName("condition_since") @SerializedName("condition_since") val conditionSince: String,
    @SerialName("attention_since") @SerializedName("attention_since") val attentionSince: String,
    @SerialName("snoozed_until") @SerializedName("snoozed_until") val snoozedUntil: String?,
)

@Serializable
data class AlarmActor(
    @SerialName("actor_id") @SerializedName("actor_id") val actorId: String,
    @SerialName("session_id") @SerializedName("session_id") val sessionId: String,
    val action: String,
)

@Serializable
data class AlarmEvidence(
    @SerialName("estimate_status") @SerializedName("estimate_status") val estimateStatus: String,
    val method: String?,
    @SerialName("value_m") @SerializedName("value_m") val valueMeters: Double?,
    val confidence: Double?,
    @SerialName("lower_95_m") @SerializedName("lower_95_m") val lower95Meters: Double?,
    @SerialName("upper_95_m") @SerializedName("upper_95_m") val upper95Meters: Double?,
    @SerialName("observed_at") @SerializedName("observed_at") val observedAt: String?,
    @SerialName("age_ms") @SerializedName("age_ms") val ageMs: Long?,
    @SerialName("calibration_id") @SerializedName("calibration_id") val calibrationId: String?,
    @SerialName("quality_flags") @SerializedName("quality_flags") val qualityFlags: List<String>,
)

data class AlarmRuntime(
    @SerializedName("schema_version") val schemaVersion: String,
    val authority: String,
    @SerializedName("authority_id") val authorityId: String,
    @SerializedName("state_revision") val stateRevision: Long,
    @SerializedName("policy_id") val policyId: String,
    @SerializedName("policy_revision") val policyRevision: Long,
    @SerializedName("asset_id") val assetId: String,
    val severity: String,
    val condition: String,
    val attention: String,
    @SerializedName("condition_since") val conditionSince: String,
    @SerializedName("attention_since") val attentionSince: String,
    @SerializedName("snoozed_until") val snoozedUntil: String?,
    @SerializedName("last_evidence") val lastEvidence: AlarmEvidence?,
    @SerializedName("last_event_id") val lastEventId: String?,
)

data class AlarmHistoryResponse(val events: List<AlarmEvent>)
data class AlarmSnoozeRequest(@SerializedName("duration_ms") val durationMs: Long)
