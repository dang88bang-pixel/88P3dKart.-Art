"""Authoritative alarm application service.

This layer is the only production adapter allowed to invoke the pure reducer. It
maps calibrated fusion evidence to monotonic time, serializes concurrent updates,
and atomically checkpoints runtime/events/outbox records through AlarmRepository.
"""
from __future__ import annotations

import hashlib
import json
import re
import threading
import time
import uuid
from dataclasses import asdict, replace
from datetime import datetime, timezone
from typing import Any, Callable, Optional

from alarm_engine import (
    AlarmPolicy,
    AlarmRuntime,
    Attention,
    Condition,
    EventType,
    Evidence,
    Reduction,
    Transition,
    acknowledge,
    evaluate,
    policy_updated,
    snooze,
)
from alarm_repository import (
    ActivePolicy,
    AlarmRepository,
    EventRecord,
    InputCursor,
    LoadedRuntime,
    PersistenceInvariantError,
    RevisionConflict,
)
from models import AlarmEvidenceRequest, AlarmPolicyRequest
from security import Principal

_IDENTIFIER = re.compile(r"^[A-Za-z0-9._:-]{1,160}$")


class AlarmNotFound(LookupError):
    pass


class AlarmConflict(RuntimeError):
    pass


class AlarmAuthorizationError(PermissionError):
    pass


class AlarmInputError(ValueError):
    pass


