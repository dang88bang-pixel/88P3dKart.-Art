"""Verwaltet externe Quellen: Polling, Projektion, Filter, Staleness.

Der Manager ist die Stelle, an der die im Prüfbericht benannten Lücken
geschlossen werden:

* **Projektion** — jede Entität bekommt lokale Koordinaten, sobald ein
  GeoAnchor gesetzt ist (Blocker A).
* **Radiusfilter** — alles ausserhalb EXT_RADIUS_M wird verworfen. Damit
  landen keine Objekte in der Szene, die 20 Szenenbreiten entfernt sind
  (Maßstabsproblem).
* **Staleness** — Feed-Alter wird berechnet und markiert. Ein schweigender
  Feed darf nicht als "keine Fahrzeuge" erscheinen.
* **Qualität** — Einordnung in die CLIENT_RULES-Formel über Q_latency,
  damit externe Entitäten derselben Schwelle unterliegen wie interne Sensorik.
* **Deduplizierung** — dieselbe Entität aus mehreren Quellen wird über
  (entity_type, gerundete Position) zusammengeführt.
"""
from __future__ import annotations

import asyncio
import logging
import time
from typing import Dict, List, Optional

from config import CONFIG
from geo.projection import bearing_deg, enu_to_local, geodetic_to_enu, haversine_m
from geo.resolver import GeoResolver
from models import ExternalEntity, ExternalEntitySnapshot, ExternalSourceStatus

from .base import ExternalSource

logger = logging.getLogger(__name__)


def latency_quality(age_s: float, max_age_s: float) -> float:
    """Q_latency nach docs/CLIENT_RULES.md — linear abfallend bis max_age_s."""
    if age_s <= 0:
        return 1.0
    if age_s >= max_age_s:
        return 0.0
    return 1.0 - (age_s / max_age_s)


