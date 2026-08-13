import sqlite3
from dataclasses import replace

import pytest

from alarm_engine import AlarmPolicy, AlarmRuntime, Evidence, EventType, evaluate
from alarm_repository import (
    AlarmRepository,
    EventRecord,
    InputCursor,
    PersistenceInvariantError,
    RevisionConflict,
)


def policy(**changes):
    base = AlarmPolicy(
        policy_id="policy-1",
        revision=1,
        enabled=True,
        threshold_m=10,
        trigger_direction="ABOVE",
        decision_mode="CONFIRMED_BREACH",
        minimum_confidence=0.8,
        maximum_age_ms=1_000,
        dwell_ms=200,
        clear_dwell_ms=0,
        data_loss_dwell_ms=500,
        recovery_dwell_ms=100,
        hysteresis_m=1,
    )
    return replace(base, **changes)


def evidence(identifier, observed, lower=11.0, upper=13.0):
    return Evidence(
        measurement_id=identifier,
        status="VALID",
        observed_monotonic_ms=observed,
        value_m=(lower + upper) / 2,
        confidence=0.9,
        lower_95_m=lower,
        upper_95_m=upper,
        calibration_id="cal-1",
    )


def records(reduction, prefix="event"):
    return [
        EventRecord(
            event_id=f"{prefix}-{index}",
            deduplication_key=f"policy-1:asset-1:{transition.current.state_revision}",
            payload={
                "event_type": transition.event_type.value,
                "state_revision": transition.current.state_revision,
            },
        )
        for index, transition in enumerate(reduction.transitions)
    ]


def apply(
    repository,
    reduction,
    expected_state,
    expected_checkpoint,
    cursor,
    event_records=None,
    monotonic=1_000,
    utc=10_000,
    boot="boot-1",
    evidence_document=None,
):
    repository.apply_reduction(
        policy_id="policy-1",
        asset_id="asset-1",
        expected_state_revision=expected_state,
        expected_checkpoint_revision=expected_checkpoint,
        reduction=reduction,
        events=records(reduction) if event_records is None else event_records,
        cursor=None if cursor is None else InputCursor("fusion", cursor),
        boot_id=boot,
        monotonic_ms=monotonic,
        utc_ms=utc,
        evidence=evidence_document,
    )


def test_policy_revisions_are_canonical_immutable_and_monotonic(tmp_path):
    repository = AlarmRepository(str(tmp_path / "alarm.db"))
    first_hash = repository.store_policy_revision(
        "policy-1", 1, {"b": 2, "a": 1}, 1_000, activate=True
    )
    same_hash = repository.store_policy_revision(
        "policy-1", 1, {"a": 1, "b": 2}, 1_100, activate=True
    )
    assert first_hash == same_hash

    with pytest.raises(PersistenceInvariantError, match="immutable"):
        repository.store_policy_revision(
            "policy-1", 1, {"a": 3}, 1_200, activate=True
        )

    repository.store_policy_revision("policy-1", 2, {"a": 4}, 1_300, activate=True)
    with pytest.raises(PersistenceInvariantError, match="backwards"):
        repository.store_policy_revision(
            "policy-1", 1, {"a": 1, "b": 2}, 1_400, activate=True
        )


def test_runtime_event_cursor_and_outbox_are_committed_atomically(tmp_path):
    repository = AlarmRepository(str(tmp_path / "alarm.db"))
    reduction = evaluate(AlarmRuntime(), policy(), evidence("m-1", 1_000), 1_000)
    assert reduction.transitions[0].event_type == EventType.PENDING_STARTED

    apply(repository, reduction, 0, 0, "cursor-1")
    loaded = repository.load_runtime("policy-1", "asset-1", "boot-1", 1_000, 10_000)
    assert loaded.runtime == reduction.runtime
    assert loaded.checkpoint_revision == 1
    assert repository.event_count() == 1

    claimed = repository.claim_outbox(10_000, lease_ms=500)
    assert len(claimed) == 1
    assert claimed[0].payload["event_type"] == "PENDING_STARTED"
    assert claimed[0].attempts == 1
    assert repository.acknowledge_delivery(claimed[0].event_id)
    assert repository.claim_outbox(20_000, lease_ms=500) == []


def test_failed_event_insert_rolls_back_runtime_cursor_and_outbox(tmp_path):
    repository = AlarmRepository(str(tmp_path / "alarm.db"))
    pending = evaluate(AlarmRuntime(), policy(), evidence("m-1", 1_000), 1_000)
    first_records = records(pending)
    apply(repository, pending, 0, 0, "cursor-1", first_records)

    active = evaluate(pending.runtime, policy(), evidence("m-2", 1_200), 1_200)
    duplicate_event = EventRecord(
        event_id=first_records[0].event_id,
        deduplication_key="different-dedup-key",
        payload={
            "event_type": active.transitions[0].event_type.value,
            "state_revision": active.runtime.state_revision,
        },
    )
    with pytest.raises(sqlite3.IntegrityError):
        apply(
            repository,
            active,
            1,
            1,
            "cursor-2",
            [duplicate_event],
            monotonic=1_200,
            utc=10_200,
        )

    loaded = repository.load_runtime("policy-1", "asset-1", "boot-1", 1_200, 10_200)
    assert loaded.runtime == pending.runtime
    assert loaded.checkpoint_revision == 1
    assert repository.event_count() == 1

    # The rolled-back cursor can be accepted with a valid, unique event.
    valid_records = records(active, "active")
    apply(
        repository,
        active,
        1,
        1,
        "cursor-2",
        valid_records,
        monotonic=1_200,
        utc=10_200,
    )
    assert repository.event_count() == 2