class AlarmService:
    """Synchronous, lock-serialized orchestration around the SQLite repository."""

    def __init__(
        self,
        repository: AlarmRepository,
        gateway_id: str,
        *,
        boot_id: Optional[str] = None,
        monotonic: Callable[[], float] = time.monotonic,
        utc_clock: Callable[[], float] = time.time,
    ):
        if not _IDENTIFIER.fullmatch(gateway_id):
            raise ValueError("gateway_id must be a bounded identifier")
        self.repository = repository
        self.gateway_id = gateway_id
        self.boot_id = boot_id or str(uuid.uuid4())
        uuid.UUID(self.boot_id)
        self._monotonic = monotonic
        self._utc_clock = utc_clock
        self._lock = threading.RLock()

    def store_policy(
        self,
        request: AlarmPolicyRequest,
        principal: Principal,
    ) -> dict[str, Any]:
        if principal.role != "admin":
            raise AlarmAuthorizationError("administrator role required")
        document = request.model_dump(mode="json")
        now_ms, utc_ms = self._clocks()
        actor = self._actor(principal, "UPDATE")
        with self._lock:
            previous_policy = self.repository.load_active_policy(request.policy_id)
            if previous_policy is not None:
                if previous_policy.document["asset_id"] != request.asset_id:
                    raise AlarmConflict("a policy cannot be reassigned to another asset")
                if request.revision <= previous_policy.revision:
                    # Identical replay remains safe at the repository level, but a
                    # public update must never move or reuse an active revision.
                    raise AlarmConflict("policy revision must advance")

            snapshot_hash = self.repository.store_policy_revision(
                request.policy_id,
                request.revision,
                document,
                utc_ms,
                activate=True,
            )
            active = ActivePolicy(
                policy_id=request.policy_id,
                revision=request.revision,
                snapshot_hash=snapshot_hash,
                document=document,
            )
            loaded = self.repository.load_runtime(
                request.policy_id, request.asset_id, self.boot_id, now_ms, utc_ms
            )

            if previous_policy is not None:
                updated = policy_updated(loaded.runtime, now_ms)
                self._persist(
                    active,
                    loaded,
                    updated,
                    now_ms,
                    utc_ms,
                    evidence=loaded.evidence,
                    actor=actor,
                    previous_policy=previous_policy,
                )
                loaded = self.repository.load_runtime(
                    request.policy_id, request.asset_id, self.boot_id, now_ms, utc_ms
                )
            elif request.enabled:
                # Starting from DISABLED makes creation of an enabled policy an
                # explicit, auditable POLICY_ENABLED transition.
                loaded = replace(loaded, runtime=replace(loaded.runtime, condition=Condition.DISABLED))

            evidence, evidence_document = self._restored_or_missing_evidence(
                loaded.evidence, now_ms, utc_ms
            )
            reduction = evaluate(loaded.runtime, self._policy(active), evidence, now_ms)
            action = "ENABLE" if request.enabled else "DISABLE"
            self._persist(
                active,
                loaded,
                reduction,
                now_ms,
                utc_ms,
                evidence=evidence_document,
                actor=self._actor(principal, action),
            )
            return self._result(active, reduction.runtime, evidence_document, now_ms, utc_ms)

    def ingest(
        self,
        request: AlarmEvidenceRequest,
        principal: Principal,
    ) -> dict[str, Any]:
        self._authorize_asset(principal, request.asset_id)
        now_ms, utc_ms = self._clocks()
        evidence, evidence_document = self._incoming_evidence(request, now_ms, utc_ms)
        with self._lock:
            policy = self._active_policy(request.policy_id, request.asset_id)
            loaded = self.repository.load_runtime(
                request.policy_id, request.asset_id, self.boot_id, now_ms, utc_ms
            )
            reduction = evaluate(loaded.runtime, self._policy(policy), evidence, now_ms)
            try:
                self._persist(
                    policy,
                    loaded,
                    reduction,
                    now_ms,
                    utc_ms,
                    evidence=evidence_document,
                    cursor=InputCursor(request.source_id, request.cursor),
                )
            except PersistenceInvariantError as exc:
                if "already consumed" in str(exc):
                    raise AlarmConflict("evidence cursor was already consumed") from exc
                raise
            return self._result(policy, reduction.runtime, evidence_document, now_ms, utc_ms)

    def acknowledge(
        self,
        policy_id: str,
        asset_id: str,
        principal: Principal,
    ) -> dict[str, Any]:
        return self._operator_command(policy_id, asset_id, principal, "ACKNOWLEDGE", None)

    def snooze(
        self,
        policy_id: str,
        asset_id: str,
        duration_ms: int,
        principal: Principal,
    ) -> dict[str, Any]:
        return self._operator_command(policy_id, asset_id, principal, "SNOOZE", duration_ms)

    def get_runtime(
        self,
        policy_id: str,
        asset_id: str,
        principal: Principal,
    ) -> dict[str, Any]:
        self._authorize_asset(principal, asset_id)
        now_ms, utc_ms = self._clocks()
        with self._lock:
            policy = self._active_policy(policy_id, asset_id)
            loaded = self.repository.load_runtime(
                policy_id, asset_id, self.boot_id, now_ms, utc_ms
            )
            _, evidence = self._restored_or_missing_evidence(
                loaded.evidence, now_ms, utc_ms
            )
            return self._result(policy, loaded.runtime, evidence, now_ms, utc_ms)

    def list_events(
        self,
        policy_id: str,
        asset_id: str,
        principal: Principal,
        after_state_revision: int,
        limit: int,
    ) -> list[dict[str, Any]]:
        self._authorize_asset(principal, asset_id)
        self._active_policy(policy_id, asset_id)
        return self.repository.list_events(
            policy_id, asset_id, after_state_revision, limit
        )

    def tick_all(self) -> int:
        """Advance persisted dwell/freshness/snooze deadlines after quiet periods."""
        now_ms, utc_ms = self._clocks()
        transitions = 0
        with self._lock:
            for policy in self.repository.list_active_policies():
                asset_id = policy.document["asset_id"]
                loaded = self.repository.load_runtime(
                    policy.policy_id, asset_id, self.boot_id, now_ms, utc_ms
                )
                evidence, document = self._restored_or_missing_evidence(
                    loaded.evidence, now_ms, utc_ms
                )
                reduction = evaluate(loaded.runtime, self._policy(policy), evidence, now_ms)
                # Persist only meaningful changes. Repeated no-op scheduler ticks do
                # not create unbounded checkpoints or database write amplification.
                if reduction.runtime == loaded.runtime:
                    continue
                try:
                    self._persist(
                        policy,
                        loaded,
                        reduction,
                        now_ms,
                        utc_ms,
                        evidence=document,
                    )
                    transitions += len(reduction.transitions)
                except RevisionConflict:
                    # Another process won this checkpoint; the next tick reloads it.
                    continue
        return transitions

    def _operator_command(
        self,
        policy_id: str,
        asset_id: str,
        principal: Principal,
        action: str,
        duration_ms: Optional[int],
    ) -> dict[str, Any]:
        self._authorize_asset(principal, asset_id)
        now_ms, utc_ms = self._clocks()
        with self._lock:
            policy = self._active_policy(policy_id, asset_id)
            loaded = self.repository.load_runtime(
                policy_id, asset_id, self.boot_id, now_ms, utc_ms
            )
            if action == "ACKNOWLEDGE":
                reduction = acknowledge(loaded.runtime, now_ms)
            else:
                assert duration_ms is not None
                reduction = snooze(loaded.runtime, now_ms, now_ms + duration_ms)
            if not reduction.transitions:
                raise AlarmConflict("command is not valid for the current alarm state")
            _, evidence = self._restored_or_missing_evidence(
                loaded.evidence, now_ms, utc_ms
            )
            self._persist(
                policy,
                loaded,
                reduction,
                now_ms,
                utc_ms,
                evidence=evidence,
                actor=self._actor(principal, action),
            )
            return self._result(policy, reduction.runtime, evidence, now_ms, utc_ms)

    def _persist(
        self,
        policy: ActivePolicy,
        loaded: LoadedRuntime,
        reduction: Reduction,
        now_ms: int,
        utc_ms: int,
        *,
        evidence: Optional[dict[str, Any]],
        cursor: Optional[InputCursor] = None,
        actor: Optional[dict[str, str]] = None,
        previous_policy: Optional[ActivePolicy] = None,
    ) -> None:
        events = self._events(
            policy,
            reduction.transitions,
            evidence,
            actor,
            previous_policy,
            now_ms,
            utc_ms,
        )
        self.repository.apply_reduction(
            policy_id=policy.policy_id,
            asset_id=policy.document["asset_id"],
            expected_state_revision=loaded.runtime.state_revision,
            expected_checkpoint_revision=loaded.checkpoint_revision,
            reduction=reduction,
            events=events,
            cursor=cursor,
            boot_id=self.boot_id,
            monotonic_ms=now_ms,
            utc_ms=utc_ms,
            evidence=evidence,
        )

    def _events(
        self,
        policy: ActivePolicy,
        transitions: tuple[Transition, ...],
        evidence: Optional[dict[str, Any]],
        actor: Optional[dict[str, str]],
        previous_policy: Optional[ActivePolicy],
        now_ms: int,
        utc_ms: int,
    ) -> list[EventRecord]:
        correlation_id = str(uuid.uuid4())
        result: list[EventRecord] = []
        for transition in transitions:
            event_id = str(uuid.uuid4())
            event_actor = actor if transition.event_type in {
                EventType.POLICY_ENABLED,
                EventType.POLICY_UPDATED,
                EventType.POLICY_DISABLED,
                EventType.ACKNOWLEDGED,
                EventType.SNOOZED,
            } else None
            payload = {
                "schema_version": "1.0.0",
                "event_id": event_id,
                "correlation_id": correlation_id,
                "deduplication_key": (
                    f"{policy.policy_id}:{policy.document['asset_id']}:"
                    f"{transition.current.state_revision}"
                ),
                "authority": "GATEWAY_AUTHORITATIVE",
                "authority_id": self.gateway_id,
                "gateway_id": self.gateway_id,
                "boot_id": self.boot_id,
                "state_revision": transition.current.state_revision,
                "policy_id": policy.policy_id,
                "policy_revision": policy.revision,
                "policy_snapshot_hash": policy.snapshot_hash,
                "previous_policy_revision": (
                    previous_policy.revision
                    if transition.event_type == EventType.POLICY_UPDATED and previous_policy
                    else None
                ),
                "previous_policy_snapshot_hash": (
                    previous_policy.snapshot_hash
                    if transition.event_type == EventType.POLICY_UPDATED and previous_policy
                    else None
                ),
                "asset_id": policy.document["asset_id"],
                "severity": policy.document["severity"],
                "event_type": transition.event_type.value,
                "reason_code": transition.reason_code,
                "previous_state": self._event_state(transition.previous, now_ms, utc_ms),
                "new_state": self._event_state(transition.current, now_ms, utc_ms),
                "occurred_monotonic_ns": now_ms * 1_000_000,
                "occurred_at": self._iso(utc_ms),
                "evidence": evidence,
                "actor": event_actor,
                # No event-signing key exists yet. Do not misrepresent transport or
                # database protection as an end-to-end event signature.
                "integrity": {"verified": False, "algorithm": None, "key_id": None},
            }
            result.append(
                EventRecord(event_id, payload["deduplication_key"], payload)
            )
        return result

    def _result(
        self,
        policy: ActivePolicy,
        runtime: AlarmRuntime,
        evidence: Optional[dict[str, Any]],
        now_ms: int,
        utc_ms: int,
    ) -> dict[str, Any]:
        latest = self.repository.list_events(
            policy.policy_id,
            policy.document["asset_id"],
            max(0, runtime.state_revision - 1),
            1,
        )
        last_event = latest[0] if latest else None
        return {
            "schema_version": "1.0.0",
            "authority": "GATEWAY_AUTHORITATIVE",
            "authority_id": self.gateway_id,
            "boot_id": self.boot_id,
            "state_revision": runtime.state_revision,
            "policy_id": policy.policy_id,
            "policy_revision": policy.revision,
            "policy_snapshot_hash": policy.snapshot_hash,
            "asset_id": policy.document["asset_id"],
            "severity": policy.document["severity"],
            "condition": runtime.condition.value,
            "attention": runtime.attention.value,
            "condition_since": self._monotonic_iso(runtime.condition_since_ms, now_ms, utc_ms),
            "attention_since": self._monotonic_iso(runtime.attention_since_ms, now_ms, utc_ms),
            "snoozed_until": self._optional_monotonic_iso(runtime.snoozed_until_ms, now_ms, utc_ms),
            "trigger_deadline_at": self._deadline(runtime, policy, "trigger", now_ms, utc_ms),
            "clear_deadline_at": self._deadline(runtime, policy, "clear", now_ms, utc_ms),
            "data_loss_deadline_at": self._deadline(runtime, policy, "data_loss", now_ms, utc_ms),
            "recovery_deadline_at": self._deadline(runtime, policy, "recovery", now_ms, utc_ms),
            "last_evaluated_at": self._iso(utc_ms),
            "next_evaluation_at": self._next_evaluation(runtime, policy, evidence, now_ms, utc_ms),
            "last_evidence": evidence,
            "last_event_id": None if last_event is None else last_event["event_id"],
            "active_correlation_id": None if last_event is None else last_event["correlation_id"],
        }

    def _incoming_evidence(
        self,
        request: AlarmEvidenceRequest,
        now_ms: int,
        utc_ms: int,
    ) -> tuple[Evidence, dict[str, Any]]:
        observed_utc_ms = (
            None
            if request.observed_at is None
            else int(request.observed_at.timestamp() * 1000)
        )
        if observed_utc_ms is not None and observed_utc_ms > utc_ms + 5_000:
            raise AlarmInputError("observed_at is too far in the future")
        age_ms = None if observed_utc_ms is None else max(0, utc_ms - observed_utc_ms)
        observed_monotonic_ms = now_ms if age_ms is None else now_ms - age_ms
        document = {
            "estimate_status": request.estimate_status,
            "method": request.method,
            "value_m": request.value_m,
            "confidence": request.confidence,
            "lower_95_m": request.lower_95_m,
            "upper_95_m": request.upper_95_m,
            "observed_at": (
                None if observed_utc_ms is None else self._iso(observed_utc_ms)
            ),
            "age_ms": age_ms,
            "source_ids": request.source_ids,
            "measurement_ids": request.measurement_ids,
            "calibration_id": request.calibration_id,
            "quality_flags": request.quality_flags,
        }
        measurement_id = hashlib.sha256(
            json.dumps(
                request.measurement_ids,
                ensure_ascii=False,
                separators=(",", ":"),
            ).encode("utf-8")
        ).hexdigest()
        return (
            Evidence(
                measurement_id=measurement_id,
                status=request.estimate_status,
                observed_monotonic_ms=observed_monotonic_ms,
                value_m=request.value_m,
                confidence=request.confidence,
                lower_95_m=request.lower_95_m,
                upper_95_m=request.upper_95_m,
                calibration_id=request.calibration_id,
            ),
            document,
        )

    def _restored_or_missing_evidence(
        self,
        document: Optional[dict[str, Any]],
        now_ms: int,
        utc_ms: int,
    ) -> tuple[Evidence, dict[str, Any]]:
        if document is None:
            missing = {
                "estimate_status": "UNOBSERVABLE",
                "method": None,
                "value_m": None,
                "confidence": None,
                "lower_95_m": None,
                "upper_95_m": None,
                "observed_at": None,
                "age_ms": None,
                "source_ids": [],
                "measurement_ids": [],
                "calibration_id": None,
                "quality_flags": ["NO_EVIDENCE"],
            }
            return Evidence(
                measurement_id="no-evidence",
                status="UNOBSERVABLE",
                observed_monotonic_ms=now_ms,
                value_m=None,
                confidence=None,
                lower_95_m=None,
                upper_95_m=None,
                calibration_id=None,
            ), missing
        restored = dict(document)
        observed = restored.get("observed_at")
        if observed is None:
            age_ms = None
        else:
            parsed = datetime.fromisoformat(observed.replace("Z", "+00:00"))
            age_ms = max(0, utc_ms - int(parsed.timestamp() * 1000))
        restored["age_ms"] = age_ms
        measurement_ids = restored.get("measurement_ids") or ["restored-evidence"]
        measurement_id = hashlib.sha256(
            json.dumps(measurement_ids, separators=(",", ":")).encode("utf-8")
        ).hexdigest()
        return Evidence(
            measurement_id=measurement_id,
            status=restored["estimate_status"],
            observed_monotonic_ms=now_ms if age_ms is None else now_ms - age_ms,
            value_m=restored.get("value_m"),
            confidence=restored.get("confidence"),
            lower_95_m=restored.get("lower_95_m"),
            upper_95_m=restored.get("upper_95_m"),
            calibration_id=restored.get("calibration_id"),
        ), restored

    @staticmethod
    def _policy(active: ActivePolicy) -> AlarmPolicy:
        document = active.document
        return AlarmPolicy(
            policy_id=active.policy_id,
            revision=active.revision,
            enabled=document["enabled"],
            threshold_m=document["threshold_m"],
            trigger_direction=document["trigger_direction"],
            decision_mode=document["decision_mode"],
            minimum_confidence=document["minimum_confidence"],
            maximum_age_ms=document["maximum_age_ms"],
            dwell_ms=document["dwell_ms"],
            clear_dwell_ms=document["clear_dwell_ms"],
            data_loss_dwell_ms=document["data_loss_dwell_ms"],
            recovery_dwell_ms=document["recovery_dwell_ms"],
            hysteresis_m=document["hysteresis_m"],
            data_loss_behavior=document["data_loss_behavior"],
        )

    def _active_policy(self, policy_id: str, asset_id: str) -> ActivePolicy:
        policy = self.repository.load_active_policy(policy_id)
        if policy is None or policy.document["asset_id"] != asset_id:
            raise AlarmNotFound("active policy not found for asset")
        return policy

    @staticmethod
    def _authorize_asset(principal: Principal, asset_id: str) -> None:
        if principal.role != "admin" and principal.subject != asset_id:
            raise AlarmAuthorizationError("principal cannot access another asset")

    @staticmethod
    def _actor(principal: Principal, action: str) -> dict[str, str]:
        return {
            "actor_id": principal.subject,
            "session_id": principal.session_id,
            "action": action,
        }

    def _clocks(self) -> tuple[int, int]:
        return int(self._monotonic() * 1000), int(self._utc_clock() * 1000)

    @staticmethod
    def _iso(utc_ms: int) -> str:
        return datetime.fromtimestamp(utc_ms / 1000, timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")

    def _monotonic_iso(self, value_ms: int, now_ms: int, utc_ms: int) -> str:
        return self._iso(utc_ms - (now_ms - value_ms))

    def _optional_monotonic_iso(
        self, value_ms: Optional[int], now_ms: int, utc_ms: int
    ) -> Optional[str]:
        return None if value_ms is None else self._monotonic_iso(value_ms, now_ms, utc_ms)

    def _event_state(self, runtime: AlarmRuntime, now_ms: int, utc_ms: int) -> dict[str, Any]:
        return {
            "condition": runtime.condition.value,
            "attention": runtime.attention.value,
            "condition_since": self._monotonic_iso(runtime.condition_since_ms, now_ms, utc_ms),
            "attention_since": self._monotonic_iso(runtime.attention_since_ms, now_ms, utc_ms),
            "snoozed_until": self._optional_monotonic_iso(runtime.snoozed_until_ms, now_ms, utc_ms),
        }

    def _deadline(
        self,
        runtime: AlarmRuntime,
        policy: ActivePolicy,
        kind: str,
        now_ms: int,
        utc_ms: int,
    ) -> Optional[str]:
        document = policy.document
        value: Optional[int] = None
        if kind == "trigger" and runtime.condition == Condition.PENDING_TRIGGER:
            value = runtime.condition_since_ms + document["dwell_ms"]
        elif kind == "clear" and runtime.condition == Condition.PENDING_CLEAR:
            value = runtime.condition_since_ms + document["clear_dwell_ms"]
        elif kind == "data_loss" and runtime.invalid_since_ms is not None:
            value = runtime.invalid_since_ms + document["data_loss_dwell_ms"]
        elif kind == "recovery" and runtime.recovery_since_ms is not None:
            value = runtime.recovery_since_ms + document["recovery_dwell_ms"]
        return self._optional_monotonic_iso(value, now_ms, utc_ms)

    def _next_evaluation(
        self,
        runtime: AlarmRuntime,
        policy: ActivePolicy,
        evidence: Optional[dict[str, Any]],
        now_ms: int,
        utc_ms: int,
    ) -> Optional[str]:
        deadlines: list[int] = []
        for kind in ("trigger", "clear", "data_loss", "recovery"):
            rendered = self._deadline(runtime, policy, kind, now_ms, utc_ms)
            if rendered is not None:
                parsed = datetime.fromisoformat(rendered.replace("Z", "+00:00"))
                deadlines.append(int(parsed.timestamp() * 1000))
        if runtime.snoozed_until_ms is not None:
            deadlines.append(utc_ms - (now_ms - runtime.snoozed_until_ms))
        if evidence is not None and evidence.get("observed_at") is not None:
            parsed = datetime.fromisoformat(evidence["observed_at"].replace("Z", "+00:00"))
            deadlines.append(
                int(parsed.timestamp() * 1000) + policy.document["maximum_age_ms"] + 1
            )
        return None if not deadlines else self._iso(min(deadlines))
