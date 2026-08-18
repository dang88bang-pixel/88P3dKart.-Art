#!/usr/bin/env python3
"""Generiert docs/openapi.yaml aus dem ECHTEN FastAPI-Schema (keine Handpflege)."""
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "edge-agent"))

from agent import app  # noqa: E402

schema = app.openapi()
schema["info"]["title"] = "88P3dKart.-Art Edge-Agent API"
schema["info"]["description"] = (
    "Automatisch generiertes OpenAPI-Schema des Edge-Agents (Ist-Stand, "
    "inkl. Alarme, Bluetooth-Zubehör, Devicedb, Floorplan, Netzwerk, "
    "Triangulation, Szenarien, Export und Metriken)."
)
schema["info"]["version"] = "2.0.0"

# Bearer-Sicherheit global deklarieren (im App-Code via Depends umgesetzt)
schema.setdefault("components", {})["securitySchemes"] = {
    "BearerAuth": {
        "type": "http",
        "scheme": "bearer",
        "bearerFormat": "JWT",
        "description": "JWT aus POST /api/v1/session (Gerät) bzw. Admin-Bootstrap-Token.",
    }
}
schema["security"] = [{"BearerAuth": []}]

out = ROOT / "docs" / "openapi.yaml"
out.parent.mkdir(exist_ok=True)
out.write_text(
    yaml.safe_dump(schema, sort_keys=False, allow_unicode=True, width=120),
    encoding="utf-8",
)
print(f"docs/openapi.yaml geschrieben ({len(schema['paths'])} Pfade)")