def test_checkpoint_cas_prevents_lost_updates_without_events(tmp_path):
    repository = AlarmRepository(str(tmp_path / "alarm.db"))
    normal_evidence = evidence("m-1", 1_000, lower=7.0, upper=9.0)
    first = evaluate(AlarmRuntime(), policy(), normal_evidence, 1_000)
    assert not first.transitions
    apply(repository, first, 0, 0, "cursor-1")

    snapshot_a = repository.load_runtime("policy-1", "asset-1", "boot-1", 1_100, 10_100)
    snapshot_b = repository.load_runtime("policy-1", "asset-1", "boot-1", 1_100, 10_100)
    second = evaluate(
        snapshot_a.runtime,
        policy(),
        evidence("m-2", 1_100, lower=7.0, upper=9.0),
        1_100,
    )
    apply(repository, second, 0, snapshot_a.checkpoint_revision, "cursor-2")

    stale = evaluate(
        snapshot_b.runtime,
        policy(),
        evidence("m-3", 1_100, lower=7.0, upper=9.0),
        1_100,
    )
    with pytest.raises(RevisionConflict):
        apply(repository, stale, 0, snapshot_b.checkpoint_revision, "cursor-3")


def test_scheduler_tick_can_reuse_checkpointed_evidence_without_input_cursor(tmp_path):
    repository = AlarmRepository(str(tmp_path / "alarm.db"))
    evidence_document = {
        "estimate_status": "VALID",
        "measurement_ids": ["m-1"],
        "value_m": 12.0,
    }
    pending = evaluate(AlarmRuntime(), policy(), evidence("m-1", 1_000), 1_000)
    apply(
        repository,
        pending,
        0,
        0,
        "cursor-1",
        evidence_document=evidence_document,
    )

    loaded = repository.load_runtime("policy-1", "asset-1", "boot-1", 1_200, 10_200)
    assert loaded.evidence == evidence_document
    due = evaluate(loaded.runtime, policy(), evidence("m-1", 1_000), 1_200)
    apply(
        repository,
        due,
        1,
        loaded.checkpoint_revision,
        None,
        records(due, "timer"),
        monotonic=1_200,
        utc=10_200,
    )
    after = repository.load_runtime("policy-1", "asset-1", "boot-1", 1_200, 10_200)
    assert after.evidence == evidence_document
    assert after.runtime.condition.value == "ACTIVE"


def test_cross_boot_load_rebases_dwell_and_flags_wall_clock_rollback(tmp_path):
    repository = AlarmRepository(str(tmp_path / "alarm.db"))
    pending = evaluate(AlarmRuntime(), policy(), evidence("m-1", 5_000), 5_000)
    apply(
        repository,
        pending,
        0,
        0,
        "cursor-1",
        monotonic=5_000,
        utc=100_000,
        boot="boot-old",
    )

    # 250 ms offline means the 200 ms pending dwell is already due at boot.
    recovered = repository.load_runtime(
        "policy-1", "asset-1", "boot-new", monotonic_ms=100, utc_ms=100_250
    )
    assert recovered.runtime.condition_since_ms == -150
    assert not recovered.clock_discontinuity
    due = evaluate(
        recovered.runtime,
        policy(),
        evidence("m-2", 100),
        100,
    )
    assert due.runtime.condition.value == "ACTIVE"

    rollback = repository.load_runtime(
        "policy-1", "asset-1", "boot-new", monotonic_ms=100, utc_ms=99_000
    )
    assert rollback.clock_discontinuity


def test_outbox_lease_expiry_and_explicit_retry_are_at_least_once(tmp_path):
    repository = AlarmRepository(str(tmp_path / "alarm.db"))
    reduction = evaluate(
        AlarmRuntime(), policy(dwell_ms=200), evidence("m-1", 1_000), 1_000
    )
    apply(repository, reduction, 0, 0, "cursor-1")

    first = repository.claim_outbox(10_000, lease_ms=100)[0]
    assert repository.claim_outbox(10_050, lease_ms=100) == []
    second = repository.claim_outbox(10_100, lease_ms=100)[0]
    assert second.event_id == first.event_id
    assert second.attempts == 2

    assert repository.retry_delivery(second.event_id, 20_000, "network unavailable")
    assert repository.claim_outbox(19_999, lease_ms=100) == []
    third = repository.claim_outbox(20_000, lease_ms=100)[0]
    assert third.attempts == 3


def test_repository_rejects_missing_or_mismatched_transition_events(tmp_path):
    repository = AlarmRepository(str(tmp_path / "alarm.db"))
    reduction = evaluate(AlarmRuntime(), policy(), evidence("m-1", 1_000), 1_000)
    with pytest.raises(PersistenceInvariantError, match="exactly one"):
        apply(repository, reduction, 0, 0, "cursor-1", [])

    bad = EventRecord(
        "event-1",
        "policy-1:asset-1:wrong",
        {"state_revision": 999},
    )
    with pytest.raises(PersistenceInvariantError, match="does not match"):
        apply(repository, reduction, 0, 0, "cursor-1", [bad])
