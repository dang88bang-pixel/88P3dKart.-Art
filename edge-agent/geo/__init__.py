"""Georeferenzierung für 3dxAgent (v4.5.0-Geo).

Enthält die Provider-Kaskade (Tier 0..3) und die Koordinatenbrücke
WGS84 <-> ENU <-> lokaler Agent-Frame.
"""
from .base import (
    TIER_CLOUD,
    TIER_CONTEXT,
    TIER_LOCAL,
    TIER_OFFLINE,
    GeoProvider,
)
from .projection import (
    bearing_deg,
    ecef_to_enu,
    enu_to_geodetic,
    enu_to_local,
    geodetic_to_ecef,
    geodetic_to_enu,
    haversine_m,
)
from .resolver import GeoResolver

__all__ = [
    "GeoProvider",
    "GeoResolver",
    "TIER_OFFLINE",
    "TIER_LOCAL",
    "TIER_CLOUD",
    "TIER_CONTEXT",
    "geodetic_to_ecef",
    "ecef_to_enu",
    "geodetic_to_enu",
    "enu_to_geodetic",
    "enu_to_local",
    "haversine_m",
    "bearing_deg",
]
