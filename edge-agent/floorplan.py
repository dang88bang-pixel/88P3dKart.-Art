"""Grundriss-Integration — Python-Kern (docs/FLOORPLAN.md).

Portierung der v12.0.0-Kernlogik mit **verifizierten Quellen** (die
Machbarkeitsprüfung ergab: „Mapzen" existiert nicht mehr, „hoowoge.de" ist
ein Tippfehler für HOWOGE/Berlin ohne öffentliche API, „api.openstreetview.org"
ist fiktiv — aktive Alternative: KartaView):

- Nominatim-Geocoding (Usage-Policy-konform: User-Agent, ≤ 1 req/s),
- Photon-Geocoding (komoot, OSM-basiert) als zweite Quelle,
- OSM-Overpass-Gebäudeabruf (Umrisse, Etagen, Höhen) → GeoJSON,
- KartaView-URL-Builder (Street-Level-Bilder, öffentlicher Endpoint),
- Source-Katalog mit Verfügbarkeitsstatus.

Tests laufen offline (Fixtures); die Live-Verifikation der Endpunkte ist in
docs/FLOORPLAN.md dokumentiert.
"""

from __future__ import annotations

import json
import time
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

import httpx

DEFAULT_USER_AGENT = "3dxAgent/1.0 (https://github.com/dang88bang-pixel/88P3dKart.-Art)"
NOMINATIM_URL = "https://nominatim.openstreetmap.org/search"
PHOTON_URL = "https://photon.komoot.io/api/"
OVERPASS_URL = "https://overpass-api.de/api/interpreter"
OVERPASS_MIRROR_URL = "https://overpass.kumi.systems/api/interpreter"
KARTAVIEW_PHOTO_SEARCH_URL = "https://api.kartaview.org/1.0/photo/search/"

# ─── Source-Katalog (verifizierte Verfügbarkeit) ────────────────────────────


@dataclass
class SourceDescriptor:
    name: str
    kind: str  # geocoder | buildings | imagery | portal
    endpoint: Optional[str]
    available: bool
    requires_auth: bool = False
    priority: int = 99
    notes: str = ""


SOURCES: List[SourceDescriptor] = [
    SourceDescriptor(
        "Nominatim (OSM)", "geocoder", NOMINATIM_URL, True, priority=1,
        notes="Usage Policy: max 1 req/s, gültiger User-Agent, ODbL-Attribution; kein Bulk-Geocoding",
    ),
    SourceDescriptor(
        "Photon (komoot)", "geocoder", PHOTON_URL, True, priority=2,
        notes="OSM-basiert, Apache-2.0, Demo-Server mit Limits (self-hostbar für Produktion)",
    ),
    SourceDescriptor(
        "OSM Overpass", "buildings", OVERPASS_URL, True, priority=1,
        notes="Gebäudeumrisse weltweit (way/relation[building]); Etikette: kleiner Radius, Timeout",
    ),
    SourceDescriptor(
        "OSM Buildings (osmbuildings.org)", "buildings", None, False, priority=3,
        notes="3D-Viewer-Bibliothek; öffentliche Daten-API nicht mehr frei — wir rendern selbst (Three.js)",
    ),
    SourceDescriptor(
        "KartaView (ex OpenStreetCam)", "imagery", KARTAVIEW_PHOTO_SEARCH_URL, True,
        priority=2,
        notes="Öffentlicher Endpoint: 100 req/h ohne Auth, 1000 req/h mit API-Key (X-Auth-Token)",
    ),
    SourceDescriptor(
        "Mapillary", "imagery", None, False, priority=9,
        notes="Zu Meta verkauft; freie API praktisch eingestellt — KartaView/Panoramax als Alternative",
    ),
    SourceDescriptor(
        "HOWOGE (Berlin, ehem. 'hoowoge.de' der Spec)", "portal", None, False, priority=9,
        notes="Wohnungsbaugesellschaft; Grundriss-/BIM-Systeme intern — keine öffentliche API; nur Anzeigen-Metadaten per Website",
    ),
    SourceDescriptor(
        "BIM Deutschland (bimdeutschland.de)", "portal", None, False, priority=9,
        notes="Informationsportal (planen-bauen 4.0), kein offenes BIM-Modell-Repository; echte Daten via CityGML/INSPIRE der Länder",
    ),
    SourceDescriptor(
        "Stadt-/Landes-Geoportale (INSPIRE/WFS)", "portal", None, True, priority=4,
        notes="z. B. Berlin FIS-Broker (fbinter.stadt-berlin.de), Hamburg Transparenzportal — WFS je Kommune, GeoNutzV-Bedingungen",
    ),
]

