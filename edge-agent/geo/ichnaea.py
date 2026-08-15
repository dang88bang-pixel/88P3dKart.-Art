"""Generischer Ichnaea-Client (beaconDB, lokale Instanz, Combain).

Das Ichnaea-Protokoll (Apache-2.0, Mozilla) ist das gemeinsame Format
mehrerer Anbieter. Entscheidung E3 aus docs/GEOLOCATION_CHANGE_PLAN.md:
Providerwechsel ist Konfiguration, nicht Code.
"""
from __future__ import annotations

import logging
from typing import Optional

from config import CONFIG
from models import GeoFix, GeolocateRequest

from .base import TIER_CLOUD, GeoProvider

logger = logging.getLogger(__name__)

try:
    import httpx

    HTTPX_AVAILABLE = True
except ImportError:  # pragma: no cover - optionale Runtime-Dependency
    httpx = None  # type: ignore
    HTTPX_AVAILABLE = False


class IchnaeaProvider(GeoProvider):
    """Ichnaea-kompatibler /v1/geolocate-Client."""

    def __init__(
        self,
        name: str,
        url: str,
        api_key: str = "",
        license: str = "unknown",
        attribution: Optional[str] = None,
        tier: int = TIER_CLOUD,
        ttl_days: Optional[int] = None,
        timeout_s: Optional[float] = None,
    ):
        self.name = name
        self.url = url
        self.api_key = api_key
        self.license = license
        self.attribution = attribution
        self.tier = tier
        self.ttl_days = ttl_days
        self.timeout_s = timeout_s if timeout_s is not None else CONFIG.GEO_TIMEOUT_S
        self._client: Optional["httpx.AsyncClient"] = None

    def available(self) -> bool:
        return bool(self.url) and HTTPX_AVAILABLE

    def _get_client(self) -> "httpx.AsyncClient":
        if self._client is None:
            self._client = httpx.AsyncClient(
                timeout=self.timeout_s,
                headers={"User-Agent": CONFIG.GEO_USER_AGENT},
            )
        return self._client

    async def locate(self, req: GeolocateRequest) -> Optional[GeoFix]:
        if not self.available() or req.is_empty():
            return None

        params = {"key": self.api_key} if self.api_key else None
        body = req.model_dump(exclude_none=True)

        try:
            resp = await self._get_client().post(self.url, json=body, params=params)
        except Exception as exc:  # noqa: BLE001 - Netzfehler sind erwartbar
            logger.warning("%s: Anfrage fehlgeschlagen: %s", self.name, exc)
            return None

        if resp.status_code == 404:
            # Ichnaea-Konvention: kein Treffer
            return None
        if resp.status_code != 200:
            logger.warning("%s: HTTP %d", self.name, resp.status_code)
            return None

        try:
            data = resp.json()
            loc = data["location"]
            return self.make_fix(
                lat=float(loc["lat"]),
                lon=float(loc["lng"]),
                accuracy_m=float(data.get("accuracy", 5000.0)),
            )
        except (KeyError, TypeError, ValueError) as exc:
            logger.warning("%s: unerwartete Antwort: %s", self.name, exc)
            return None

    async def aclose(self) -> None:
        if self._client is not None:
            await self._client.aclose()
            self._client = None


def build_beacondb() -> IchnaeaProvider:
    """beaconDB — Ichnaea-kompatibler MLS-Nachfolger (AGPL-3.0 Server).

    Achtung: der Dienst ist ausdrücklich experimentell (Befund im Prüfbericht).
    """
    return IchnaeaProvider(
        name="beacondb",
        url=CONFIG.GEO_BEACONDB_URL,
        license="ODbL-1.0 (Daten) / AGPL-3.0 (Server)",
        attribution="Positionsdaten: beaconDB-Mitwirkende",
        tier=TIER_CLOUD,
    )


def build_local_ichnaea() -> IchnaeaProvider:
    """Selbst betriebene Ichnaea-Instanz im eigenen Netz (Tier 1)."""
    from .base import TIER_LOCAL

    return IchnaeaProvider(
        name="local_ichnaea",
        url=CONFIG.GEO_LOCAL_ICHNAEA_URL,
        license="eigene Erhebung",
        attribution=None,
        tier=TIER_LOCAL,
    )


def build_combain() -> IchnaeaProvider:
    """Combain — kommerziell, unterstützt On-Premise-Betrieb."""
    return IchnaeaProvider(
        name="combain",
        url=CONFIG.GEO_COMBAIN_URL,
        api_key=CONFIG.GEO_COMBAIN_KEY,
        license="proprietär (Combain-Vertrag)",
        attribution="Positionsbestimmung: Combain Mobile AB",
        tier=TIER_CLOUD,
    )


def build_google() -> IchnaeaProvider:
    """Google Geolocation API.

    Hartes ``ttl_days=30``: die Google-ToS erlauben das Zwischenspeichern von
    lat/lng nur bis zu 30 Tage. Die Retention in database.py setzt das um.
    """
    return IchnaeaProvider(
        name="google",
        url="https://www.googleapis.com/geolocation/v1/geolocate",
        api_key=CONFIG.GEO_GOOGLE_KEY,
        license="proprietär (Google Maps Platform ToS)",
        attribution="Positionsbestimmung: Google",
        tier=TIER_CLOUD,
        ttl_days=CONFIG.GEO_GOOGLE_TTL_DAYS,
    )
