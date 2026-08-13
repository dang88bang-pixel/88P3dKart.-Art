"""Transactional SQLite persistence for the authoritative alarm engine.

Every accepted reduction checkpoints runtime, input cursor, immutable events, and
outbox deliveries in one ``BEGIN IMMEDIATE`` transaction. The outbox is claimed
and acknowledged separately so a dispatcher can provide at-least-once delivery.
"""
from __future__ import annotations

import hashlib
import json
import sqlite3
from contextlib import contextmanager
from dataclasses import asdict, dataclass, replace
from pathlib import Path
from typing import Any, Iterator, Optional, Sequence

from alarm_engine import AlarmRuntime, Attention, Condition, Reduction


class RevisionConflict(RuntimeError):
    """The caller evaluated an obsolete runtime revision."""


class PersistenceInvariantError(ValueError):
    """A reduction or event record violates a repository invariant."""


@dataclass(frozen=True)
class EventRecord:
    event_id: str
    deduplication_key: str
    payload: dict[str, Any]


@dataclass(frozen=True)
class InputCursor:
    source_id: str
    cursor: str


@dataclass(frozen=True)
class LoadedRuntime:
    runtime: AlarmRuntime
    checkpoint_revision: int
    evidence: Optional[dict[str, Any]]
    clock_discontinuity: bool


@dataclass(frozen=True)
class OutboxRecord:
    event_id: str
    payload: dict[str, Any]
    attempts: int


