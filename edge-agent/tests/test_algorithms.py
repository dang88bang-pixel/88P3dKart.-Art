"""Mesh-Konsens-Synchronisation + Datenschutz-Filter + Checkpoints."""
import json
import random

import pytest

from database import LocalVectorStore
from mesh_sync import consensus_sync, max_time_disagreement, sync_to_tolerance
from privacy import (
    PersistenceFilter,
    anonymize_identifier,
    granularize,
    strip_metadata,
)


# ─── Mesh-Sync ──────────────────────────────────────────────

def test_consensus_preserves_mean():
    times = [10.0, 20.0, 30.0, 40.0]
    before = sum(times) / len(times)
    out = consensus_sync(times, 50, alpha=0.3, rng=random.Random(1))
    after = sum(out) / len(out)
    assert after == pytest.approx(before, abs=1e-6)


def test_consensus_converges_to_tolerance():
    report = sync_to_tolerance(
        [0.0, 5.0, -3.0, 8.0], tolerance=0.01, max_rounds=500, rng=random.Random(2)
    )
    assert report["converged"] is True
    assert report["disagreement_after"] <= 0.01
    assert report["disagreement_before"] >= report["disagreement_after"]


def test_consensus_single_node_and_empty():
    assert consensus_sync([7.0], 10) == [7.0]
    assert consensus_sync([], 10) == []
    assert max_time_disagreement([]) == 0.0


# ─── Privacy-Filter (erzwingende Nicht-Persistenz) ──────────

def test_persistence_filter_removes_live_only_objects():
    filt = PersistenceFilter()
    objects = [
        {"kind": "wall", "position": [0, 0, 1]},
        {"kind": "floor", "position": [1, 1, 0]},
        {"kind": "person", "position": [2, 2, 1]},
        {"kind": "animal", "position": [3, 3, 0]},
        {"kind": "moving_person", "position": [4, 4, 1]},
        {"kind": "device", "position": [5, 5, 1]},
    ]
    kept, removed = filt.filter_objects(objects)
    assert removed == 3
    kinds = {o["kind"] for o in kept}
    assert kinds == {"wall", "floor", "device"}
    # Audit zählt korrekt, ohne Objektdaten preiszugeben
    audit = filt.audit(objects)
    assert audit["live_only_removed"] == 3
    assert audit["persisted_kinds"]["wall"] == 1


def test_device_anonymization_strips_identifiers():
    filt = PersistenceFilter()
    device = {
        "id": "AA:BB:CC:DD:EE:FF",
        "mac": "AA:BB:CC:DD:EE:FF",
        "type": "ble",
        "metadata": {"mac": "x", "uuid": "y", "user_id": "z", "friendly": "ok"},
    }
    out = filt.filter_device(device)
    assert out["id"].startswith("ANON_") and out["id"] != device["id"]
    assert out["mac"].startswith("ANON_")
    assert "mac" not in out["metadata"] and "uuid" not in out["metadata"]
    assert out["metadata"]["friendly"] == "ok"


def test_anonymization_deterministic_and_granularization():
    assert anonymize_identifier("AA:BB") == anonymize_identifier("AA:BB")
    assert anonymize_identifier("AA:BB") != anonymize_identifier("AA:BC")
    assert granularize(1.234567) == pytest.approx(1.2, abs=1e-9)
    assert granularize(-0.057) == pytest.approx(-0.1, abs=1e-9)


def test_strip_metadata_removes_personal_keys():
    assert strip_metadata({"user_id": 1, "note": "x", "User": "y"}) == {"note": "x"}


def test_pipeline_persons_never_reach_storage(tmp_path):
    """Integration: Pipeline-Ergebnis (inkl. person-Objekte) → Filter → DB
    speichert ausschließlich gefilterte (unpersönliche) Daten."""
    import numpy as np

    from pipeline import DataPipeline

    pipeline = DataPipeline()
    # Raum mit Boden + mittlerem Band (wird heuristisch als 'person' klassifiziert)
    pts = []
    rng = np.random.default_rng(3)
    pts += [[x, y, 0.0] for x, y in zip(rng.uniform(-3, 3, 40), rng.uniform(-3, 3, 40))]
    pts += [[x, y, 2.5] for x, y in zip(rng.uniform(-3, 3, 15), rng.uniform(-3, 3, 15))]
    pts += [[x, y, 1.2] for x, y in zip(rng.uniform(-2, 2, 15), rng.uniform(-2, 2, 15))]
    result = pipeline.run(np.asarray(pts, dtype=float).flatten().tolist())
    kinds = result.get("objects", [])
    assert any(k == "person" for k in kinds), "Pipeline muss Person-Objekt liefern"

    filt = PersistenceFilter()
    objects_full = [{"kind": k} for k in kinds]
    kept, removed = filt.filter_objects(objects_full)
    assert removed >= 1
    assert all(o["kind"] not in ("person", "animal") for o in kept)

    # Persistenz: nur gefilterte Daten landen in der DB
    store = LocalVectorStore(db_path=str(tmp_path / "pipeline.db"))
    store.save_transform("dev-1", (1.0, 2.0, 3.0), (0.1, 0.1), {"kinds": [o["kind"] for o in kept]})
    stored = store.get_latest("dev-1", 10)
    assert stored and "person" not in json.dumps(stored)


# ─── Checkpoints ────────────────────────────────────────────

def test_checkpoint_and_integrity(tmp_path):
    store = LocalVectorStore(db_path=str(tmp_path / "agent.db"))
    store.save_transform("dev-1", (1, 1, 1), (0.1, 0.1), {})
    cp = store.create_checkpoint(metadata={"reason": "test"})
    assert cp["point_count"] == 1
    assert len(cp["checksum"]) == 64
    assert store.latest_checkpoint()["checksum"] == cp["checksum"]
    verify = store.verify_integrity()
    assert verify["integrity_ok"] is True
    assert verify["matches_checkpoint"] is True
    # Nach weiterem Write ändert sich die Checksumme → kein Match mehr
    store.save_transform("dev-1", (2, 2, 2), (0.1, 0.1), {})
    verify2 = store.verify_integrity()
    assert verify2["integrity_ok"] is True
    assert verify2["matches_checkpoint"] is False
    assert len(store.list_checkpoints()) == 1


def test_checkpoint_without_data(tmp_path):
    store = LocalVectorStore(db_path=str(tmp_path / "empty.db"))
    assert store.latest_checkpoint() is None
    cp = store.create_checkpoint()
    assert cp["point_count"] == 0
    assert store.verify_integrity()["integrity_ok"] is True
