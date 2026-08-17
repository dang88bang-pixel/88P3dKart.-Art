from dataclasses import replace

import pytest

from alarm_engine import (
    AlarmPolicy,
    AlarmRuntime,
    Attention,
    Condition,
    EventType,
    Evidence,
    acknowledge,
    evaluate,
    evaluation_error,
    snooze,
)


def policy(**changes):
    base = AlarmPolicy(
        policy_id="policy-1",
        revision=1,
        enabled=True,
        threshold_m=10.0,
        trigger_direction="ABOVE",
        decision_mode="CONFIRMED_BREACH",
        minimum_confidence=0.8,
        maximum_age_ms=1_000,
        dwell_ms=200,
        clear_dwell_ms=300,
        data_loss_dwell_ms=500,
        recovery_dwell_ms=100,
        hysteresis_m=1.0,
    )
    return replace(base, **changes)


def evidence(
    measurement_id="m-1",
    observed=1_000,
    value=12.0,
    lower=11.0,
    upper=13.0,
    confidence=0.9,
    status="VALID",
    calibration="cal-1",
):
    return Evidence(
        measurement_id=measurement_id,
        status=status,
        observed_monotonic_ms=observed,
        value_m=value,
        confidence=confidence,
        lower_95_m=lower,
        upper_95_m=upper,
        calibration_id=calibration,
    )


@pytest.mark.parametrize(
    "direction,mode,lower,upper,expected",
    [
        ("ABOVE", "POSSIBLE_BREACH", 8.0, 10.1, True),
        ("ABOVE", "POSSIBLE_BREACH", 8.0, 10.0, False),
        ("ABOVE", "CONFIRMED_BREACH", 10.1, 12.0, True),
        ("ABOVE", "CONFIRMED_BREACH", 10.0, 12.0, False),
        ("BELOW", "POSSIBLE_BREACH", 9.9, 12.0, True),
        ("BELOW", "POSSIBLE_BREACH", 10.0, 12.0, False),
        ("BELOW", "CONFIRMED_BREACH", 8.0, 9.9, True),
        ("BELOW", "CONFIRMED_BREACH", 8.0, 10.0, False),
    ],
)
def test_uncertainty_aware_trigger_boundaries(direction, mode, lower, upper, expected):
    p = policy(trigger_direction=direction, decision_mode=mode, dwell_ms=0)
    value = (lower + upper) / 2
    result = evaluate(
        AlarmRuntime(), p, evidence(value=value, lower=lower, upper=upper), 1_000
    )
    assert (result.runtime.condition == Condition.ACTIVE) is expected


def test_trigger_dwell_cancels_and_then_triggers_at_boundary():
    p = policy(dwell_ms=200)
    pending = evaluate(AlarmRuntime(), p, evidence(), 1_000)
    assert pending.runtime.condition == Condition.PENDING_TRIGGER
    assert pending.transitions[0].event_type == EventType.PENDING_STARTED

    canceled = evaluate(
        pending.runtime,
        p,
        evidence("m-2", 1_100, value=9.0, lower=8.0, upper=10.0),
        1_100,
    )
    assert canceled.runtime.condition == Condition.NORMAL
    assert canceled.transitions[0].event_type == EventType.PENDING_CANCELED

    pending = evaluate(canceled.runtime, p, evidence("m-3", 1_200), 1_200)
    not_due = evaluate(pending.runtime, p, evidence("m-4", 1_399), 1_399)
    due = evaluate(not_due.runtime, p, evidence("m-5", 1_400), 1_400)
    assert not_due.runtime.condition == Condition.PENDING_TRIGGER
    assert due.runtime.condition == Condition.ACTIVE
    assert due.runtime.attention == Attention.UNACKNOWLEDGED
    assert due.transitions[0].event_type == EventType.TRIGGERED


def test_hysteresis_and_clear_dwell_prevent_chatter():
    p = policy(dwell_ms=0, clear_dwell_ms=300, hysteresis_m=1.0)
    active = evaluate(AlarmRuntime(), p, evidence(), 1_000).runtime

    # Confirmed breach ended, but upper bound has not crossed the 9 m clear boundary.
    band = evaluate(
        active, p, evidence("m-2", 1_100, value=9.0, lower=8.5, upper=9.5), 1_100
    )
    assert band.runtime.condition == Condition.ACTIVE
    assert not band.transitions

    pending = evaluate(
        band.runtime, p, evidence("m-3", 1_200, value=8.0, lower=7.5, upper=8.5), 1_200
    )
    assert pending.runtime.condition == Condition.PENDING_CLEAR
    assert pending.transitions[0].event_type == EventType.CLEAR_PENDING_STARTED

    canceled = evaluate(
        pending.runtime, p, evidence("m-4", 1_300, value=9.0, lower=8.5, upper=9.5), 1_300
    )
    assert canceled.runtime.condition == Condition.ACTIVE
    assert canceled.transitions[0].event_type == EventType.CLEAR_PENDING_CANCELED

    pending = evaluate(
        canceled.runtime, p, evidence("m-5", 1_400, value=8.0, lower=7.5, upper=8.5), 1_400
    )
    cleared = evaluate(
        pending.runtime, p, evidence("m-6", 1_700, value=8.0, lower=7.5, upper=8.5), 1_700
    )
    assert cleared.runtime.condition == Condition.NORMAL
    assert cleared.runtime.attention == Attention.NONE
    assert cleared.transitions[0].event_type == EventType.CLEARED


