"""Abstraktes Provider-Interface für Geolokalisierung."""
from __future__ import annotations

import time
from abc import ABC, abstractmethod
from typing import Optional

from models import GeoFix, GeolocateRequest, accuracy_to_quality

# Tier-Modell (docs/GEOLOCATION_PROVIDERS.md)
TIER_OFFLINE = 0  # lokale Datenbestände, kein Netzverkehr
TIER_LOCAL = 1  # eigenes Netz / selbst gehostete Instanz
TIER_CLOUD = 2  # externe Dienste — bei GEO_OFFLINE_ONLY gesperrt
TIER_CONTEXT = 3  # nur Kontext (Land/ASN), nicht positionsgenau


class GeoProvider(ABC):
    """Basisklasse aller Geo-Provider."""

    name: str = "unnamed"
    tier: int = TIER_CLOUD
    license: str = "unknown"
    attribution: Optional[str] = None
    ttl_days: Optional[int] = None

    @abstractmethod
    async def locate(self, req: GeolocateRequest) -> Optional[GeoFix]:
        """Liefert einen Fix oder None (kein Treffer / nicht verfügbar)."""

    def available(self) -> bool:
        """Ob der Provider einsatzbereit konfiguriert ist."""
        return True

    async def aclose(self) -> None:
        """Ressourcen freigeben (HTTP-Clients, Dateihandles)."""

    def make_fix(
        self,
        lat: float,
        lon: float,
        accuracy_m: float,
        altitude_m: Optional[float] = None,
    ) -> GeoFix:
        """Baut einen GeoFix mit Provider-Metadaten und abgeleiteter Qualität."""
        return GeoFix(
            lat=lat,
            lon=lon,
            accuracy_m=max(float(accuracy_m), 0.1),
            altitude_m=altitude_m,
            source=self.name,
            license=self.license,
            attribution=self.attribution,
            ttl_days=self.ttl_days,
            timestamp=time.time(),
            quality=accuracy_to_quality(accuracy_m),
        )
