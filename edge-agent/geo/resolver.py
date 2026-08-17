"""Kaskadierender Geo-Resolver: Policy-Gate, Cache, Qualitätsfilter, Audit.

Ablauf (docs/GEOLOCATION_CHANGE_PLAN.md §2.1):

    1. Policy-Gate:  GEO_ENABLED? sonst None
    2. Cache-Lookup: Hash über sortierte BSSIDs/CellIDs
    3. Kette durchlaufen (GEO_PROVIDER_CHAIN, in Reihenfolge)
         - tier >= 2 UND GEO_OFFLINE_ONLY  -> überspringen
         - nicht available()               -> überspringen
         - Timeout/Fehler                  -> protokollieren, nächster
         - quality < GEO_MIN_QUALITY       -> verwerfen, nächster
    4. Erster gültiger Fix gewinnt; Cache schreiben
    5. Audit-Log je Versuch
"""
from __future__ import annotations

import asyncio
import hashlib
import logging
import time
from typing import Any, Dict, List, Optional

from config import CONFIG
from models import GeoAnchor, GeoFix, GeolocateRequest

from .base import TIER_CLOUD, GeoProvider
from .offline_cell import OfflineCellProvider

logger = logging.getLogger(__name__)


def _cache_key(req: GeolocateRequest) -> str:
    """Stabiler Hash über die Scan-Signatur (Reihenfolge-unabhängig)."""
    parts: List[str] = sorted(
        ap.macAddress.lower() for ap in req.wifiAccessPoints
    )
    parts += sorted(
        f"{c.mobileCountryCode}-{c.mobileNetworkCode}-{c.locationAreaCode}-{c.cellId}"
        for c in req.cellTowers
    )
    parts += sorted(b.macAddress.lower() for b in req.bluetoothBeacons)
    return hashlib.sha256("|".join(parts).encode()).hexdigest()[:32]


class GeoResolver:
    """Verwaltet Providerkette, Cache und den aktuellen GeoAnchor."""

    def __init__(self, providers: Optional[List[GeoProvider]] = None):
        self.providers: List[GeoProvider] = (
            providers if providers is not None else self._build_default_chain()
        )
        self._cache: Dict[str, GeoFix] = {}
        self._anchor: Optional[GeoAnchor] = None
        self._audit: List[Dict[str, Any]] = []

    # ─── Aufbau ────────────────────────────────────────────────
    @staticmethod
    def _build_default_chain() -> List[GeoProvider]:
        from .ichnaea import (
            build_beacondb,
            build_combain,
            build_google,
            build_local_ichnaea,
        )

        factories = {
            "offline_cell": OfflineCellProvider,
            "local_ichnaea": build_local_ichnaea,
            "beacondb": build_beacondb,
            "combain": build_combain,
            "google": build_google,
        }
        chain: List[GeoProvider] = []
        for name in CONFIG.GEO_PROVIDER_CHAIN.split(","):
            name = name.strip()
            if not name:
                continue
            factory = factories.get(name)
            if factory is None:
                logger.warning("Unbekannter Geo-Provider in Kette: %s", name)
                continue
            chain.append(factory())
        return chain

    # ─── Kernlogik ─────────────────────────────────────────────
    async def locate(self, req: GeolocateRequest) -> Optional[GeoFix]:
        if not CONFIG.GEO_ENABLED:
            logger.debug("Geolokalisierung deaktiviert (GEO_ENABLED=false)")
            return None
        if req.is_empty():
            return None

        key = _cache_key(req)
        cached = self._cache.get(key)
        if cached is not None and (time.time() - cached.timestamp) < CONFIG.GEO_CACHE_TTL_S:
            logger.debug("Geo-Cache-Treffer (%s)", cached.source)
            return cached

        for provider in self.providers:
            if provider.tier >= TIER_CLOUD and CONFIG.GEO_OFFLINE_ONLY:
                self._log_audit(provider.name, "skipped", "offline_only")
                continue
            if not provider.available():
                self._log_audit(provider.name, "skipped", "unavailable")
                continue

            started = time.perf_counter()
            try:
                fix = await asyncio.wait_for(
                    provider.locate(req), timeout=CONFIG.GEO_TIMEOUT_S
                )
            except asyncio.TimeoutError:
                self._log_audit(provider.name, "timeout", None)
                continue
            except Exception as exc:  # noqa: BLE001
                self._log_audit(provider.name, "error", str(exc))
                continue

            latency_ms = (time.perf_counter() - started) * 1000.0
            if fix is None:
                self._log_audit(provider.name, "no_result", None, latency_ms)
                continue
            if fix.quality < CONFIG.GEO_MIN_QUALITY:
                self._log_audit(
                    provider.name,
                    "rejected_quality",
                    f"q={fix.quality:.2f} acc={fix.accuracy_m:.0f}m",
                    latency_ms,
                )
                continue

            self._log_audit(provider.name, "accepted", None, latency_ms, fix)
            self._cache[key] = fix
            return fix

        return None

    def _log_audit(
        self,
        provider: str,
        outcome: str,
        detail: Optional[str] = None,
        latency_ms: float = 0.0,
        fix: Optional[GeoFix] = None,
    ) -> None:
        entry = {
            "timestamp": time.time(),
            "provider": provider,
            "outcome": outcome,
            "detail": detail,
            "latency_ms": round(latency_ms, 1),
            "accuracy_m": fix.accuracy_m if fix else None,
            "quality": fix.quality if fix else None,
        }
        self._audit.append(entry)
        if len(self._audit) > 200:
            self._audit = self._audit[-200:]
        if outcome == "accepted":
            logger.info(
                "Geo-Fix von %s: acc=%.0fm q=%.2f (%.0fms)",
                provider,
                fix.accuracy_m if fix else -1,
                fix.quality if fix else -1,
                latency_ms,
            )

    @property
    def audit_log(self) -> List[Dict[str, Any]]:
        return list(self._audit)

    # ─── Anker ─────────────────────────────────────────────────
    @property
    def anchor(self) -> Optional[GeoAnchor]:
        return self._anchor

    def set_anchor(self, anchor: GeoAnchor) -> GeoAnchor:
        self._anchor = anchor
        logger.info(
            "GeoAnchor gesetzt: %.6f, %.6f (±%.0fm, Quelle %s, Heading %s)",
            anchor.fix.lat,
            anchor.fix.lon,
            anchor.fix.accuracy_m,
            anchor.fix.source,
            f"{anchor.heading_deg:.0f}°" if anchor.heading_deg is not None else "—",
        )
        return anchor

    def clear_anchor(self) -> None:
        self._anchor = None

    def describe_providers(self) -> List[Dict[str, Any]]:
        return [
            {
                "name": p.name,
                "tier": p.tier,
                "available": p.available(),
                "blocked_by_policy": p.tier >= TIER_CLOUD and CONFIG.GEO_OFFLINE_ONLY,
                "license": p.license,
                "attribution": p.attribution,
                "ttl_days": p.ttl_days,
            }
            for p in self.providers
        ]

    async def aclose(self) -> None:
        for provider in self.providers:
            try:
                await provider.aclose()
            except Exception:  # noqa: BLE001
                pass
