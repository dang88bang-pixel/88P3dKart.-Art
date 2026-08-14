# 🔗 API-Spezifikation

Die vollständige OpenAPI-3.0-Spezifikation liegt unter
[`edge-agent/openapi.yaml`](../edge-agent/openapi.yaml).

## REST (Port 8080)

| Methode | Endpunkt | Zweck |
|---------|----------|-------|
| GET | `/api/v1/health` | Health-Check (Status, Modus) |
| GET | `/api/v1/agent/state` | Aktueller 6-DOF-EKF-Zustand |
| POST | `/api/v1/agent/config` | Szenario starten/konfigurieren |
| GET | `/api/v1/agent/history` | 3D-Transformations-Historie |
| POST | `/api/v1/agent/merge` | ICP-Map-Merging mehrerer CT45P |
| POST | `/api/v1/pipeline/run` | v2.0-Datenpipeline ausführen |
| POST | `/api/v1/aura/rti` | Aura: RTI-Rekonstruktion (Messlinien → Voxel-Feld) |
| POST | `/api/v1/aura/heatmap` | Aura: RF-Samples → extrudierte Heatmap-Zellen |
| POST | `/api/v1/triangulation/solve` | Triangulation: Anker + Distanzen → Position (docs/TRIANGULATION.md) |
| POST/GET | `/api/v1/network/topology` | Network3D: Topologie-Ingest (Upsert) / aktuelle Topologie |
| POST | `/api/v1/network/simulate` | Network3D: What-If-Failover-Simulation |
| GET | `/api/v1/network/history` | Network3D: Time-Machine-Snapshot-Replay |
| GET | `/api/v1/network/devices` | Live-Netzwerk: Geräte des Trackers |
| GET | `/api/v1/floorplan/sources` | Grundriss: verifizierter Quellen-Katalog |
| POST | `/api/v1/floorplan/geocode` | Grundriss: Adresssuche (Nominatim→Photon) |
| POST | `/api/v1/floorplan/buildings` | Grundriss: Gebäudeumrisse via Overpass (GeoJSON + Broadcast) |
| GET | `/api/v1/devices` | Geräteinteraktion: Geräte + Layer-Konfiguration |
| POST | `/api/v1/devices/upsert` | Geräteinteraktion: Gerät upserten (Merge) + Broadcast |
| POST | `/api/v1/devices/action` | Geräteinteraktion: Capability-geprüfte Aktion |
| GET/POST | `/api/v1/devices/layers` | Geräteinteraktion: Layer lesen / Sichtbarkeit setzen |
| POST | `/api/v1/network/traffic` | Netzwerk-LiveView: Live-Traffic-Ingest (Flüsse → Broadcast) |
| POST | `/api/v1/network/traffic/simulate` | Netzwerk-LiveView: Flusssimulation auf den Topologie-Kanten |
| GET | `/api/v1/devicedb/status` | Gerätedatenbank: Status (Quelle, Größen, Company-IDs, Kategorien, Technologien) |
| GET | `/api/v1/devicedb/lookup/mac/{mac}` | Gerätedatenbank: MAC → OUI-Hersteller + Geräte |
| GET | `/api/v1/devicedb/lookup/service/{uuid}` | Gerätedatenbank: UUID → GATT/Tracker/Geräte |
| GET | `/api/v1/devicedb/lookup/company/{company_id}` | Gerätedatenbank: Company-ID (hex/dezimal) → SIG-Hersteller |
| GET | `/api/v1/devicedb/search` | Gerätedatenbank: Volltext-/Kategorie-/Technologie-Suche |
| GET | `/api/v1/devicedb/categories` | Gerätedatenbank: Kategorie-Statistik |

Der Web-Visualizer (Port 3000) proxyt alle `/api/*`-Anfragen an den
Edge-Agent (`AGENT_REST_URL`, Standard `http://localhost:8080`) — das
„🗃️ Geräte-DB"-Panel nutzt die devicedb-Endpunkte darüber.

### Beispiel: Zustand

```bash
curl http://localhost:8080/api/v1/agent/state
```

```json
{
  "x": 0.32, "y": 0.37, "z": 0.56,
  "vx": 0.0, "vy": 0.0, "vz": 0.0,
  "covariance": [[1.0, ...], ...],
  "kalman_gain_lidar": 0.91,
  "mode": "DEGRADED"
}
```

### Beispiel: Map-Merge

```bash
curl -X POST http://localhost:8080/api/v1/agent/merge \
  -H "Content-Type: application/json" \
  -d '{"device_ids":["CT45P-01","CT45P-02"]}'
```

### Beispiel: Datenpipeline (v2.0)

```bash
curl -X POST http://localhost:8080/api/v1/pipeline/run \
  -H "Content-Type: application/json" \
  -d '{"device_id":"CT45P-01","points":[0,0,0,1,0,0,1,1,0,0,1,0,0.5,0.5,2.5]}'
```

### Beispiel: Aura RTI (docs/AURA.md)