SOURCES_BY_NAME = {s.name: s for s in SOURCES}


# ─── Geocoding ─────────────────────────────────────────────────────────────


@dataclass
class GeocodingResult:
    display_name: str
    lat: float
    lon: float
    source: str
    osm_type: Optional[str] = None
    osm_id: Optional[str] = None
    building_type: Optional[str] = None
    confidence: float = 0.5

    def to_dict(self) -> Dict[str, Any]:
        return {
            "display_name": self.display_name,
            "lat": self.lat,
            "lon": self.lon,
            "source": self.source,
            "osm_type": self.osm_type,
            "osm_id": self.osm_id,
            "building_type": self.building_type,
            "confidence": self.confidence,
        }


def _confidence_from_importance(importance: Any) -> float:
    try:
        value = float(importance)
    except (TypeError, ValueError):
        return 0.5
    # Nominatim-Wichtigkeiten liegen typisch zwischen 0 und ~1
    return max(0.0, min(1.0, value))


def nominatim_search(
    query: str,
    user_agent: str = DEFAULT_USER_AGENT,
    limit: int = 5,
    timeout: float = 10.0,
) -> List[GeocodingResult]:
    """Nominatim-Suche (Usage-Policy: gültiger User-Agent, sparsam)."""
    response = httpx.get(
        NOMINATIM_URL,
        params={
            "q": query,
            "format": "jsonv2",
            "limit": limit,
            "addressdetails": 1,
            "accept-language": "de",
        },
        headers={"User-Agent": user_agent},
        timeout=timeout,
    )
    response.raise_for_status()
    results = []
    for item in response.json():
        results.append(
            GeocodingResult(
                display_name=str(item.get("display_name", "")),
                lat=float(item["lat"]),
                lon=float(item["lon"]),
                source="nominatim",
                osm_type=item.get("osm_type"),
                osm_id=item.get("osm_id"),
                building_type=item.get("type"),
                confidence=_confidence_from_importance(item.get("importance")),
            )
        )
    return results


def photon_search(
    query: str,
    limit: int = 5,
    timeout: float = 10.0,
) -> List[GeocodingResult]:
    """Photon-Suche (komoot, OSM-basiert) — zweiter Geocoder/Fallback."""
    response = httpx.get(
        PHOTON_URL,
        params={"q": query, "limit": limit, "lang": "de"},
        timeout=timeout,
    )
    response.raise_for_status()
    results = []
    for feature in response.json().get("features", []):
        props = feature.get("properties", {})
        coords = feature.get("geometry", {}).get("coordinates", [None, None])
        if coords[0] is None or coords[1] is None:
            continue
        results.append(
            GeocodingResult(
                display_name=str(props.get("name") or props.get("street") or query),
                lat=float(coords[1]),
                lon=float(coords[0]),
                source="photon",
                osm_type=props.get("osm_type"),
                osm_id=str(props.get("osm_id", "")),
                building_type=props.get("type"),
                confidence=_confidence_from_importance(props.get("importance", 0.5)),
            )
        )
    return results


def geocode(query: str, user_agent: str = DEFAULT_USER_AGENT) -> List[GeocodingResult]:
    """Primär Nominatim, Fallback Photon."""
    try:
        results = nominatim_search(query, user_agent=user_agent)
        if results:
            return results
    except Exception:
        pass
    return photon_search(query)


# ─── OSM-Overpass: Gebäudeabruf ────────────────────────────────────────────


def build_overpass_buildings_query(
    lat: float,
    lon: float,
    radius: float = 100.0,
    timeout: int = 25,
) -> str:
    """Overpass-QL: Gebäude-Wege und -Relationen im Radius (mit Knoten)."""
    return (
        f"[out:json][timeout:{max(1, timeout)}];\n"
        "(\n"
        f'  way["building"](around:{radius},{lat},{lon});\n'
        f'  relation["building"](around:{radius},{lat},{lon});\n'
        ");\n"
        "out body;\n"
        ">;\n"
        "out skel qt;"
    )


