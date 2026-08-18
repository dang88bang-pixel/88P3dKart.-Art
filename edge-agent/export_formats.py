"""Export & Format-Konvertierung — Python-Port der Kotlin-Module
(`com.example.agent.maintenance.ExportPipeline`, docs/SERVICE_WORKER.md).

Erzeugt GeoJSON (RFC 7946), KML (OGC 2.2) und JSON aus Annotationen/
Punktlisten sowie eine Retention-Funktion — identisches Verhalten zur
Kotlin-Implementierung (inkl. XML-/JSON-Escaping).
"""

from __future__ import annotations

import json
import time
from typing import Any, Dict, List, Optional
from xml.sax.saxutils import escape as xml_escape


def _json_escape(value: str) -> str:
    return json.dumps(value, ensure_ascii=False)[1:-1]


def annotations_to_geojson(annotations: List[Dict[str, Any]]) -> str:
    """Annotationen → GeoJSON FeatureCollection (RFC 7946)."""
    features = []
    for a in annotations:
        features.append(
            {
                "type": "Feature",
                "geometry": {
                    "type": "Point",
                    "coordinates": [
                        float(a.get("lon", 0.0)),
                        float(a.get("lat", 0.0)),
                        float(a.get("z", 0.0)),
                    ],
                },
                "properties": {
                    "id": str(a.get("id", "")),
                    "title": str(a.get("title", "")),
                    "description": str(a.get("description", "")),
                },
            }
        )
    return json.dumps(
        {"type": "FeatureCollection", "features": features},
        ensure_ascii=False,
        indent=2,
    )


def annotations_to_kml(annotations: List[Dict[str, Any]]) -> str:
    """Annotationen → KML-Dokument (OGC KML 2.2) mit XML-Escaping."""
    placemarks = []
    for a in annotations:
        title = xml_escape(str(a.get("title", "")))
        description = xml_escape(str(a.get("description", "")))
        lon = float(a.get("lon", 0.0))
        lat = float(a.get("lat", 0.0))
        z = float(a.get("z", 0.0))
        placemarks.append(
            "    <Placemark>\n"
            f"      <name>{title}</name>\n"
            f"      <description>{description}</description>\n"
            "      <Point>\n"
            f"        <coordinates>{lon},{lat},{z}</coordinates>\n"
            "      </Point>\n"
            "    </Placemark>"
        )
    body = "\n".join(placemarks)
    document = f"  <Document>\n{body}\n  </Document>\n" if body else "  <Document>\n  </Document>\n"
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<kml xmlns="http://www.opengis.net/kml/2.2">\n'
        + document
        + "</kml>\n"
    )


def annotations_to_json(annotations: List[Dict[str, Any]], pretty: bool = True) -> str:
    """Annotationen → JSON (Exportformat der Plattform)."""
    return json.dumps(
        {"type": "FeatureCollection", "annotations": annotations},
        ensure_ascii=False,
        indent=2 if pretty else None,
    )


def points_to_geojson(points: List[List[float]], device_id: str = "CT45P-01") -> str:
    """Punktliste [[x, y, z], ...] → GeoJSON MultiPoint."""
    coordinates = [[float(p[0]), float(p[1]), float(p[2])] for p in points if len(p) >= 3]
    return json.dumps(
        {
            "type": "FeatureCollection",
            "features": [
                {
                    "type": "Feature",
                    "geometry": {"type": "MultiPoint", "coordinates": coordinates},
                    "properties": {"device_id": device_id},
                }
            ],
        },
        ensure_ascii=False,
        indent=2,
    )


def apply_retention(
    items: List[Dict[str, Any]],
    retention_days: int,
    now_ms: Optional[int] = None,
) -> List[Dict[str, Any]]:
    """Retention: behält nur Einträge der letzten [retention_days] Tage."""
    if retention_days <= 0:
        raise ValueError("retention_days muss > 0 sein")
    cutoff = (now_ms if now_ms is not None else int(time.time() * 1000)) - retention_days * 24 * 3600 * 1000
    return [item for item in items if item.get("timestamp_ms", 0) >= cutoff]


# ─── Mesh-Exporte (reine Textformate, verlustfrei) ────────────────────────────

def points_to_obj(points: List[List[float]], name: str = "3dxagent") -> str:
    """Wavefront OBJ: Punktwolke als Vertex-Liste (keine Faces ohne Topologie)."""
    lines = [f"# 3dxAgent point cloud export ({len(points)} points)", f"o {name}"]
    for p in points:
        lines.append(f"v {_fmt(p[0])} {_fmt(p[1])} {_fmt(p[2])}")
    return "\n".join(lines) + "\n"


def points_to_ply(points: List[List[float]]) -> str:
    """Polygon File Format (ASCII): Standardformat für Punktwolken-Werkzeuge."""
    header = (
        "ply\nformat ascii 1.0\n"
        f"element vertex {len(points)}\n"
        "property float x\nproperty float y\nproperty float z\n"
        "end_header\n"
    )
    body = "\n".join(f"{_fmt(p[0])} {_fmt(p[1])} {_fmt(p[2])}" for p in points)
    return header + body + "\n"


def points_to_stl(points: List[List[float]], name: str = "3dxagent") -> str:
    """STL (ASCII): pro Punkt ein degeneriertes Dreieck — von STL-Viewern lesbar.

    Hinweis: STL kennt keine nackten Punkte; degenerierte Facetten sind die
    etablierte Konvention für reine Punktwolken-Exporte.
    """
    lines = [f"solid {name}"]
    for p in points:
        x, y, z = _fmt(p[0]), _fmt(p[1]), _fmt(p[2])
        lines.append(
            f"  facet normal 0 0 0\n    outer loop\n"
            f"      vertex {x} {y} {z}\n"
            f"      vertex {x} {y} {z}\n"
            f"      vertex {x} {y} {z}\n"
            f"    endloop\n  endfacet"
        )
    lines.append(f"endsolid {name}")
    return "\n".join(lines) + "\n"


def _fmt(value: float) -> str:
    return f"{float(value):.6f}"
