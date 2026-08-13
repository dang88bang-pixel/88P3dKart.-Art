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
