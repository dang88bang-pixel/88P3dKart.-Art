import time

from database import LocalVectorStore


def test_save_and_retrieve(tmp_path):
    db = LocalVectorStore(db_path=str(tmp_path / "test.db"))
    db.save_transform("CT45P-01", (1.0, 2.0, 3.0), (0.1, 0.3), {"mode": "FULL"})
    rows = db.get_latest("CT45P-01")
    assert len(rows) == 1
    assert rows[0]["pos_x"] == 1.0


def test_retention_deletes_old(tmp_path):
    db = LocalVectorStore(db_path=str(tmp_path / "test.db"))
    with db._get_conn() as conn:
        conn.execute(
            "INSERT INTO spatial_memory "
            "(id, device_id, timestamp, pos_x, pos_y, pos_z, cov_lidar, cov_mmwave, metadata) "
            "VALUES ('old', 'CT45P-01', ?, 0,0,0,0,0,'{}')",
            (time.time() - 30 * 24 * 3600,),
        )
        conn.execute(
            "UPDATE retention_policy SET max_age_days = 7 WHERE id = 1"
        )
    deleted = db.enforce_retention()
    assert deleted >= 1


def test_merged_map_roundtrip(tmp_path):
    db = LocalVectorStore(db_path=str(tmp_path / "test.db"))
    db.save_merged_map("merged", [[0, 0, 0], [1, 1, 1]])
    assert db.get_merged_map("merged") == [[0, 0, 0], [1, 1, 1]]


def test_purge_expired_geo_respects_ttl(tmp_path):
    """Fixes mit abgelaufener TTL müssen verschwinden, TTL-lose bleiben.

    Hintergrund: Google erlaubt max. 30 Tage Zwischenspeicherung. Ohne
    Purge würde der Agent diese Grenze verletzen (docs/LICENSES.md).
    """
    db = LocalVectorStore(db_path=str(tmp_path / "test.db"))

    # Abgelaufen: TTL 30 Tage, aber vor 31 Tagen gespeichert
    db.save_geo_fix(
        lat=52.5, lon=13.4, accuracy_m=50.0, source="google",
        license="proprietary", quality=0.7, ttl_days=30,
    )
    with db._get_conn() as conn:
        conn.execute("UPDATE geo_fixes SET expires_at = ?", (time.time() - 86400,))

    # Ohne TTL (eigene Messung) — darf nicht gelöscht werden
    db.save_geo_fix(
        lat=52.6, lon=13.5, accuracy_m=8.0, source="manual",
        license="n/a", quality=0.9, ttl_days=None,
    )

    assert db.purge_expired_geo() == 1
    remaining = db.get_latest_geo_fix()
    assert remaining is not None
    assert remaining["source"] == "manual"
    # Zweiter Lauf ist idempotent
    assert db.purge_expired_geo() == 0


def test_retention_also_purges_merged_maps(tmp_path):
    """Befund C2: merged_maps hatte keine Retention."""
    db = LocalVectorStore(db_path=str(tmp_path / "test.db"))
    with db._get_conn() as conn:
        conn.execute(
            "INSERT INTO merged_maps (id, timestamp, points) "
            "VALUES ('alt', ?, '[]')",
            (time.time() - 30 * 24 * 3600,),
        )
        conn.execute("UPDATE retention_policy SET max_age_days = 7 WHERE id = 1")
        before = conn.execute("SELECT COUNT(*) FROM merged_maps").fetchone()[0]
    assert before == 1

    db.enforce_retention()
    with db._get_conn() as conn:
        after = conn.execute("SELECT COUNT(*) FROM merged_maps").fetchone()[0]
    assert after == 0
