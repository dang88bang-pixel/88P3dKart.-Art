"""Externe Tracking-Quellen (v4.5.0-Geo, Stufe 1).

Bewusst als Paket im Edge-Agent statt als eigenständiger api-gateway-Service:
Mosquitto und SQLite sind bereits vorhanden, ein dritter Message-Layer und
eine zweite Datenbank-Engine wären auf einem Handheld nicht zu rechtfertigen
(docs/API_INTEGRATION_REVIEW.md §4, W5/W6).
"""
from .base import ExternalSource
from .gtfs_rt import GtfsRealtimeSource, parse_feed
from .manager import ExternalEntityManager, latency_quality

__all__ = [
    "ExternalSource",
    "GtfsRealtimeSource",
    "parse_feed",
    "ExternalEntityManager",
    "latency_quality",
]