class ExternalEntityManager:
    """Sammelt, projiziert und filtert externe Entitäten."""

    def __init__(
        self,
        resolver: GeoResolver,
        sources: Optional[List[ExternalSource]] = None,
    ):
        self.resolver = resolver
        self.sources: List[ExternalSource] = (
            sources if sources is not None else self._build_default_sources()
        )
        self._tasks: List[asyncio.Task] = []
        self._running = False
        self._on_update = None  # optionaler Broadcast-Callback

    @staticmethod
    def _build_default_sources() -> List[ExternalSource]:
        from .gtfs_rt import GtfsRealtimeSource

        factories = {"gtfs_rt": GtfsRealtimeSource}
        out: List[ExternalSource] = []
        for name in CONFIG.EXT_SOURCES.split(","):
            name = name.strip()
            if not name:
                continue
            factory = factories.get(name)
            if factory is None:
                logger.warning("Unbekannte externe Quelle: %s", name)
                continue
            out.append(factory())
        return out

    # ─── Aufbereitung ──────────────────────────────────────────
    def _project(self, entity: ExternalEntity) -> ExternalEntity:
        """Setzt local/distance_m relativ zum GeoAnchor."""
        anchor = self.resolver.anchor
        if anchor is None:
            return entity

        ref = anchor.fix
        dist = haversine_m(ref.lat, ref.lon, entity.lat, entity.lon)
        entity.distance_m = dist

        east, north, up = geodetic_to_enu(
            entity.lat,
            entity.lon,
            entity.altitude_m or 0.0,
            ref.lat,
            ref.lon,
            ref.altitude_m or 0.0,
        )
        entity.local = enu_to_local(
            east, north, up, anchor.heading_deg, anchor.local_origin
        )
        entity.metadata["bearing_from_anchor"] = round(
            bearing_deg(ref.lat, ref.lon, entity.lat, entity.lon), 1
        )
        return entity

    def _score(self, entity: ExternalEntity, now: float) -> ExternalEntity:
        """Berechnet Alter, Staleness und Qualität."""
        entity.age_s = max(0.0, now - entity.timestamp)
        entity.stale = entity.age_s > CONFIG.EXT_MAX_AGE_S
        entity.quality = round(latency_quality(entity.age_s, CONFIG.EXT_MAX_AGE_S), 3)
        return entity

    @staticmethod
    def _dedup_key(entity: ExternalEntity) -> tuple:
        """Grobraster ~11 m — dieselbe Entität aus zwei Feeds kollabiert."""
        return (entity.entity_type, round(entity.lat, 4), round(entity.lon, 4))

    def collect(self) -> List[ExternalEntity]:
        """Aggregiert alle Quellen, projiziert, filtert und sortiert."""
        now = time.time()
        seen: Dict[tuple, ExternalEntity] = {}

        for source in self.sources:
            for entity in source.entities:
                entity = self._score(entity, now)
                if entity.quality < CONFIG.EXT_MIN_QUALITY:
                    continue

                entity = self._project(entity)
                if (
                    entity.distance_m is not None
                    and entity.distance_m > CONFIG.EXT_RADIUS_M
                ):
                    continue

                key = self._dedup_key(entity)
                existing = seen.get(key)
                if existing is None or entity.timestamp > existing.timestamp:
                    seen[key] = entity

        entities = list(seen.values())
        # Nächste zuerst; ohne Anker nach Aktualität
        if self.resolver.anchor is not None:
            entities.sort(key=lambda e: e.distance_m if e.distance_m is not None else 1e12)
        else:
            entities.sort(key=lambda e: e.age_s)
        return entities[: CONFIG.EXT_MAX_ENTITIES]

    def snapshot(self) -> ExternalEntitySnapshot:
        entities = self.collect()
        return ExternalEntitySnapshot(
            generated_at=time.time(),
            anchor_set=self.resolver.anchor is not None,
            sources=[s.name for s in self.sources],
            count=len(entities),
            entities=entities,
        )

    def status(self) -> List[ExternalSourceStatus]:
        return [s.status(enabled=CONFIG.EXT_ENABLED) for s in self.sources]

    # ─── Lebenszyklus ──────────────────────────────────────────
    def set_update_callback(self, callback) -> None:
        """Callback(snapshot) nach jedem erfolgreichen Poll."""
        self._on_update = callback

    async def _poll_loop(self, source: ExternalSource) -> None:
        # Gestaffelter Start, damit nicht alle Quellen gleichzeitig anfragen
        await asyncio.sleep(1.0)
        while self._running:
            try:
                await source.poll()
                if self._on_update is not None:
                    try:
                        await self._on_update(self.snapshot())
                    except Exception as exc:  # noqa: BLE001
                        logger.warning("Update-Callback fehlgeschlagen: %s", exc)
            except asyncio.CancelledError:
                raise
            except Exception as exc:  # noqa: BLE001
                logger.error("%s: Poll-Schleife: %s", source.name, exc)

            # Backoff bei Fehlerserie: 2^n, gedeckelt auf 8x
            factor = min(2 ** source.consecutive_errors, 8) if source.consecutive_errors else 1
            await asyncio.sleep(source.poll_interval_s * factor)

    def start(self) -> None:
        if self._running or not CONFIG.EXT_ENABLED:
            if not CONFIG.EXT_ENABLED:
                logger.info("Externe Feeds deaktiviert (EXT_ENABLED=false)")
            return
        active = [s for s in self.sources if s.available()]
        if not active:
            logger.info("Keine externe Quelle konfiguriert — Polling nicht gestartet")
            return
        self._running = True
        for source in active:
            self._tasks.append(asyncio.create_task(self._poll_loop(source)))
        logger.info(
            "Externe Feeds gestartet: %s", ", ".join(s.name for s in active)
        )

    async def stop(self) -> None:
        self._running = False
        for task in self._tasks:
            task.cancel()
        for task in self._tasks:
            try:
                await task
            except (asyncio.CancelledError, Exception):  # noqa: BLE001
                pass
        self._tasks.clear()
        for source in self.sources:
            try:
                await source.aclose()
            except Exception:  # noqa: BLE001
                pass
