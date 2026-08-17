from datetime import datetime, timezone
import json
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker
import pytest
from pydantic import ValidationError

from alarm_repository import AlarmRepository
from alarm_service import (
    AlarmAuthorizationError,
    AlarmConflict,
    AlarmInputError,
    AlarmService,
)
from models import AlarmEvidenceRequest, AlarmPolicyRequest
from security import Principal

POLICY_ID = "123e4567-e89b-12d3-a456-426614174000"
BOOT_ID = "123e4567-e89b-12d3-a456-426614174001"


class FakeClock:
    def __init__(self, monotonic_s=10.0, utc_s=1_700_000_000.0):
        self.monotonic_s = monotonic_s
        self.utc_s = utc_s

    def monotonic(self):
        return self.monotonic_s

    def utc(self):
        return self.utc_s

    def advance_ms(self, milliseconds):
        self.monotonic_s += milliseconds / 1000
        self.utc_s += milliseconds / 1000


ADMIN = Principal("administrator", "admin", "admin-session")
DEVICE = Principal("asset-1", "device", "device-session")
OTHER_DEVICE = Principal("asset-2", "device", "other-session")


def policy(revision=1, **changes):
    values = {
        "schema_version": "1.0.0",
        "policy_id": POLICY_ID,
        "asset_id": "asset-1",
        "revision": revision,
        "enabled": True,
        "metric": "RANGE_FROM_CT45P",
        "reference_id": None,
        "threshold_m": 10.0,
        "trigger_direction": "ABOVE",
        "decision_mode": "CONFIRMED_BREACH",
        "minimum_confidence": 0.8,
        "maximum_age_ms": 10_000,
        "dwell_ms": 200,
        "clear_dwell_ms": 100,
        "data_loss_dwell_ms": 500,
        "recovery_dwell_ms": 0,
        "hysteresis_m": 1.0,
        "cooldown_ms": 0,
        "severity": "WARNING",
        "data_loss_behavior": "SEPARATE_ALARM",
        "delivery_profile_id": "operators",
    }
    values.update(changes)
    return AlarmPolicyRequest(**values)


def evidence(clock, cursor="cursor-1", measurements=None, **changes):
    values = {
        "policy_id": POLICY_ID,
        "asset_id": "asset-1",
        "source_id": "fusion",
        "cursor": cursor,
        "estimate_status": "VALID",
        "method": "calibrated-fusion-v1",
        "value_m": 12.0,
        "confidence": 0.9,
        "lower_95_m": 11.0,
        "upper_95_m": 13.0,
        "observed_at": datetime.fromtimestamp(clock.utc_s, timezone.utc),
        "source_ids": ["lidar-1", "token-1"],
        "measurement_ids": measurements or [f"measurement-{cursor}"],
        "calibration_id": "calibration-1",
        "quality_flags": [],
    }
    values.update(changes)
    return AlarmEvidenceRequest(**values)


def service(tmp_path, clock):
    return AlarmService(
        AlarmRepository(str(tmp_path / "alarms.db")),
        "gateway-berlin-1",
        boot_id=BOOT_ID,
        monotonic=clock.monotonic,
        utc_clock=clock.utc,
    )