def parse_overpass_buildings(data: Dict[str, Any]) -> List[Dict[str, Any]]:
    """Overpass-JSON → Gebäude mit geschlossenem Umriss (GeoJSON-Polygone)."""
    nodes: Dict[int, tuple] = {}
    for element in data.get("elements", []):
        if element.get("type") == "node" and "lat" in element and "lon" in element:
            nodes[element["id"]] = (float(element["lat"]), float(element["lon"]))

    buildings: List[Dict[str, Any]] = []
    for element in data.get("elements", []):
        if element.get("type") not in ("way", "relation"):
            continue
        tags = element.get("tags") or {}
        if "building" not in tags:
            continue

        # Relationen: äußere Rolle der Member-Wege
        refs: List[int] = []
        if element["type"] == "way":
            refs = element.get("nodes", [])
        else:
            outer = [m for m in element.get("members", []) if m.get("role") == "outer"]
            for member in outer:
                member_way = next(
                    (e for e in data.get("elements", []) if e.get("id") == member.get("ref") and e.get("type") == "way"),
                    None,
                )
                if member_way:
                    refs.extend(member_way.get("nodes", []))

        ring = [nodes[nid] for nid in refs if nid in nodes]
        if len(ring) < 4 or ring[0] != ring[-1]:
            continue  # ungeschlossen → kein valider Umriss

        levels = tags.get("building:levels") or tags.get("levels")
        try:
            level_count = int(float(levels))
        except (TypeError, ValueError):
            level_count = 1
        try:
            height = float(tags.get("height", ""))
        except ValueError:
            height = level_count * 3.2

        buildings.append(
            {
                "type": "Feature",
                "geometry": {"type": "Polygon", "coordinates": [[[r[1], r[0]] for r in ring]]},
                "properties": {
                    "osm_id": f"{element['type']}/{element['id']}",
                    "building": str(tags.get("building", "yes")),
                    "levels": level_count,
                    "height": height,
                    "name": tags.get("name"),
                    "addr_street": tags.get("addr:street"),
                    "addr_housenumber": tags.get("addr:housenumber"),
                },
            }
        )
    return buildings


def fetch_osm_buildings(
    lat: float,
    lon: float,
    radius: float = 100.0,
    endpoints: Optional[List[str]] = None,
    timeout: float = 30.0,
) -> Dict[str, Any]:
    """Live-Abruf der Gebäudeumrisse (Overpass) → GeoJSON FeatureCollection.

    Reihenfolge: Hauptendpunkt, dann Kumi-Systems-Spiegel (der Hauptserver
    ist häufig überlastet — „server too busy" ist ein Last-, kein
    Query-Fehler; live verifiziert am 14.08.2026).
    """
    endpoints = endpoints or [OVERPASS_URL, OVERPASS_MIRROR_URL]
    last_error: Optional[Exception] = None
    for endpoint in endpoints:
        try:
            query = build_overpass_buildings_query(lat, lon, radius)
            response = httpx.post(endpoint, data={"data": query}, timeout=timeout)
            response.raise_for_status()
            features = parse_overpass_buildings(response.json())
            return {
                "type": "FeatureCollection",
                "features": features,
                "metadata": {
                    "source": "OSM Overpass",
                    "endpoint": endpoint,
                    "lat": lat,
                    "lon": lon,
                    "radius": radius,
                    "timestamp": int(time.time() * 1000),
                },
            }
        except Exception as exc:  # noqa: BLE001 — Fallback-Kette
            last_error = exc
    raise RuntimeError(f"Overpass-Abruf fehlgeschlagen ({len(endpoints)} Endpunkte): {last_error}")


# ─── KartaView (Street-Level-Bilder) ───────────────────────────────────────


def kartaview_search_url(lat: float, lon: float, radius: float = 50.0, limit: int = 20) -> str:
    """Öffentlicher KartaView-Such-URL (bbox; Auth via X-Auth-Token optional)."""
    d = radius / 111_000.0  # grobe Grad-Näherung (Breitengrad)
    west, south = lon - d, lat - d
    east, north = lon + d, lat + d
    return (
        f"{KARTAVIEW_PHOTO_SEARCH_URL}?bbox={west},{south},{east},{north}&limit={limit}"
    )
