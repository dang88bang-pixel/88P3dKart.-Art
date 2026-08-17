"""GTFS-Realtime VehiclePositions-Adapter.

Warum genau diese Quelle als Stufe 1 (docs/API_INTEGRATION_REVIEW.md §6):

* offener Standard (Protobuf), keine Anbieterbindung
* in DE flächendeckend verfügbar (DELFI, MobiData BW, VBB, …)
* reines HTTP-Polling — kein zusätzlicher Verbindungsstack, kein MQTT/WSS
* deckt Szenario 2 (Evakuierung) und 4 (temporäre Szenarien) ab

Feldnummern nach gtfs-realtime.proto (FeedMessage):

    FeedMessage.header      = 1   FeedMessage.entity      = 2
    FeedHeader.gtfs_realtime_version = 1
    FeedHeader.timestamp    = 3
    FeedEntity.id           = 1   FeedEntity.vehicle      = 4
    VehiclePosition.trip    = 1   VehiclePosition.position = 2
    VehiclePosition.current_stop_sequence = 3
    VehiclePosition.timestamp = 5
    VehiclePosition.stop_id = 7   VehiclePosition.vehicle = 8
    VehiclePosition.occupancy_status = 9
    Position.latitude = 1  .longitude = 2  .bearing = 3
    Position.odometer = 4  .speed = 5
    TripDescriptor.trip_id = 1  .route_id = 5  .direction_id = 6
    VehicleDescriptor.id = 1  .label = 2  .license_plate = 3
"""
from __future__ import annotations

import logging
import time
from typing import List, Optional

from config import CONFIG
from models import ExternalEntity

from . import protobuf_lite as pb
from .base import ExternalSource

logger = logging.getLogger(__name__)

try:
    import httpx

    HTTPX_AVAILABLE = True
except ImportError:  # pragma: no cover
    httpx = None  # type: ignore
    HTTPX_AVAILABLE = False

OCCUPANCY_LABELS = {
    0: "EMPTY",
    1: "MANY_SEATS_AVAILABLE",
    2: "FEW_SEATS_AVAILABLE",
    3: "STANDING_ROOM_ONLY",
    4: "CRUSHED_STANDING_ROOM_ONLY",
    5: "FULL",
    6: "NOT_ACCEPTING_PASSENGERS",
    7: "NO_DATA_AVAILABLE",
    8: "NOT_BOARDABLE",
}


