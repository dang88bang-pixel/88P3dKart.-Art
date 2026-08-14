"""Tests für die Export-Formate (docs/SERVICE_WORKER.md)."""

import json

from export_formats import (
    annotations_to_geojson,
    annotations_to_json,
    annotations_to_kml,
    apply_retention,
    points_to_geojson,
)

ANNOTATIONS = [
    {
        "id": "a1",
        "title": "Person <A> & Co.",
        "description": 'Bewegung "hinter" der Wand',
        "lon": 13.405,
        "lat": 52.52,
        "z": 1.2,
    },
    {"id": "a2", "title": "Sender 433 MHz", "description": "", "lon": 13.406, "lat": 52.521, "z": 0.5},
]


def test_geojson_feature_collection():
    geo = json.loads(annotations_to_geojson(ANNOTATIONS))
    assert geo["type"] == "FeatureCollection"
    assert len(geo["features"]) == 2
    assert geo["features"][0]["geometry"]["coordinates"] == [13.405, 52.52, 1.2]
    assert geo["features"][0]["properties"]["title"] == "Person <A> & Co."


def test_kml_escapes_xml():
    kml = annotations_to_kml(ANNOTATIONS)
    assert kml.startswith('<?xml version="1.0" encoding="UTF-8"?>')
    assert "Person &lt;A&gt; &amp; Co." in kml
    assert "Person <A> & Co." not in kml
    assert "<coordinates>13.405,52.52,1.2</coordinates>" in kml


def test_json_export():
    data = json.loads(annotations_to_json(ANNOTATIONS))
    assert data["type"] == "FeatureCollection"
    assert len(data["annotations"]) == 2


def test_points_to_geojson_multipoint():
    geo = json.loads(points_to_geojson([[1.0, 2.0, 3.0], [4.0, 5.0, 0.0]], device_id="CT45P-02"))
    geometry = geo["features"][0]["geometry"]
    assert geometry["type"] == "MultiPoint"
    assert geometry["coordinates"] == [[1.0, 2.0, 3.0], [4.0, 5.0, 0.0]]
    assert geo["features"][0]["properties"]["device_id"] == "CT45P-02"


def test_empty_annotations():
    assert json.loads(annotations_to_geojson([]))["features"] == []
    assert "<Document>\n  </Document>" in annotations_to_kml([])


def test_retention():
    now = 1_700_000_000_000
    items = [
        {"id": "old", "timestamp_ms": now - 40 * 24 * 3600 * 1000},
        {"id": "new", "timestamp_ms": now - 5 * 24 * 3600 * 1000},
    ]
    kept = apply_retention(items, retention_days=30, now_ms=now)
    assert [i["id"] for i in kept] == ["new"]
    try:
        apply_retention(items, retention_days=0)
        assert False, "sollte ValueError werfen"
    except ValueError:
        pass