class AlarmRepository:
    def __init__(self, db_path: str):
        self.db_path = db_path
        Path(db_path).parent.mkdir(parents=True, exist_ok=True)
        self._initialize()

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.db_path, timeout=10.0)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA journal_mode=WAL")
        connection.execute("PRAGMA synchronous=FULL")
        connection.execute("PRAGMA foreign_keys=ON")
        return connection

    @contextmanager
    def _transaction(self) -> Iterator[sqlite3.Connection]:
        connection = self._connect()
        try:
            connection.execute("BEGIN IMMEDIATE")
            yield connection
            connection.commit()
        except BaseException:
            connection.rollback()
            raise
        finally:
            connection.close()

    def _initialize(self) -> None:
        with self._transaction() as connection:
            connection.executescript(
                """
                CREATE TABLE IF NOT EXISTS alarm_policy_revisions (
                    policy_id TEXT NOT NULL,
                    revision INTEGER NOT NULL CHECK (revision >= 1),
                    snapshot_hash TEXT NOT NULL,
                    document_json TEXT NOT NULL,
                    created_utc_ms INTEGER NOT NULL,
                    PRIMARY KEY (policy_id, revision),
                    UNIQUE (policy_id, snapshot_hash)
                );

                CREATE TABLE IF NOT EXISTS active_alarm_policies (
                    policy_id TEXT PRIMARY KEY,
                    revision INTEGER NOT NULL,
                    FOREIGN KEY (policy_id, revision)
                        REFERENCES alarm_policy_revisions(policy_id, revision)
                );

                CREATE TABLE IF NOT EXISTS alarm_runtime (
                    policy_id TEXT NOT NULL,
                    asset_id TEXT NOT NULL,
                    state_revision INTEGER NOT NULL CHECK (state_revision >= 0),
                    checkpoint_revision INTEGER NOT NULL CHECK (checkpoint_revision >= 1),
                    runtime_json TEXT NOT NULL,
                    evidence_json TEXT,
                    boot_id TEXT NOT NULL,
                    checkpoint_monotonic_ms INTEGER NOT NULL,
                    checkpoint_utc_ms INTEGER NOT NULL,
                    PRIMARY KEY (policy_id, asset_id)
                );

                CREATE TABLE IF NOT EXISTS alarm_input_cursors (
                    policy_id TEXT NOT NULL,
                    asset_id TEXT NOT NULL,
                    source_id TEXT NOT NULL,
                    cursor TEXT NOT NULL,
                    state_revision INTEGER NOT NULL,
                    PRIMARY KEY (policy_id, asset_id, source_id)
                );

                CREATE TABLE IF NOT EXISTS alarm_events (
                    event_id TEXT PRIMARY KEY,
                    deduplication_key TEXT NOT NULL UNIQUE,
                    policy_id TEXT NOT NULL,
                    asset_id TEXT NOT NULL,
                    state_revision INTEGER NOT NULL CHECK (state_revision >= 1),
                    payload_json TEXT NOT NULL,
                    created_utc_ms INTEGER NOT NULL
                );

                CREATE TABLE IF NOT EXISTS alarm_outbox (
                    event_id TEXT PRIMARY KEY,
                    status TEXT NOT NULL CHECK (status IN ('PENDING', 'IN_FLIGHT')),
                    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
                    available_utc_ms INTEGER NOT NULL,
                    lease_until_utc_ms INTEGER,
                    last_error TEXT,
                    FOREIGN KEY (event_id) REFERENCES alarm_events(event_id)
                );

                CREATE INDEX IF NOT EXISTS idx_alarm_outbox_due
                    ON alarm_outbox(status, available_utc_ms, lease_until_utc_ms);
                """
            )

    def store_policy_revision(
        self,
        policy_id: str,
        revision: int,
        document: dict[str, Any],
        utc_ms: int,
        activate: bool = False,
    ) -> str:
        if revision < 1:
            raise PersistenceInvariantError("policy revision must be positive")
        encoded = _canonical_json(document)
        snapshot_hash = hashlib.sha256(encoded.encode("utf-8")).hexdigest()
        with self._transaction() as connection:
            existing = connection.execute(
                """SELECT snapshot_hash FROM alarm_policy_revisions
                   WHERE policy_id=? AND revision=?""",
                (policy_id, revision),
            ).fetchone()
            if existing is not None and existing["snapshot_hash"] != snapshot_hash:
                raise PersistenceInvariantError(
                    "a policy revision is immutable and already has different content"
                )
            connection.execute(
                """INSERT OR IGNORE INTO alarm_policy_revisions
                   (policy_id, revision, snapshot_hash, document_json, created_utc_ms)
                   VALUES (?, ?, ?, ?, ?)""",
                (policy_id, revision, snapshot_hash, encoded, utc_ms),
            )
            if activate:
                current = connection.execute(
                    "SELECT revision FROM active_alarm_policies WHERE policy_id=?",
                    (policy_id,),
                ).fetchone()
                if current is not None and current["revision"] > revision:
                    raise PersistenceInvariantError("policy activation cannot move backwards")
                connection.execute(
                    """INSERT INTO active_alarm_policies(policy_id, revision)
                       VALUES (?, ?)
                       ON CONFLICT(policy_id) DO UPDATE SET revision=excluded.revision""",
                    (policy_id, revision),
                )
        return snapshot_hash

    def apply_reduction(
        self,
        policy_id: str,
        asset_id: str,
        expected_state_revision: int,
        expected_checkpoint_revision: int,
        reduction: Reduction,
        events: Sequence[EventRecord],
        cursor: Optional[InputCursor],
        boot_id: str,
        monotonic_ms: int,
        utc_ms: int,
        evidence: Optional[dict[str, Any]] = None,
    ) -> None:
        """Atomically persist one accepted reducer result and all emitted events.

        ``cursor`` is required for a newly ingested measurement and must be ``None``
        for a scheduler tick that re-evaluates the already-checkpointed evidence.
        """
        if len(events) != len(reduction.transitions):
            raise PersistenceInvariantError("each transition requires exactly one event")
        if reduction.runtime.state_revision != expected_state_revision + len(events):
            raise PersistenceInvariantError("state revision does not match transitions")
        for transition, event in zip(reduction.transitions, events):
            payload_revision = event.payload.get("state_revision")
            if transition.current.state_revision <= expected_state_revision:
                raise PersistenceInvariantError("transition revisions must advance")
            if payload_revision != transition.current.state_revision:
                raise PersistenceInvariantError("event state_revision does not match transition")

        runtime_json = _canonical_json(_runtime_to_dict(reduction.runtime))
        evidence_json = None if evidence is None else _canonical_json(evidence)
        with self._transaction() as connection:
            row = connection.execute(
                """SELECT state_revision, checkpoint_revision FROM alarm_runtime
                   WHERE policy_id=? AND asset_id=?""",
                (policy_id, asset_id),
            ).fetchone()
            actual_state_revision = 0 if row is None else row["state_revision"]
            actual_checkpoint_revision = 0 if row is None else row["checkpoint_revision"]
            if (
                actual_state_revision != expected_state_revision
                or actual_checkpoint_revision != expected_checkpoint_revision
            ):
                raise RevisionConflict(
                    "expected state/checkpoint revisions "
                    f"{expected_state_revision}/{expected_checkpoint_revision}, found "
                    f"{actual_state_revision}/{actual_checkpoint_revision}"
                )

            if cursor is not None:
                existing_cursor = connection.execute(
                    """SELECT cursor FROM alarm_input_cursors
                       WHERE policy_id=? AND asset_id=? AND source_id=?""",
                    (policy_id, asset_id, cursor.source_id),
                ).fetchone()
                if existing_cursor is not None and existing_cursor["cursor"] == cursor.cursor:
                    raise PersistenceInvariantError("input cursor was already consumed")

            connection.execute(
                """INSERT INTO alarm_runtime
                   (policy_id, asset_id, state_revision, checkpoint_revision,
                    runtime_json, evidence_json, boot_id, checkpoint_monotonic_ms,
                    checkpoint_utc_ms)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                   ON CONFLICT(policy_id, asset_id) DO UPDATE SET
                       state_revision=excluded.state_revision,
                       checkpoint_revision=excluded.checkpoint_revision,
                       runtime_json=excluded.runtime_json,
                       evidence_json=COALESCE(excluded.evidence_json, alarm_runtime.evidence_json),
                       boot_id=excluded.boot_id,
                       checkpoint_monotonic_ms=excluded.checkpoint_monotonic_ms,
                       checkpoint_utc_ms=excluded.checkpoint_utc_ms""",
                (
                    policy_id,
                    asset_id,
                    reduction.runtime.state_revision,
                    expected_checkpoint_revision + 1,
                    runtime_json,
                    evidence_json,
                    boot_id,
                    monotonic_ms,
                    utc_ms,
                ),
            )
            if cursor is not None:
                connection.execute(
                    """INSERT INTO alarm_input_cursors
                       (policy_id, asset_id, source_id, cursor, state_revision)
                       VALUES (?, ?, ?, ?, ?)
                       ON CONFLICT(policy_id, asset_id, source_id) DO UPDATE SET
                           cursor=excluded.cursor,
                           state_revision=excluded.state_revision""",
                    (
                        policy_id,
                        asset_id,
                        cursor.source_id,
                        cursor.cursor,
                        reduction.runtime.state_revision,
                    ),
                )
            for transition, event in zip(reduction.transitions, events):
                payload_json = _canonical_json(event.payload)
                connection.execute(
                    """INSERT INTO alarm_events
                       (event_id, deduplication_key, policy_id, asset_id,
                        state_revision, payload_json, created_utc_ms)
                       VALUES (?, ?, ?, ?, ?, ?, ?)""",
                    (
                        event.event_id,
                        event.deduplication_key,
                        policy_id,
                        asset_id,
                        transition.current.state_revision,
                        payload_json,
                        utc_ms,
                    ),
                )
                connection.execute(
                    """INSERT INTO alarm_outbox
                       (event_id, status, attempts, available_utc_ms)
                       VALUES (?, 'PENDING', 0, ?)""",
                    (event.event_id, utc_ms),
                )

    def load_runtime(
        self,
        policy_id: str,
        asset_id: str,
        boot_id: str,
        monotonic_ms: int,
        utc_ms: int,
    ) -> LoadedRuntime:
        connection = self._connect()
        try:
            row = connection.execute(
                """SELECT runtime_json, evidence_json, checkpoint_revision, boot_id,
                          checkpoint_monotonic_ms, checkpoint_utc_ms
                   FROM alarm_runtime WHERE policy_id=? AND asset_id=?""",
                (policy_id, asset_id),
            ).fetchone()
        finally:
            connection.close()
        if row is None:
            return LoadedRuntime(AlarmRuntime(), 0, None, False)

        runtime = _runtime_from_dict(json.loads(row["runtime_json"]))
        evidence = (
            None if row["evidence_json"] is None else json.loads(row["evidence_json"])
        )
        if row["boot_id"] == boot_id:
            return LoadedRuntime(runtime, row["checkpoint_revision"], evidence, False)

        # Across reboot, preserve elapsed dwell using wall time, while surfacing
        # rollback/invalid clocks so the caller can publish degraded health.
        wall_delta = utc_ms - row["checkpoint_utc_ms"]
        discontinuity = wall_delta < 0
        offline_elapsed = max(0, wall_delta)
        offset = monotonic_ms - row["checkpoint_monotonic_ms"] - offline_elapsed

        def rebase(value: Optional[int]) -> Optional[int]:
            return None if value is None else value + offset

        return LoadedRuntime(
            replace(
                runtime,
                condition_since_ms=rebase(runtime.condition_since_ms) or 0,
                attention_since_ms=rebase(runtime.attention_since_ms) or 0,
                invalid_since_ms=rebase(runtime.invalid_since_ms),
                recovery_since_ms=rebase(runtime.recovery_since_ms),
                snoozed_until_ms=rebase(runtime.snoozed_until_ms),
            ),
            row["checkpoint_revision"],
            evidence,
            discontinuity,
        )

    def claim_outbox(
        self,
        now_utc_ms: int,
        lease_ms: int,
        limit: int = 100,
    ) -> list[OutboxRecord]:
        if lease_ms <= 0 or limit <= 0:
            raise ValueError("lease_ms and limit must be positive")
        with self._transaction() as connection:
            rows = connection.execute(
                """SELECT o.event_id, e.payload_json, o.attempts
                   FROM alarm_outbox o
                   JOIN alarm_events e ON e.event_id=o.event_id
                   WHERE (o.status='PENDING' AND o.available_utc_ms <= ?)
                      OR (o.status='IN_FLIGHT' AND o.lease_until_utc_ms <= ?)
                   ORDER BY o.available_utc_ms, o.event_id
                   LIMIT ?""",
                (now_utc_ms, now_utc_ms, limit),
            ).fetchall()
            event_ids = [row["event_id"] for row in rows]
            if event_ids:
                placeholders = ",".join("?" for _ in event_ids)
                connection.execute(
                    f"""UPDATE alarm_outbox
                        SET status='IN_FLIGHT', attempts=attempts+1,
                            lease_until_utc_ms=?
                        WHERE event_id IN ({placeholders})""",
                    (now_utc_ms + lease_ms, *event_ids),
                )
            return [
                OutboxRecord(
                    event_id=row["event_id"],
                    payload=json.loads(row["payload_json"]),
                    attempts=row["attempts"] + 1,
                )
                for row in rows
            ]

    def acknowledge_delivery(self, event_id: str) -> bool:
        with self._transaction() as connection:
            return (
                connection.execute(
                    "DELETE FROM alarm_outbox WHERE event_id=? AND status='IN_FLIGHT'",
                    (event_id,),
                ).rowcount
                == 1
            )

    def retry_delivery(
        self,
        event_id: str,
        available_utc_ms: int,
        error: str,
    ) -> bool:
        with self._transaction() as connection:
            return (
                connection.execute(
                    """UPDATE alarm_outbox
                       SET status='PENDING', available_utc_ms=?, lease_until_utc_ms=NULL,
                           last_error=?
                       WHERE event_id=? AND status='IN_FLIGHT'""",
                    (available_utc_ms, error[:1000], event_id),
                ).rowcount
                == 1
            )

    def event_count(self) -> int:
        connection = self._connect()
        try:
            return connection.execute("SELECT COUNT(*) FROM alarm_events").fetchone()[0]
        finally:
            connection.close()


def _runtime_to_dict(runtime: AlarmRuntime) -> dict[str, Any]:
    result = asdict(runtime)
    result["condition"] = runtime.condition.value
    result["attention"] = runtime.attention.value
    return result


def _runtime_from_dict(document: dict[str, Any]) -> AlarmRuntime:
    return AlarmRuntime(
        condition=Condition(document["condition"]),
        attention=Attention(document["attention"]),
        condition_since_ms=document["condition_since_ms"],
        attention_since_ms=document["attention_since_ms"],
        invalid_since_ms=document["invalid_since_ms"],
        recovery_since_ms=document["recovery_since_ms"],
        snoozed_until_ms=document["snoozed_until_ms"],
        state_revision=document["state_revision"],
        last_measurement_id=document["last_measurement_id"],
    )


def _canonical_json(document: Any) -> str:
    try:
        return json.dumps(
            document,
            sort_keys=True,
            separators=(",", ":"),
            ensure_ascii=False,
            allow_nan=False,
        )
    except (TypeError, ValueError) as exc:
        raise PersistenceInvariantError("document is not canonical JSON") from exc
