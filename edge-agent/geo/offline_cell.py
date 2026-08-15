"""Offline-Zellortung über einen lokalen OpenCellID-SQLite-Bestand (Tier 0).

Kein Netzverkehr, damit auch im abgeschotteten Einsatz verfügbar. Die
Datenbank wird nicht mitgeliefert (Grösse + Share-Alike) — siehe
scripts/fetch_geo_data.py und docs/LICENSES.md.

Erwartetes Schema (vom Importskript erzeugt):

    CREATE TABLE cells (
        mcc INTEGER, mnc INTEGER, lac INTEGER, cid INTEGER,
        lat REAL, lon REAL, range_m REAL, samples INTEGER,
        PRIMARY KEY (mcc, mnc, lac, cid)
    );
"""
from __future__ import annotations

import logging
import os
import sqlite3
from typing import List, Optional, Tuple

from config import CONFIG
from models import CellTower, GeoFix, GeolocateRequest

from .base import TIER_OFFLINE, GeoProvider

logger = logging.getLogger(__name__)


class OfflineCellProvider(GeoProvider):
    """Schwerpunktschätzung aus bekannten Zellstandorten."""

    name = "offline_cell"
    tier = TIER_OFFLINE
    license = "CC BY-SA 4.0 (OpenCelliD)"
    attribution = "Zelldaten: OpenCelliD-Mitwirkende (CC BY-SA 4.0)"
    ttl_days = None

    def __init__(self, db_path: Optional[str] = None):
        self.db_path = db_path or CONFIG.GEO_OFFLINE_CELL_DB

    def available(self) -> bool:
        return bool(self.db_path) and os.path.isfile(self.db_path)

    def _lookup(self, cell: CellTower) -> Optional[Tuple[float, float, float]]:
        if None in (
            cell.mobileCountryCode,
            cell.mobileNetworkCode,
            cell.locationAreaCode,
            cell.cellId,
        ):
            return None
        try:
            conn = sqlite3.connect(f"file:{self.db_path}?mode=ro", uri=True, timeout=5.0)
        except sqlite3.Error as exc:
            logger.warning("offline_cell: DB nicht lesbar: %s", exc)
            return None
        try:
            row = conn.execute(
                "SELECT lat, lon, range_m FROM cells "
                "WHERE mcc=? AND mnc=? AND lac=? AND cid=?",
                (
                    cell.mobileCountryCode,
                    cell.mobileNetworkCode,
                    cell.locationAreaCode,
                    cell.cellId,
                ),
            ).fetchone()
        except sqlite3.Error as exc:
            logger.warning("offline_cell: Abfrage fehlgeschlagen: %s", exc)
            return None
        finally:
            conn.close()

        if row is None:
            return None
        return float(row[0]), float(row[1]), float(row[2] or 2000.0)

    async def locate(self, req: GeolocateRequest) -> Optional[GeoFix]:
        if not self.available() or not req.cellTowers:
            return None

        hits: List[Tuple[float, float, float, float]] = []
        for cell in req.cellTowers:
            found = self._lookup(cell)
            if found is None:
                continue
            lat, lon, rng = found
            # Signalstärke als Gewicht: stärkere Zelle ist näher.
            # -50 dBm -> 1.0, -110 dBm -> ~0.1
            if cell.signalStrength is not None:
                w = max(0.1, min(1.0, (cell.signalStrength + 113) / 63.0))
            else:
                w = 0.5
            hits.append((lat, lon, rng, w))

        if not hits:
            return None

        total_w = sum(h[3] for h in hits)
        lat = sum(h[0] * h[3] for h in hits) / total_w
        lon = sum(h[1] * h[3] for h in hits) / total_w

        # Genauigkeit: gewichteter mittlerer Zellradius, durch Mehrfachabdeckung
        # verbessert (sqrt-Gesetz), aber nie besser als 150 m.
        mean_range = sum(h[2] * h[3] for h in hits) / total_w
        accuracy = max(150.0, mean_range / (len(hits) ** 0.5))
        return self.make_fix(lat=lat, lon=lon, accuracy_m=accuracy)
