"""Pure deterministic reducer for gateway-authoritative distance alarms.

The reducer has no I/O and uses caller-supplied monotonic milliseconds. Persistence,
UTC rendering, authentication, delivery, and scheduling belong to surrounding layers.
"""
from __future__ import annotations

from dataclasses import dataclass, replace
from enum import Enum
from typing import Optional


class Condition(str, Enum):
    NORMAL = "NORMAL"
    PENDING_TRIGGER = "PENDING_TRIGGER"
    ACTIVE = "ACTIVE"
    PENDING_CLEAR = "PENDING_CLEAR"
    DATA_LOSS = "DATA_LOSS"
    DISABLED = "DISABLED"
    ERROR = "ERROR"


class Attention(str, Enum):
    NONE = "NONE"
    UNACKNOWLEDGED = "UNACKNOWLEDGED"
    ACKNOWLEDGED = "ACKNOWLEDGED"
    SNOOZED = "SNOOZED"


class EventType(str, Enum):
    POLICY_ENABLED = "POLICY_ENABLED"
    POLICY_UPDATED = "POLICY_UPDATED"
    PENDING_STARTED = "PENDING_STARTED"
    PENDING_CANCELED = "PENDING_CANCELED"
    TRIGGERED = "TRIGGERED"
    CLEAR_PENDING_STARTED = "CLEAR_PENDING_STARTED"
    CLEAR_PENDING_CANCELED = "CLEAR_PENDING_CANCELED"
    CLEARED = "CLEARED"
    DATA_LOSS_STARTED = "DATA_LOSS_STARTED"
    DATA_LOSS_CLEARED = "DATA_LOSS_CLEARED"
    ACKNOWLEDGED = "ACKNOWLEDGED"
    SNOOZED = "SNOOZED"
    SNOOZE_EXPIRED = "SNOOZE_EXPIRED"
    POLICY_DISABLED = "POLICY_DISABLED"
    EVALUATION_ERROR = "EVALUATION_ERROR"
    EVALUATION_RECOVERED = "EVALUATION_RECOVERED"


@dataclass(frozen=True)
class AlarmPolicy:
    policy_id: str
    revision: int
    enabled: bool
    threshold_m: float
    trigger_direction: str
    decision_mode: str
    minimum_confidence: float
    maximum_age_ms: int
    dwell_ms: int
    clear_dwell_ms: int
    data_loss_dwell_ms: int
    recovery_dwell_ms: int
    hysteresis_m: float
    data_loss_behavior: str = "SEPARATE_ALARM"

    def __post_init__(self) -> None:
        if self.revision < 1:
            raise ValueError("policy revision must be positive")
        if self.threshold_m <= 0:
            raise ValueError("threshold_m must be positive")
        if self.trigger_direction not in {"ABOVE", "BELOW"}:
            raise ValueError("only ABOVE and BELOW range policies are supported")
        if self.decision_mode not in {"POSSIBLE_BREACH", "CONFIRMED_BREACH"}:
            raise ValueError("unsupported decision mode")
        if not 0 <= self.minimum_confidence <= 1:
            raise ValueError("minimum_confidence must be in [0, 1]")
        for name in (
            "maximum_age_ms",
            "dwell_ms",
            "clear_dwell_ms",
            "data_loss_dwell_ms",
            "recovery_dwell_ms",
        ):
            if getattr(self, name) < 0:
                raise ValueError(f"{name} must not be negative")
        if self.hysteresis_m < 0:
            raise ValueError("hysteresis_m must not be negative")


@dataclass(frozen=True)
class Evidence:
    measurement_id: str
    status: str
    observed_monotonic_ms: int
    value_m: Optional[float]
    confidence: Optional[float]
    lower_95_m: Optional[float]
    upper_95_m: Optional[float]
    calibration_id: Optional[str]


@dataclass(frozen=True)
class AlarmRuntime:
    condition: Condition = Condition.NORMAL
    attention: Attention = Attention.NONE
    condition_since_ms: int = 0
    attention_since_ms: int = 0
    invalid_since_ms: Optional[int] = None
    recovery_since_ms: Optional[int] = None
    snoozed_until_ms: Optional[int] = None
    state_revision: int = 0
    last_measurement_id: Optional[str] = None


