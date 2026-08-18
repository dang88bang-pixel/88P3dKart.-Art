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
            # --- Georeferenzierung (v4.5.0-Geo) ---
            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS geo_fixes (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    timestamp REAL NOT NULL,
                    lat REAL NOT NULL,
                    lon REAL NOT NULL,
                    accuracy_m REAL NOT NULL,
                    source TEXT NOT NULL,
                    license TEXT NOT NULL,
                    quality REAL NOT NULL,
                    expires_at REAL,
                    scan_id TEXT
                )
                """
            )
            conn.execute(
                "CREATE INDEX IF NOT EXISTS idx_geo_expires ON geo_fixes(expires_at)"
            )
            conn.execute(
                "CREATE INDEX IF NOT EXISTS idx_geo_time ON geo_fixes(timestamp DESC)"
            )

            # --- Checkpoints für Crash-Recovery (docs/SIGNAL_POSITIONING.md §5) ---
            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS checkpoints (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    timestamp REAL NOT NULL,
                    point_count INTEGER NOT NULL,
                    last_record_id TEXT,
                    checksum TEXT NOT NULL,
                    metadata TEXT NOT NULL DEFAULT '{}'
                )
                """
            )
            conn.execute(
                "CREATE INDEX IF NOT EXISTS idx_checkpoints_time ON checkpoints(timestamp DESC)"
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

    # ─── Checkpointing & Integrität ───────────────────────────────

    def create_checkpoint(self, metadata: dict | None = None) -> dict:
        """Erstellt einen Checkpoint: Zählerstand + SHA-256 über einen
        konsistenten DB-Snapshot (sqlite3-Backup-API, WAL-sicher)."""
        import json as _json

        with self._get_conn() as conn:
            count = conn.execute("SELECT COUNT(*) FROM spatial_memory").fetchone()[0]
            last = conn.execute(
                "SELECT id FROM spatial_memory ORDER BY timestamp DESC LIMIT 1"
            ).fetchone()
            checksum = self._db_checksum()
            timestamp = time.time()
            conn.execute(
                """INSERT INTO checkpoints (timestamp, point_count, last_record_id, checksum, metadata)
                   VALUES (?, ?, ?, ?, ?)""",
                (timestamp, count, last["id"] if last else None, checksum, _json.dumps(metadata or {})),
            )
            conn.execute(
                "DELETE FROM checkpoints WHERE id NOT IN "
                "(SELECT id FROM checkpoints ORDER BY timestamp DESC LIMIT 10)"
            )
        return {
            "timestamp": timestamp,
            "point_count": count,
            "last_record_id": last["id"] if last else None,
            "checksum": checksum,
        }

    def latest_checkpoint(self) -> dict | None:
        with self._get_conn() as conn:
            row = conn.execute(
                "SELECT * FROM checkpoints ORDER BY timestamp DESC LIMIT 1"
            ).fetchone()
        if row is None:
            return None
        return {
            "id": row["id"],
            "timestamp": row["timestamp"],
            "point_count": row["point_count"],
            "last_record_id": row["last_record_id"],
            "checksum": row["checksum"],
            "metadata": row["metadata"],
        }

    def list_checkpoints(self, limit: int = 10) -> list:
        with self._get_conn() as conn:
            rows = conn.execute(
                "SELECT * FROM checkpoints ORDER BY timestamp DESC LIMIT ?", (limit,)
            ).fetchall()
        return [
            {
                "id": r["id"],
                "timestamp": r["timestamp"],
                "point_count": r["point_count"],
                "last_record_id": r["last_record_id"],
                "checksum": r["checksum"],
            }
            for r in rows
        ]

    def verify_integrity(self) -> dict:
        """PRAGMA integrity_check + Vergleich mit dem letzten Checkpoint."""
        with self._get_conn() as conn:
            row = conn.execute("PRAGMA integrity_check").fetchone()
        ok = row is not None and row[0] == "ok"
        latest = self.latest_checkpoint()
        current = self._db_checksum()
        return {
            "integrity_ok": ok,
            "latest_checkpoint": latest["timestamp"] if latest else None,
            "checksum": current,
            "matches_checkpoint": bool(latest and latest["checksum"] == current),
        }

    def _db_checksum(self) -> str:
        """SHA-256 über die Datentabellen (ohne checkpoints — der Checkpoint
        speichert seine eigene Checksumme und darf sie nicht verändern)."""
        import hashlib

        tables = ("spatial_memory", "merged_maps", "geo_fixes")
        digest = hashlib.sha256()
        with self._get_conn() as conn:
            for table in tables:
                for row in conn.execute(f"SELECT * FROM {table} ORDER BY rowid"):
                    digest.update(table.encode("utf-8"))
                    digest.update(repr(tuple(row)).encode("utf-8"))
        return digest.hexdigest()

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
            # Befund C2 des Prüfberichts: merged_maps hatte keine Retention.
            # Georeferenzierte Merge-Ergebnisse dürfen lizenzpflichtige
            # Koordinaten nicht unbegrenzt konservieren.
            deleted += conn.execute(
                "DELETE FROM merged_maps WHERE timestamp < ?", (cutoff,)
            ).rowcount
            return deleted

    # ─── Georeferenzierung ─────────────────────────────────────
    def save_geo_fix(
        self,
        lat: float,
        lon: float,
        accuracy_m: float,
        source: str,
        license: str,
        quality: float,
        ttl_days: Optional[int] = None,
        scan_id: Optional[str] = None,
    ) -> None:
        """Speichert einen Fix; ``ttl_days`` erzwingt ein Ablaufdatum.

        Google erlaubt laut ToS max. 30 Tage Zwischenspeicherung — der Wert
        kommt aus dem Provider und wird hier in ``expires_at`` materialisiert.
        """
        now = time.time()
        expires_at = now + ttl_days * 86400 if ttl_days else None
        with self._get_conn() as conn:
            conn.execute(
                """
                INSERT INTO geo_fixes
                (timestamp, lat, lon, accuracy_m, source, license, quality,
                 expires_at, scan_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    now,
                    lat,
                    lon,
                    accuracy_m,
                    source,
                    license,
                    quality,
                    expires_at,
                    scan_id,
                ),
            )

    def purge_expired_geo(self) -> int:
        """Löscht lizenzpflichtig ablaufende Fixes (Google-ToS: max. 30 Tage)."""
        now = time.time()
        with self._get_conn() as conn:
            return conn.execute(
                "DELETE FROM geo_fixes WHERE expires_at IS NOT NULL AND expires_at < ?",
                (now,),
            ).rowcount

    def get_latest_geo_fix(self) -> Optional[Dict[str, Any]]:
        with self._get_conn() as conn:
            row = conn.execute(
                "SELECT * FROM geo_fixes ORDER BY timestamp DESC LIMIT 1"
            ).fetchone()
            return dict(row) if row else None

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

    def known_devices(self) -> List[str]:
        """Alle Geräte mit gespeicherten Positionsdaten."""
        with self._get_conn() as conn:
            rows = conn.execute(
                "SELECT DISTINCT device_id FROM spatial_memory ORDER BY device_id"
            ).fetchall()
        return [r["device_id"] for r in rows]

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
