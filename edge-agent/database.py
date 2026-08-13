"""SQLite-WAL-Persistenz mit Retention Policy."""
import json
import logging
import time
import uuid
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from config import CONFIG

logger = logging.getLogger(__name__)


class LocalVectorStore:
    """SQLite-Datenbank (WAL-Modus) für persistente 3D-Transformationen."""

    def __init__(self, db_path: str = CONFIG.DB_PATH):
        self.db_path = db_path
        Path(db_path).parent.mkdir(parents=True, exist_ok=True)
        self._init_db()

    @contextmanager
    def _get_conn(self):
        conn = __import__("sqlite3").connect(self.db_path, timeout=10.0)
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA synchronous=NORMAL")
        conn.row_factory = __import__("sqlite3").Row
        try:
            yield conn
            conn.commit()
        finally:
            conn.close()

    def _init_db(self) -> None:
        with self._get_conn() as conn:
            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS spatial_memory (
                    id TEXT PRIMARY KEY,
                    device_id TEXT NOT NULL,
                    timestamp REAL NOT NULL,
                    pos_x REAL NOT NULL,
                    pos_y REAL NOT NULL,
                    pos_z REAL NOT NULL,
                    cov_lidar REAL NOT NULL,
                    cov_mmwave REAL NOT NULL,
                    metadata TEXT NOT NULL
                )
                """
            )
            conn.execute(
                "CREATE INDEX IF NOT EXISTS idx_device_time "
                "ON spatial_memory (device_id, timestamp DESC)"
            )
            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS retention_policy (
                    id INTEGER PRIMARY KEY CHECK (id = 1),
                    max_age_days REAL NOT NULL DEFAULT 7,
                    max_records INTEGER NOT NULL DEFAULT 100000
                )
                """
            )
            conn.execute(
                "INSERT OR IGNORE INTO retention_policy (id, max_age_days, max_records) "
                "VALUES (1, ?, ?)",
                (CONFIG.RETENTION_DAYS, CONFIG.MAX_RECORDS),
            )
            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS merged_maps (
                    id TEXT PRIMARY KEY,
                    timestamp REAL NOT NULL,
                    points TEXT NOT NULL
                )
                """
            )

    def save_transform(
        self,
        device_id: str,
        pos: Tuple[float, float, float],
        cov: Tuple[float, float],
        metadata: Dict[str, Any],
    ) -> None:
        with self._get_conn() as conn:
            conn.execute(
                """
                INSERT OR REPLACE INTO spatial_memory
                (id, device_id, timestamp, pos_x, pos_y, pos_z, cov_lidar, cov_mmwave, metadata)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    f"{device_id}_{uuid.uuid4().hex}",
                    device_id,
                    time.time(),
                    pos[0],
                    pos[1],
                    pos[2],
                    cov[0],
                    cov[1],
                    json.dumps(metadata),
                ),
            )

    def enforce_retention(self) -> int:
        with self._get_conn() as conn:
            row = conn.execute(
                "SELECT max_age_days, max_records FROM retention_policy WHERE id=1"
            ).fetchone()
            if not row:
                return 0
            cutoff = time.time() - (row["max_age_days"] * 24 * 3600)
            deleted = conn.execute(
                "DELETE FROM spatial_memory WHERE timestamp < ?", (cutoff,)
            ).rowcount
            deleted += conn.execute(
                """
                DELETE FROM spatial_memory WHERE id IN (
                    SELECT id FROM spatial_memory
                    ORDER BY timestamp DESC
                    LIMIT -1 OFFSET ?
                )
                """,
                (row["max_records"],),
            ).rowcount
            return deleted

    def get_latest(self, device_id: str, limit: int = 100) -> List[Dict[str, Any]]:
        with self._get_conn() as conn:
            cursor = conn.execute(
                """
                SELECT timestamp, pos_x, pos_y, pos_z, cov_lidar, cov_mmwave, metadata
                FROM spatial_memory
                WHERE device_id = ?
                ORDER BY timestamp DESC
                LIMIT ?
                """,
                (device_id, limit),
            )
            return [dict(row) for row in cursor.fetchall()]

    def get_all_points(self, device_id: str, limit: int = 1000) -> List[Tuple[float, float, float]]:
        with self._get_conn() as conn:
            cursor = conn.execute(
                """
                SELECT pos_x, pos_y, pos_z FROM spatial_memory
                WHERE device_id = ?
                ORDER BY timestamp DESC
                LIMIT ?
                """,
                (device_id, limit),
            )
            return [(r["pos_x"], r["pos_y"], r["pos_z"]) for r in cursor.fetchall()]

    def save_merged_map(self, map_id: str, points: List[List[float]]) -> None:
        with self._get_conn() as conn:
            conn.execute(
                "INSERT OR REPLACE INTO merged_maps (id, timestamp, points) VALUES (?, ?, ?)",
                (map_id, time.time(), json.dumps(points)),
            )

    def get_merged_map(self, map_id: str) -> Optional[List[List[float]]]:
        with self._get_conn() as conn:
            row = conn.execute(
                "SELECT points FROM merged_maps WHERE id = ?", (map_id,)
            ).fetchone()
            return json.loads(row["points"]) if row else None
