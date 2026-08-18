# 🗺️ Flotten-Live-Dashboard (OSM) — eigene Geräte & Fahrzeuge in Echtzeit

**Zweck:** Gebundene eigene Geräte — Werkzeuge/BLE-Tokens, Handys, Fahrzeug-Flotte
(E-Bike, E-Scooter, E-Roller) — werden in Echtzeit auf einer OpenStreetMap-Karte
mit exakter Position angezeigt und können über das Mesh gezielt angesprochen
werden (Aktionen). Plug-and-play-Umkreissuche findet eigene gebundene Geräte und
BLE-Zubehör in der Nähe.

## Architektur

```
CT45P-App / Sensoren
   │  (WS fleet_position  oder  REST /api/v1/fleet/upsert)
   ▼
Edge-Agent (FleetRegistry, capability-geprüfte Aktionen)
   │  REST /api/v1/fleet*  +  WS fleet_update / fleet_action_result
   ▼
Web-Visualizer (Express-Proxy mit AGENT_TOKEN)
   │  /fleet.html + /fleet.js (Leaflet, lokal gebündelt)
   ▼
Browser: OSM-Karte mit Live-Markern, Genauigkeitskreisen, Aktionsleiste
```

## Positionsquellen (ehrlich, nach Genauigkeit)

| Quelle | Payload | Ergebnis |
|---|---|---|
| **GPS** | `lat`, `lon`, `accuracy_m` | exakte Position (Geräte-GPS, z. B. E-Bike-Tracker) |
| **Triangulation** | `local: [x, y, z]` + GeoAnchor | lokale Sensorfusion → WGS84-Projektion (Wi-Fi-RTT/BLE-Anker) |
| **BLE-Sichtung** | `rssi` | nur Distanzschätzung (Pfadverlust n=2) — in der Liste grau, bis die App trianguliert |

Der **GeoAnchor** wird einmalig gesetzt: `POST /api/v1/fleet/anchor` (Admin) mit
`lat`, `lon`, `heading_deg`, optional `local_origin`. Rückprojektion über
`geo/projection.py::enu_to_geodetic` (Bowring, iterationsfrei).

## REST-Endpunkte

| Endpunkt | Beschreibung |
|---|---|
| `POST /api/v1/fleet/anchor` | GeoAnchor setzen (Admin) |
| `GET  /api/v1/fleet/anchor` | aktueller Anker |
| `GET  /api/v1/fleet` | alle Flotten-Geräte (Position, Akku, Status, Quelle) |
| `POST /api/v1/fleet/upsert` | Batch-Ingest (GPS/Triangulation/BLE) — Geräte-Scope |
| `GET  /api/v1/fleet/nearby?lat&lon&radius_m` | Plug-and-play: eigene Flotte **und** BLE-Zubehör im Umkreis |
| `POST /api/v1/fleet/{id}/action` | Mesh-Aktion (capability-geprüft) |
| `DELETE /api/v1/fleet/{id}` | Fahrzeug entfernen (Geräte-Scope) |

## Aktionen (Capability-Modell)

| Aktion | BLE-Token/Werkzeug | Handy | E-Bike/Scooter/Roller/Fahrzeug |
|---|---|---|---|
| `read_status` | ✅ | ✅ | ✅ |
| `locate` | ✅ | ✅ | ✅ |
| `toggle_led` | ✅ | ✅ | ✅ |
| `set_visible` | ✅ | ✅ | ✅ |
| `lock` / `unlock` | ❌ (403) | ❌ (403) | ✅ |

## WebSocket

- **Client → Agent:** `{"type": "fleet_position", "payload": {device_id, id, kind, lat/lon|local|rssi, …}}`
- **Agent → Clients:** `{"type": "fleet_update", "payload": {"vehicles": [...]}}`
- **Agent → Clients:** `{"type": "fleet_action_result", "payload": {...}}`

## Visualizer starten

```bash
cd web-visualizer
npm install                     # Leaflet wird lokal gebündelt (kein CDN)
AGENT_TOKEN=<JWT> AGENT_REST_URL=http://localhost:8080 \
AGENT_WS_URL=ws://localhost:8080/ws/agent/events PORT=3000 node server.js
# → Dashboard: http://localhost:3000/fleet.html
```

`AGENT_TOKEN` erhält man über die Enrollment-Kette (Gerät „visualizer-1"):
`POST /api/v1/admin/enrollment-codes` → `POST /api/v1/enrollment/claim` → `POST /api/v1/session`.

Der REST-/WS-Proxy injiziert den Token serverseitig — im Browser liegt kein JWT.

## OSM-Nutzung

Standard-Kacheln von `tile.openstreetmap.org` mit korrekter Namensnennung
(„© OpenStreetMap-Mitwirkende"). Für Produktion mit hohem Volumen einen
konformen Tile-Provider verwenden (OSM-Tile-Usage-Policy).

## Tests

```bash
cd edge-agent
pytest tests/test_fleet.py -q              # 7 Unit-/Integrationstests
cd ..
python3 scripts/fleet_e2e_test.py          # 16 End-to-End-Checks (Agent+Visualizer live)
```