def test_authoritative_flow_is_durable_auditable_and_scheduler_driven(tmp_path):
    clock = FakeClock()
    alarms = service(tmp_path, clock)

    created = alarms.store_policy(policy(), ADMIN)
    assert created["authority"] == "GATEWAY_AUTHORITATIVE"
    assert created["condition"] == "NORMAL"
    assert created["state_revision"] == 1

    pending = alarms.ingest(evidence(clock), DEVICE)
    assert pending["condition"] == "PENDING_TRIGGER"
    assert pending["trigger_deadline_at"] is not None

    clock.advance_ms(199)
    assert alarms.tick_all() == 0
    clock.advance_ms(1)
    assert alarms.tick_all() == 1
    active = alarms.get_runtime(POLICY_ID, "asset-1", DEVICE)
    assert active["condition"] == "ACTIVE"
    assert active["attention"] == "UNACKNOWLEDGED"

    acknowledged = alarms.acknowledge(POLICY_ID, "asset-1", DEVICE)
    assert acknowledged["attention"] == "ACKNOWLEDGED"
    snoozed = alarms.snooze(POLICY_ID, "asset-1", 1_000, DEVICE)
    assert snoozed["attention"] == "SNOOZED"
    assert snoozed["snoozed_until"] is not None

    clock.advance_ms(1_000)
    assert alarms.tick_all() == 1
    expired = alarms.get_runtime(POLICY_ID, "asset-1", DEVICE)
    assert expired["condition"] == "ACTIVE"
    assert expired["attention"] == "UNACKNOWLEDGED"

    events = alarms.list_events(POLICY_ID, "asset-1", DEVICE, 0, 100)
    assert [event["event_type"] for event in events] == [
        "POLICY_ENABLED",
        "PENDING_STARTED",
        "TRIGGERED",
        "ACKNOWLEDGED",
        "SNOOZED",
        "SNOOZE_EXPIRED",
    ]
    assert events[2]["actor"] is None
    assert events[3]["actor"] == {
        "actor_id": "asset-1",
        "session_id": "device-session",
        "action": "ACKNOWLEDGE",
    }
    assert events[0]["integrity"] == {
        "verified": False,
        "algorithm": None,
        "key_id": None,
    }
    assert events[-1]["state_revision"] == expired["state_revision"]

    contracts = Path(__file__).parents[2] / "docs" / "contracts"
    event_schema = json.loads((contracts / "alarm-event.schema.json").read_text())
    runtime_schema = json.loads((contracts / "alarm-runtime.schema.json").read_text())
    event_validator = Draft202012Validator(event_schema, format_checker=FormatChecker())
    runtime_validator = Draft202012Validator(runtime_schema, format_checker=FormatChecker())
    for event in events:
        event_validator.validate(event)
    runtime_validator.validate(expired)

    # Events and delivery work are one transaction; all six are claimable.
    outbox = alarms.repository.claim_outbox(int(clock.utc_s * 1000), 30_000)
    assert [record.payload["event_type"] for record in outbox] == [
        event["event_type"] for event in events
    ]


def test_cursor_replay_scope_and_future_timestamp_fail_closed(tmp_path):
    clock = FakeClock()
    alarms = service(tmp_path, clock)
    alarms.store_policy(policy(dwell_ms=0), ADMIN)
    alarms.ingest(evidence(clock, "cursor-1"), DEVICE)
    alarms.ingest(evidence(clock, "cursor-2"), DEVICE)

    with pytest.raises(AlarmConflict, match="already consumed"):
        alarms.ingest(evidence(clock, "cursor-1", ["measurement-replay"]), DEVICE)
    with pytest.raises(AlarmAuthorizationError):
        alarms.get_runtime(POLICY_ID, "asset-1", OTHER_DEVICE)
    with pytest.raises(AlarmInputError, match="future"):
        alarms.ingest(
            evidence(
                clock,
                "cursor-future",
                observed_at=datetime.fromtimestamp(clock.utc_s + 6, timezone.utc),
            ),
            DEVICE,
        )


def test_policy_revision_is_immutable_asset_bound_and_emits_update(tmp_path):
    clock = FakeClock()
    alarms = service(tmp_path, clock)
    first = alarms.store_policy(policy(), ADMIN)
    updated = alarms.store_policy(policy(2, threshold_m=20.0), ADMIN)
    assert updated["policy_revision"] == 2
    assert updated["policy_snapshot_hash"] != first["policy_snapshot_hash"]

    events = alarms.list_events(POLICY_ID, "asset-1", ADMIN, 0, 100)
    policy_event = next(event for event in events if event["event_type"] == "POLICY_UPDATED")
    assert policy_event["previous_policy_revision"] == 1
    assert policy_event["actor"]["action"] == "UPDATE"

    with pytest.raises(AlarmConflict, match="advance"):
        alarms.store_policy(policy(2), ADMIN)
    with pytest.raises(AlarmConflict, match="reassigned"):
        alarms.store_policy(policy(3, asset_id="asset-2"), ADMIN)


def test_alarm_models_reject_partial_valid_evidence_and_extra_fields():
    clock = FakeClock()
    with pytest.raises(ValidationError):
        evidence(clock, confidence=None)
    with pytest.raises(ValidationError):
        evidence(clock, unexpected="not accepted")
    with pytest.raises(ValidationError):
        policy(metric="RANGE_FROM_ANCHOR", reference_id=None)