@pytest.mark.parametrize(
    "bad",
    [
        {"status": "STALE"},
        {"confidence": 0.79},
        {"calibration": None},
        {"value": None, "lower": None, "upper": None},
        {"observed": -1},
        {"observed": 3_000},
    ],
)
def test_inadmissible_evidence_enters_data_loss_only_after_dwell(bad):
    p = policy(data_loss_dwell_ms=500)
    first = evaluate(AlarmRuntime(), p, evidence(**bad), 1_000)
    assert first.runtime.condition == Condition.NORMAL
    assert first.runtime.invalid_since_ms == 1_000
    assert not first.transitions

    due_evidence = evidence("m-2", **bad)
    due = evaluate(first.runtime, p, due_evidence, 1_500)
    assert due.runtime.condition == Condition.DATA_LOSS
    assert due.runtime.attention == Attention.UNACKNOWLEDGED
    assert due.transitions[0].event_type == EventType.DATA_LOSS_STARTED


def test_data_loss_requires_continuous_recovery_then_reevaluates():
    p = policy(dwell_ms=0, data_loss_dwell_ms=0, recovery_dwell_ms=100)
    lost = evaluate(
        AlarmRuntime(), p, evidence(status="UNOBSERVABLE"), 1_000
    ).runtime
    assert lost.condition == Condition.DATA_LOSS

    recovering = evaluate(lost, p, evidence("m-2", 1_050), 1_050)
    assert recovering.runtime.condition == Condition.DATA_LOSS
    assert not recovering.transitions

    interrupted = evaluate(
        recovering.runtime, p, evidence("m-3", 1_100, status="STALE"), 1_100
    )
    assert interrupted.runtime.recovery_since_ms is None

    recovering = evaluate(interrupted.runtime, p, evidence("m-4", 1_200), 1_200)
    recovered = evaluate(recovering.runtime, p, evidence("m-5", 1_300), 1_300)
    assert [t.event_type for t in recovered.transitions] == [
        EventType.DATA_LOSS_CLEARED,
        EventType.PENDING_STARTED,
        EventType.TRIGGERED,
    ]
    assert recovered.runtime.condition == Condition.ACTIVE


def test_acknowledge_snooze_and_expiry_do_not_clear_condition():
    p = policy(dwell_ms=0)
    active = evaluate(AlarmRuntime(), p, evidence(), 1_000).runtime

    acked = acknowledge(active, 1_010)
    assert acked.runtime.condition == Condition.ACTIVE
    assert acked.runtime.attention == Attention.ACKNOWLEDGED
    assert acked.transitions[0].event_type == EventType.ACKNOWLEDGED

    snoozed = snooze(acked.runtime, 1_020, 2_000)
    assert snoozed.runtime.attention == Attention.SNOOZED
    before = evaluate(snoozed.runtime, p, evidence("m-2", 1_999), 1_999)
    assert before.runtime.attention == Attention.SNOOZED

    expired = evaluate(before.runtime, p, evidence("m-3", 2_000), 2_000)
    assert expired.runtime.condition == Condition.ACTIVE
    assert expired.runtime.attention == Attention.UNACKNOWLEDGED
    assert expired.transitions[0].event_type == EventType.SNOOZE_EXPIRED


def test_disabled_policy_is_idempotent():
    p = policy(enabled=False)
    first = evaluate(AlarmRuntime(), p, evidence(), 1_000)
    second = evaluate(first.runtime, p, evidence("m-2"), 1_001)
    assert first.runtime.condition == Condition.DISABLED
    assert first.transitions[0].event_type == EventType.POLICY_DISABLED
    assert not second.transitions


def test_policy_enable_reconciles_evidence_instead_of_assuming_normal():
    disabled = evaluate(AlarmRuntime(), policy(enabled=False), evidence(), 1_000).runtime
    enabled = evaluate(
        disabled,
        policy(enabled=True, dwell_ms=0),
        evidence("m-2", 1_100),
        1_100,
    )
    assert [transition.event_type for transition in enabled.transitions] == [
        EventType.POLICY_ENABLED,
        EventType.PENDING_STARTED,
        EventType.TRIGGERED,
    ]
    assert enabled.runtime.condition == Condition.ACTIVE


def test_evaluation_error_is_distinct_from_data_loss_and_recovers_by_reevaluation():
    failed = evaluation_error(AlarmRuntime(), 1_000)
    assert failed.runtime.condition == Condition.ERROR
    assert failed.transitions[0].event_type == EventType.EVALUATION_ERROR

    recovered = evaluate(
        failed.runtime,
        policy(data_loss_dwell_ms=0),
        evidence("m-2", 1_100, status="UNOBSERVABLE"),
        1_100,
    )
    assert [transition.event_type for transition in recovered.transitions] == [
        EventType.EVALUATION_RECOVERED,
        EventType.DATA_LOSS_STARTED,
    ]
    assert recovered.runtime.condition == Condition.DATA_LOSS


def test_policy_validation_rejects_unsupported_or_invalid_values():
    with pytest.raises(ValueError):
        policy(trigger_direction="OUTSIDE")
    with pytest.raises(ValueError):
        policy(minimum_confidence=1.1)
    with pytest.raises(ValueError):
        policy(hysteresis_m=-1)