```bash
curl -X POST http://localhost:8080/api/v1/aura/rti \
  -H "Content-Type: application/json" \
  -d '{
    "device_id": "CT45P-01",
    "bounds_min": [-5, -5, 0], "bounds_max": [5, 5, 1],
    "voxel_size": 0.5, "regularization": 0.05,
    "links": [
      {"tx": [-5, 0, 0.5], "rx": [5, 0, 0.5], "attenuation_db": 2.1},
      {"tx": [0, -5, 0.5], "rx": [0, 5, 0.5], "attenuation_db": 1.8}
    ]
  }'
```

### Beispiel: Aura Heatmap

```bash
curl -X POST http://localhost:8080/api/v1/aura/heatmap \
  -H "Content-Type: application/json" \
  -d '{"device_id":"CT45P-01","cell_size_m":1.0,
       "samples":[{"x":0.3,"y":0.3,"z":0,"dbm":-45,"frequency_hz":433920000}]}'
```

### Beispiel: Triangulation

```bash
curl -X POST http://localhost:8080/api/v1/triangulation/solve \
  -H "Content-Type: application/json" \
  -d '{
    "anchors": [
      {"id": "AP-1", "x": 0, "y": 0, "z": 0},
      {"id": "AP-2", "x": 10, "y": 0, "z": 0},
      {"id": "AP-3", "x": 10, "y": 10, "z": 0},
      {"id": "AP-4", "x": 0, "y": 10, "z": 0}
    ],
    "distances": {"AP-1": 7.07, "AP-2": 7.07, "AP-3": 7.07, "AP-4": 7.07},
    "use_z": false
  }'
```

→ Position (5, 5): Schnittpunkt der vier Distanzkreise um die Anker.

### Beispiel: Network3D (Topologie + What-If)

```bash
# Topologie ingestieren (Upsert) — broadcastet an alle Visualizer
curl -X POST http://localhost:8080/api/v1/network/topology \
  -H "Content-Type: application/json" \
  -d '{
    "nodes": [
      {"id":"A","type":"router","x":0,"y":0},
      {"id":"B","type":"router","x":1,"y":0},
      {"id":"C","type":"router","x":1,"y":0},
      {"id":"D","type":"server","x":2,"y":0}
    ],
    "edges": [
      {"id":"AB","source":"A","target":"B","latency_ms":1},
      {"id":"AC","source":"A","target":"C","latency_ms":1},
      {"id":"BD","source":"B","target":"D","latency_ms":1},
      {"id":"CD","source":"C","target":"D","latency_ms":1}
    ]
  }'

# What-If: Node B fällt aus → Flow A→D wird über C reroutet
curl -X POST http://localhost:8080/api/v1/network/simulate \
  -H "Content-Type: application/json" \
  -d '{"node_id":"B","flows":[{"id":"f1","source":"A","target":"D"}]}'
```

## WebSocket (`/ws/agent/events`)

Nachrichtentypen (JSON `{type, payload}`):

| type | Richtung | Beschreibung |
|------|----------|--------------|
| `handshake` | → | `device_id` setzen |
| `lidar` | → | LiDAR-Punktwolke (→ Binary-Broadcast) |
| `mmwave` | → | mmWave-Targets |
| `ble` | → | BLE-Token-Updates |
| `uwb_phase` | → | UWB-Phase für Micro-Doppler |
| `telemetry` | → | Batterie/Temperatur/Scattering |
| `aura_voxels` | → / ← | RTI-Voxel-Feld (App → Agent → Visualizer) |
| `aura_heatmap` | → / ← | Extrudierte RF-Heatmap-Zellen |
| `position_update` | → / ← | Fusionierte Triangulations-Position (RTT/BLE) |
| `triangulation_anchors` | → / ← | Anker-Konfiguration für Karte/Visualizer |
| `network_topology` | ← | Live-Topologie-Broadcast (→ Visualizer) |
| `topology_simulation` | ← | What-If-Failover-Ergebnis |
| `network_devices_update` | → | Scan-Zyklus → Tracker → Broadcast `network_devices` |
| `annotation_update` | → / ← | Kollaborative Annotation (Live-Sync) |
| `floorplan_buildings` | ← | Gebäude-GeoJSON (→ Grundriss-Layer im Visualizer) |
| `devices_update` | → / ← | Geräte-Ingest (DeviceSync) / Registry-Broadcast |
| `device_action` | → | Client → Agent: Geräteaktion ausführen |
| `device_action_result` | ← | Ergebnis der Geräteaktion (HUD) |
| `network_traffic` | → | Live-Traffic-Ingest (Flüsse) |
| `network_traffic_update` | ← | Flüsse + Aktivität + Heatmap (→ LiveView-Layer) |

Binary-Ausgabe: Punktwolke `[N (uint32 LE), N*3 float32]`.

## MQTT (Port 1883)

Topic `ble/tokens/<device_id>` — JSON-Payload mit `mac`, `rssi`, `accel_*`, `battery`.