def parse_feed(data: bytes) -> tuple[Optional[float], List[ExternalEntity]]:
    """Dekodiert ein GTFS-RT FeedMessage zu ExternalEntity-Objekten.

    Liefert (header_timestamp, entities). Entitäten ohne Position werden
    übersprungen — TripUpdates und Alerts teilen sich denselben Feed-Typ.
    """
    feed = pb.parse_message(data)

    header = pb.get_submessage(feed, 1)
    header_ts: Optional[float] = None
    if header:
        raw_ts = pb.get_uint(header, 3)
        if raw_ts:
            header_ts = float(raw_ts)

    now = time.time()
    entities: List[ExternalEntity] = []

    for ent in pb.get_repeated_submessages(feed, 2):
        vehicle = pb.get_submessage(ent, 4)
        if vehicle is None:
            continue  # TripUpdate/Alert — hier nicht relevant

        position = pb.get_submessage(vehicle, 2)
        if position is None:
            continue

        lat = pb.get_float(position, 1)
        lon = pb.get_float(position, 2)
        if lat is None or lon is None:
            continue
        # Reale Feeds liefern gelegentlich 0/0 oder Werte ausserhalb des
        # gültigen Bereichs. Beides verwerfen statt in die Szene zu lassen.
        if not (-90.0 <= lat <= 90.0) or not (-180.0 <= lon <= 180.0):
            continue
        if abs(lat) < 1e-6 and abs(lon) < 1e-6:
            continue

        bearing = pb.get_float(position, 3)
        speed = pb.get_float(position, 5)

        vp_ts = pb.get_uint(vehicle, 5)
        ts = float(vp_ts) if vp_ts else (header_ts or now)

        descriptor = pb.get_submessage(vehicle, 8)
        trip = pb.get_submessage(vehicle, 1)

        vehicle_id = pb.get_string(descriptor, 1) if descriptor else None
        label = pb.get_string(descriptor, 2) if descriptor else None
        route_id = pb.get_string(trip, 5) if trip else None
        trip_id = pb.get_string(trip, 1) if trip else None

        # Fallback-Kette für die ID. VehicleDescriptor.id ist laut Spezifikation
        # agenturintern und weder garantiert stabil noch garantiert vorhanden
        # (Befund C5 im Prüfbericht) — deshalb id_is_stable=False.
        entity_id = vehicle_id or trip_id or pb.get_string(ent, 1) or "unknown"

        occupancy = pb.get_uint(vehicle, 9)
        stop_id = pb.get_string(vehicle, 7)

        metadata = {
            "route_id": route_id,
            "trip_id": trip_id,
            "stop_id": stop_id,
            "vehicle_label": label,
            "occupancy_status": OCCUPANCY_LABELS.get(occupancy) if occupancy is not None else None,
            "feed_entity_id": pb.get_string(ent, 1),
        }
        metadata = {k: v for k, v in metadata.items() if v is not None}

        entities.append(
            ExternalEntity(
                source="gtfs_rt",
                entity_type="vehicle",
                entity_id=entity_id,
                id_is_stable=False,
                label=label or route_id or entity_id,
                lat=float(lat),
                lon=float(lon),
                bearing_deg=float(bearing) if bearing is not None else None,
                speed_mps=float(speed) if speed is not None else None,
                timestamp=ts,
                received_at=now,
                age_s=max(0.0, now - ts),
                license=CONFIG.GTFS_RT_LICENSE,
                attribution=CONFIG.GTFS_RT_ATTRIBUTION or None,
                metadata=metadata,
            )
        )

    return header_ts, entities


class GtfsRealtimeSource(ExternalSource):
    """Pollt einen GTFS-RT VehiclePositions-Endpunkt."""

    name = "gtfs_rt"
    entity_type = "vehicle"

    def __init__(
        self,
        url: Optional[str] = None,
        poll_interval_s: Optional[float] = None,
        api_key: Optional[str] = None,
        api_key_header: Optional[str] = None,
    ):
        super().__init__()
        self.url = url if url is not None else CONFIG.GTFS_RT_URL
        self.poll_interval_s = (
            poll_interval_s if poll_interval_s is not None else CONFIG.GTFS_RT_POLL_S
        )
        self.api_key = api_key if api_key is not None else CONFIG.GTFS_RT_API_KEY
        self.api_key_header = api_key_header or CONFIG.GTFS_RT_API_KEY_HEADER
        self.license = CONFIG.GTFS_RT_LICENSE
        self.attribution = CONFIG.GTFS_RT_ATTRIBUTION or None
        self._client: Optional["httpx.AsyncClient"] = None
        self.feed_timestamp: Optional[float] = None

    def available(self) -> bool:
        return bool(self.url) and HTTPX_AVAILABLE

    def _get_client(self) -> "httpx.AsyncClient":
        if self._client is None:
            headers = {"User-Agent": CONFIG.GEO_USER_AGENT}
            if self.api_key:
                headers[self.api_key_header] = self.api_key
            self._client = httpx.AsyncClient(
                timeout=CONFIG.GEO_TIMEOUT_S,
                headers=headers,
                follow_redirects=True,
            )
        return self._client

    async def fetch(self) -> List[ExternalEntity]:
        if not self.available():
            raise RuntimeError("GTFS-RT nicht konfiguriert (GTFS_RT_URL fehlt)")

        resp = await self._get_client().get(self.url)
        resp.raise_for_status()
        header_ts, entities = parse_feed(resp.content)
        self.feed_timestamp = header_ts
        logger.debug("%s: %d Fahrzeuge empfangen", self.name, len(entities))
        return entities

    async def aclose(self) -> None:
        if self._client is not None:
            await self._client.aclose()
            self._client = None
