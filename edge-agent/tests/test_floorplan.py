"""Tests für die Grundriss-Integration (docs/FLOORPLAN.md).

Offline-deterministisch (Fixtures); die Live-Verifikation der Endpunkte ist
in docs/FLOORPLAN.md dokumentiert.
"""

import json

import pytest

import floorplan
from floorplan import (
    SOURCES,
    GeocodingResult,
    build_overpass_buildings_query,
    fetch_osm_buildings,
    geocode,
    kartaview_search_url,
    parse_overpass_buildings,
)

FIXTURE = {
    "elements": [
        # Rathaus: geschlossener Weg mit Etagen/Höhe
        {"type": "node", "id": 1, "lat": 48.1370, "lon": 11.5752},
        {"type": "node", "id": 2, "lat": 48.1372, "lon": 11.5756},
        {"type": "node", "id": 3, "lat": 48.1369, "lon": 11.5758},
        {"type": "node", "id": 4, "lat": 48.1367, "lon": 11.5754},
        {
            "type": "way", "id": 100, "nodes": [1, 2, 3, 4, 1],
            "tags": {"building": "public", "building:levels": "5", "height": "20", "name": "Rathaus"},
        },
        # Ungeschlossener Weg → wird verworfen
        {"type": "way", "id": 101, "nodes": [1, 2, 3, 4], "tags": {"building": "yes"}},
        # Wohnhaus ohne Etagen/Höhe → Defaults (1 Etage, 3,2 m)
        {"type": "node", "id": 5, "lat": 48.1400, "lon": 11.5700},
        {"type": "node", "id": 6, "lat": 48.1410, "lon": 11.5720},
        {"type": "node", "id": 7, "lat": 48.1405, "lon": 11.5730},
        {"type": "node", "id": 8, "lat": 48.1395, "lon": 11.5710},
        {"type": "way", "id": 200, "nodes": [5, 6, 7, 8, 5], "tags": {"building": "apartments"}},
        # Weg mit fehlenden Knoten → wird verworfen
        {"type": "way", "id": 201, "nodes": [9, 10, 11, 9], "tags": {"building": "yes"}},
    ]
}


def test_source_catalog_verification_status():
    names = {s.name for s in SOURCES}
    # Verifiziert verfügbar
    assert any("Nominatim" in n and s.available for n, s in ((s.name, s) for s in SOURCES))
    assert any("Photon" in s.name and s.available for s in SOURCES)
    assert any("Overpass" in s.name and s.available for s in SOURCES)
    assert any("KartaView" in s.name and s.available for s in SOURCES)
    # Verifiziert NICHT verfügbar (Spec-Korrekturen)
    assert not any(s.available for s in SOURCES if "HOWOGE" in s.name)
    assert not any(s.available for s in SOURCES if "BIM Deutschland" in s.name)
    assert not any(s.available for s in SOURCES if s.name == "Mapillary")
    # „Mapzen" existiert gar nicht mehr — darf nicht im Katalog stehen
    assert not any("Mapzen" in s.name for s in SOURCES)


def test_overpass_query_contains_radius_and_building():
    query = build_overpass_buildings_query(48.137, 11.575, radius=75.0, timeout=30)
    assert 'way["building"](around:75.0,48.137,11.575)' in query
    assert 'relation["building"](around:75.0,48.137,11.575)' in query
    assert "[out:json][timeout:30]" in query


def test_parse_overpass_buildings():
    buildings = parse_overpass_buildings(FIXTURE)
    assert len(buildings) == 2

    rathaus = next(b for b in buildings if b["properties"]["name"] == "Rathaus")
    assert rathaus["properties"]["levels"] == 5
    assert rathaus["properties"]["height"] == 20.0
    assert rathaus["properties"]["osm_id"] == "way/100"
    ring = rathaus["geometry"]["coordinates"][0]
    # GeoJSON-Konvention: [lon, lat]
    assert ring[0] == [11.5752, 48.1370]
    assert ring[0] == ring[-1]  # geschlossen

    apartments = next(b for b in buildings if b["properties"]["building"] == "apartments")
    assert apartments["properties"]["levels"] == 1
    assert abs(apartments["properties"]["height"] - 3.2) < 1e-9


def test_geocode_falls_back_to_photon(monkeypatch):
    def raise_nominatim(*args, **kwargs):
        raise ConnectionError("offline")

    def fake_photon(*args, **kwargs):
        return [GeocodingResult("Rathaus München", 48.1371, 11.5754, "photon")]

    monkeypatch.setattr(floorplan, "nominatim_search", raise_nominatim)
    monkeypatch.setattr(floorplan, "photon_search", fake_photon)
    results = geocode("Rathaus München")
    assert results[0].source == "photon"
    assert results[0].display_name == "Rathaus München"


def test_kartaview_search_url():
    url = kartaview_search_url(48.137, 11.575, radius=50.0, limit=20)
    assert url.startswith("https://api.kartaview.org/1.0/photo/search/")
    assert "bbox=" in url and "limit=20" in url
    # Radius ~50 m → Bbox-Spanne plausibel klein
    west = float(url.split("bbox=")[1].split(",")[0])
    assert abs(west - 11.575) < 0.01


def test_fetch_osm_buildings_parses_live_response_shape(monkeypatch):
    class FakeResponse:
        def raise_for_status(self):
            return None

        def json(self):
            return FIXTURE

    monkeypatch.setattr(floorplan.httpx, "post", lambda *a, **k: FakeResponse())
    result = fetch_osm_buildings(48.137, 11.575, radius=30)
    assert result["type"] == "FeatureCollection"
    assert len(result["features"]) == 2
    assert result["metadata"]["source"] == "OSM Overpass"


def test_fetch_osm_buildings_falls_back_to_mirror(monkeypatch):
    """Hauptserver „too busy" → automatischer Wechsel auf den Spiegel."""
    class BusyResponse:
        def raise_for_status(self):
            raise Exception("server too busy")

    class OkResponse:
        def raise_for_status(self):
            return None

        def json(self):
            return FIXTURE

    calls = {"count": 0}

    def fake_post(*args, **kwargs):
        calls["count"] += 1
        return BusyResponse() if calls["count"] == 1 else OkResponse()

    monkeypatch.setattr(floorplan.httpx, "post", fake_post)
    result = fetch_osm_buildings(48.137, 11.575, radius=30)
    assert calls["count"] == 2
    assert len(result["features"]) == 2


def test_fetch_osm_buildings_raises_after_all_endpoints_fail(monkeypatch):
    class FailResponse:
        def raise_for_status(self):
            raise Exception("down")

    monkeypatch.setattr(floorplan.httpx, "post", lambda *a, **k: FailResponse())
    with pytest.raises(RuntimeError, match="Overpass-Abruf fehlgeschlagen"):
        fetch_osm_buildings(48.137, 11.575)


def test_geocoding_result_roundtrip():
    result = GeocodingResult("Test", 1.5, 2.5, "nominatim", confidence=0.9)
    data = result.to_dict()
    assert data["lat"] == 1.5 and data["lon"] == 2.5
    assert data["confidence"] == 0.9