@dataclass(frozen=True)
class Transition:
    event_type: EventType
    reason_code: str
    previous: AlarmRuntime
    current: AlarmRuntime


@dataclass(frozen=True)
class Reduction:
    runtime: AlarmRuntime
    transitions: tuple[Transition, ...] = ()


def evaluate(
    runtime: AlarmRuntime,
    policy: AlarmPolicy,
    evidence: Evidence,
    now_ms: int,
) -> Reduction:
    """Evaluate one evidence snapshot and return state plus immutable transitions."""
    if now_ms < 0:
        raise ValueError("now_ms must not be negative")
    transitions: list[Transition] = []
    current = _expire_snooze(runtime, now_ms, transitions)

    if not policy.enabled:
        if current.condition != Condition.DISABLED:
            current = _transition(
                current,
                now_ms,
                Condition.DISABLED,
                Attention.NONE,
                EventType.POLICY_DISABLED,
                "POLICY.DISABLED",
                transitions,
            )
        return Reduction(current, tuple(transitions))

    if current.condition == Condition.DISABLED:
        current = _transition(
            current,
            now_ms,
            Condition.NORMAL,
            Attention.NONE,
            EventType.POLICY_ENABLED,
            "POLICY.ENABLED",
            transitions,
        )
    elif current.condition == Condition.ERROR:
        current = _transition(
            current,
            now_ms,
            Condition.NORMAL,
            Attention.NONE,
            EventType.EVALUATION_RECOVERED,
            "EVALUATOR.RECOVERED",
            transitions,
        )

    # Duplicate evidence can drive a due timer but cannot replace the cursor.
    duplicate = evidence.measurement_id == current.last_measurement_id
    if not duplicate:
        current = replace(current, last_measurement_id=evidence.measurement_id)

    if not _is_admissible(policy, evidence, now_ms):
        current = _handle_invalid(current, policy, now_ms, transitions)
        return Reduction(current, tuple(transitions))

    current, recovered = _handle_recovery(current, policy, now_ms, transitions)
    if not recovered:
        return Reduction(current, tuple(transitions))

    breach = _breach_predicate(policy, evidence)
    clear = _clear_predicate(policy, evidence)
    current = _reduce_condition(current, policy, breach, clear, now_ms, transitions)
    return Reduction(current, tuple(transitions))


def policy_updated(runtime: AlarmRuntime, now_ms: int) -> Reduction:
    """Record an immutable policy-revision change without inventing a state change."""
    previous = runtime
    current = replace(runtime, state_revision=runtime.state_revision + 1)
    return Reduction(
        current,
        (Transition(EventType.POLICY_UPDATED, "POLICY.UPDATED", previous, current),),
    )


def acknowledge(runtime: AlarmRuntime, now_ms: int) -> Reduction:
    if runtime.condition not in {Condition.ACTIVE, Condition.DATA_LOSS, Condition.PENDING_CLEAR}:
        return Reduction(runtime)
    if runtime.attention == Attention.ACKNOWLEDGED:
        return Reduction(runtime)
    previous = runtime
    current = replace(
        runtime,
        attention=Attention.ACKNOWLEDGED,
        attention_since_ms=now_ms,
        snoozed_until_ms=None,
        state_revision=runtime.state_revision + 1,
    )
    return Reduction(
        current,
        (Transition(EventType.ACKNOWLEDGED, "OPERATOR.ACKNOWLEDGED", previous, current),),
    )


def snooze(runtime: AlarmRuntime, now_ms: int, until_ms: int) -> Reduction:
    if until_ms <= now_ms:
        raise ValueError("snooze deadline must be in the future")
    if runtime.condition not in {Condition.ACTIVE, Condition.DATA_LOSS, Condition.PENDING_CLEAR}:
        return Reduction(runtime)
    previous = runtime
    current = replace(
        runtime,
        attention=Attention.SNOOZED,
        attention_since_ms=now_ms,
        snoozed_until_ms=until_ms,
        state_revision=runtime.state_revision + 1,
    )
    return Reduction(
        current,
        (Transition(EventType.SNOOZED, "OPERATOR.SNOOZED", previous, current),),
    )


