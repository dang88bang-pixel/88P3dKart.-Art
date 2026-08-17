"""Basisklasse für externe Tracking-Quellen (Polling und Streaming)."""
from __future__ import annotations

import logging
import time
from abc import ABC, abstractmethod
from typing import List, Optional

from models import EntityType, ExternalEntity, ExternalSourceStatus

logger = logging.getLogger(__name__)


class ExternalSource(ABC):
    """Ein Adapter für eine externe Entitätsquelle.

    Bewusst schlank gehalten: Der Prüfbericht (docs/API_INTEGRATION_REVIEW.md)
    empfiehlt Stufe 1 mit *einem* Adapter auf vorhandener Infrastruktur statt
    eines eigenständigen api-gateway-Microservice mit elf Adaptern.
    """

    name: str = "unnamed"
    entity_type: EntityType = "unknown"
    license: str = "unknown"
    attribution: Optional[str] = None
    poll_interval_s: float = 30.0

    def __init__(self) -> None:
        self.last_success: Optional[float] = None
        self.last_error: Optional[str] = None
        self.consecutive_errors: int = 0
        self._entities: List[ExternalEntity] = []

    # ─── Vertrag ───────────────────────────────────────────────
    @abstractmethod
    async def fetch(self) -> List[ExternalEntity]:
        """Holt den aktuellen Stand. Wirft bei Fehlern eine Exception."""

    def available(self) -> bool:
        """Ob der Adapter konfiguriert und einsatzbereit ist."""
        return True

    async def aclose(self) -> None:
        """Ressourcen freigeben."""

    # ─── Betrieb ───────────────────────────────────────────────
    async def poll(self) -> List[ExternalEntity]:
        """Ruft fetch() ab und pflegt den Gesundheitszustand."""
        try:
            entities = await self.fetch()
        except Exception as exc:  # noqa: BLE001 - Feeds fallen erwartbar aus
            self.consecutive_errors += 1
            self.last_error = f"{type(exc).__name__}: {exc}"
            logger.warning(
                "%s: Abruf fehlgeschlagen (%dx in Folge): %s",
                self.name,
                self.consecutive_errors,
                exc,
            )
            return self._entities  # letzter bekannter Stand, wird veralten

        self.last_success = time.time()
        self.last_error = None
        self.consecutive_errors = 0
        self._entities = entities
        return entities

    @property
    def entities(self) -> List[ExternalEntity]:
        return self._entities

    @property
    def healthy(self) -> bool:
        """Gesund = mindestens ein Erfolg und aktuell keine Fehlerserie."""
        return self.last_success is not None and self.consecutive_errors < 3

    def status(self, enabled: bool = True) -> ExternalSourceStatus:
        return ExternalSourceStatus(
            name=self.name,
            enabled=enabled,
            healthy=self.healthy,
            entity_type=self.entity_type,
            license=self.license,
            attribution=self.attribution,
            poll_interval_s=self.poll_interval_s,
            last_success=self.last_success,
            last_error=self.last_error,
            consecutive_errors=self.consecutive_errors,
            entity_count=len(self._entities),
        )