def evaluation_error(
    runtime: AlarmRuntime,
    now_ms: int,
    reason_code: str = "EVALUATOR.INTERNAL_ERROR",
) -> Reduction:
    """Enter ERROR after a non-recoverable evaluator failure.

    Expected evidence-quality failures are represented as data loss instead; this
    command is reserved for software/configuration faults in the evaluation path.
    """
    if runtime.condition == Condition.ERROR:
        return Reduction(runtime)
    transitions: list[Transition] = []
    current = _transition(
        runtime,
        now_ms,
        Condition.ERROR,
        Attention.UNACKNOWLEDGED,
        EventType.EVALUATION_ERROR,
        reason_code,
        transitions,
        recovery_since_ms=None,
    )
    return Reduction(current, tuple(transitions))


def _is_admissible(policy: AlarmPolicy, evidence: Evidence, now_ms: int) -> bool:
    age_ms = now_ms - evidence.observed_monotonic_ms
    return (
        evidence.status == "VALID"
        and 0 <= age_ms <= policy.maximum_age_ms
        and evidence.value_m is not None
        and evidence.value_m >= 0
        and evidence.confidence is not None
        and evidence.confidence >= policy.minimum_confidence
        and evidence.lower_95_m is not None
        and evidence.upper_95_m is not None
        and 0 <= evidence.lower_95_m <= evidence.value_m <= evidence.upper_95_m
        and evidence.calibration_id is not None
    )


def _breach_predicate(policy: AlarmPolicy, evidence: Evidence) -> bool:
    assert evidence.lower_95_m is not None and evidence.upper_95_m is not None
    if policy.trigger_direction == "ABOVE":
        boundary = (
            evidence.upper_95_m
            if policy.decision_mode == "POSSIBLE_BREACH"
            else evidence.lower_95_m
        )
        return boundary > policy.threshold_m
    boundary = (
        evidence.lower_95_m
        if policy.decision_mode == "POSSIBLE_BREACH"
        else evidence.upper_95_m
    )
    return boundary < policy.threshold_m


def _clear_predicate(policy: AlarmPolicy, evidence: Evidence) -> bool:
    assert evidence.lower_95_m is not None and evidence.upper_95_m is not None
    if policy.trigger_direction == "ABOVE":
        return evidence.upper_95_m < policy.threshold_m - policy.hysteresis_m
    return evidence.lower_95_m > policy.threshold_m + policy.hysteresis_m


def _handle_invalid(
    runtime: AlarmRuntime,
    policy: AlarmPolicy,
    now_ms: int,
    transitions: list[Transition],
) -> AlarmRuntime:
    current = replace(runtime, recovery_since_ms=None)
    if current.condition in {Condition.DISABLED, Condition.ERROR, Condition.DATA_LOSS}:
        return current
    invalid_since = current.invalid_since_ms
    if invalid_since is None:
        invalid_since = now_ms
        current = replace(current, invalid_since_ms=invalid_since)
    if now_ms - invalid_since >= policy.data_loss_dwell_ms:
        return _transition(
            current,
            now_ms,
            Condition.DATA_LOSS,
            Attention.UNACKNOWLEDGED,
            EventType.DATA_LOSS_STARTED,
            "EVIDENCE.UNAVAILABLE",
            transitions,
            invalid_since_ms=invalid_since,
        )
    return current


def _handle_recovery(
    runtime: AlarmRuntime,
    policy: AlarmPolicy,
    now_ms: int,
    transitions: list[Transition],
) -> tuple[AlarmRuntime, bool]:
    if runtime.condition != Condition.DATA_LOSS:
        return replace(runtime, invalid_since_ms=None, recovery_since_ms=None), True

    recovery_since = runtime.recovery_since_ms
    if recovery_since is None:
        recovery_since = now_ms
        runtime = replace(runtime, recovery_since_ms=recovery_since)
    if now_ms - recovery_since < policy.recovery_dwell_ms:
        return runtime, False

    recovered = _transition(
        runtime,
        now_ms,
        Condition.NORMAL,
        Attention.NONE,
        EventType.DATA_LOSS_CLEARED,
        "EVIDENCE.RECOVERED",
        transitions,
        invalid_since_ms=None,
        recovery_since_ms=None,
    )
    return recovered, True


def _reduce_condition(
    runtime: AlarmRuntime,
    policy: AlarmPolicy,
    breach: bool,
    clear: bool,
    now_ms: int,
    transitions: list[Transition],
) -> AlarmRuntime:
    if runtime.condition == Condition.NORMAL:
        if not breach:
            return runtime
        pending = _transition(
            runtime, now_ms, Condition.PENDING_TRIGGER, Attention.NONE,
            EventType.PENDING_STARTED, "THRESHOLD.BREACH_PENDING", transitions,
        )
        if policy.dwell_ms == 0:
            return _transition(
                pending, now_ms, Condition.ACTIVE, Attention.UNACKNOWLEDGED,
                EventType.TRIGGERED, "THRESHOLD.BREACHED", transitions,
            )
        return pending

    if runtime.condition == Condition.PENDING_TRIGGER:
        if not breach:
            return _transition(
                runtime, now_ms, Condition.NORMAL, Attention.NONE,
                EventType.PENDING_CANCELED, "THRESHOLD.BREACH_CANCELED", transitions,
            )
        if now_ms - runtime.condition_since_ms >= policy.dwell_ms:
            return _transition(
                runtime, now_ms, Condition.ACTIVE, Attention.UNACKNOWLEDGED,
                EventType.TRIGGERED, "THRESHOLD.BREACHED", transitions,
            )
        return runtime

    if runtime.condition == Condition.ACTIVE:
        if not clear:
            return runtime
        pending = _transition(
            runtime, now_ms, Condition.PENDING_CLEAR, runtime.attention,
            EventType.CLEAR_PENDING_STARTED, "THRESHOLD.CLEAR_PENDING", transitions,
        )
        if policy.clear_dwell_ms == 0:
            return _transition(
                pending, now_ms, Condition.NORMAL, Attention.NONE,
                EventType.CLEARED, "THRESHOLD.CLEARED", transitions,
            )
        return pending

    if runtime.condition == Condition.PENDING_CLEAR:
        if not clear:
            return _transition(
                runtime, now_ms, Condition.ACTIVE, runtime.attention,
                EventType.CLEAR_PENDING_CANCELED, "THRESHOLD.CLEAR_CANCELED", transitions,
            )
        if now_ms - runtime.condition_since_ms >= policy.clear_dwell_ms:
            return _transition(
                runtime, now_ms, Condition.NORMAL, Attention.NONE,
                EventType.CLEARED, "THRESHOLD.CLEARED", transitions,
            )
        return runtime

    return runtime


def _expire_snooze(
    runtime: AlarmRuntime,
    now_ms: int,
    transitions: list[Transition],
) -> AlarmRuntime:
    if (
        runtime.attention != Attention.SNOOZED
        or runtime.snoozed_until_ms is None
        or now_ms < runtime.snoozed_until_ms
    ):
        return runtime
    previous = runtime
    attention = (
        Attention.UNACKNOWLEDGED
        if runtime.condition in {Condition.ACTIVE, Condition.DATA_LOSS, Condition.PENDING_CLEAR}
        else Attention.NONE
    )
    current = replace(
        runtime,
        attention=attention,
        attention_since_ms=now_ms,
        snoozed_until_ms=None,
        state_revision=runtime.state_revision + 1,
    )
    transitions.append(
        Transition(EventType.SNOOZE_EXPIRED, "OPERATOR.SNOOZE_EXPIRED", previous, current)
    )
    return current


def _transition(
    runtime: AlarmRuntime,
    now_ms: int,
    condition: Condition,
    attention: Attention,
    event_type: EventType,
    reason_code: str,
    transitions: list[Transition],
    **changes: object,
) -> AlarmRuntime:
    previous = runtime
    attention_since = (
        now_ms if attention != runtime.attention else runtime.attention_since_ms
    )
    current = replace(
        runtime,
        condition=condition,
        attention=attention,
        condition_since_ms=now_ms,
        attention_since_ms=attention_since,
        snoozed_until_ms=(
            runtime.snoozed_until_ms if attention == Attention.SNOOZED else None
        ),
        state_revision=runtime.state_revision + 1,
        **changes,
    )
    transitions.append(Transition(event_type, reason_code, previous, current))
    return current
