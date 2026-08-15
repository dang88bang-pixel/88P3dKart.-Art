# 🧭 3dxAgent — Autonomes 3D-Kartierungs- & Lageerkennungssystem

**Version:** 4.5.0-Geo · **Zielplattform:** Honeywell CT45P + Multi-Sensor Edge-Netzwerk

Die **3dxAgent-Plattform** verwandelt das Industrie-Smartphone **Honeywell CT45P**
in ein hochpräzises, autonomes 3D-Kartierungs- und Lageerkennungssystem. Durch die
Fusion von **LiDAR, mmWave-Radar, UWB-Micro-Doppler, BLE-Token-Triangulation** und
**IMU** mit einem **adaptiven 6-DOF Extended Kalman Filter (EKF)** entsteht ein
digitaler Zwilling der Umgebung — inklusive Detektion von Personen und Objekten
hinter Mauern.

Die **v2.0.0-DataPipeline** ergänzt eine vollständige
**Sensor-/Netzwerkdaten-Evaluierungspipeline**:

```
Sensor-/Netzwerkdaten → Analyse → Mesh → 3D-Umgebung → Exakte Abbildung → Evaluierungsagent
```

Die Erweiterungen **v3.x–v4.5.0** ergänzen zudem:
- **Offline-Betrieb** — UWB-DFT, ICP/Kabsch, Madgwick-IMU, Trilateration und ein
  lokaler REST/WebSocket-Server direkt in Kotlin auf dem CT45P (Package `offline/`)
- **Smart Mesh Integrator** — adaptiver Octree, semantische Klassifikation
  (Person/Gegenstand/Wand/Boden), Bewegungsdetektion
- **Client-Regelwerk** — Anbindung beliebiger externer Geräte (Token, Relay,
  Sensor, Gateway, Wearable) mit Authentifizierung, Signalauswertung und Health-Check
- **Georeferenzierung (v4.5.0)** — optionaler `GeoAnchor` verankert die lokale
  Szene in WGS84 (`edge-agent/geo/`). Netzwerkortung ist **offline-first**
  (`GEO_OFFLINE_ONLY=true`); Online-Provider sprechen das Ichnaea-Protokoll.
  Ein Netzwerk-Fix fliesst bewusst **nicht** in den EKF — Begründung in
  `docs/GEOLOCATION_CHANGE_PLAN.md` (Entscheidung E1)
- **Externe Tracking-Feeds (v4.5.0)** — GTFS-Realtime als einzige Quelle
  (`edge-agent/external/`), gefiltert nach Radius, Alter und Qualität. Die
  Darstellung erfolgt über einen **Kontextring** statt massstäblich, weil ein
  Bus in 800 m sonst ausserhalb der 40-m-Szene läge
  (`web-visualizer/public/context-ring.js`)

---

## 📁 Monorepo-Struktur

```text
88P3dKart.-Art/
├── edge-agent/                # Python 3.11 + FastAPI + NumPy/SciPy (lauffähig)
├── web-visualizer/            # Node.js + Three.js (lauffähig)
├── android-app/               # Kotlin CT45P-App (Scaffolding, kompiliert mit Android Studio)
├── ble-token-firmware/        # nRF52 Zephyr (Scaffolding)
├── mosquitto/                 # MQTT-Broker-Konfiguration
├── nginx/                     # Reverse-Proxy (optional, HTTPS)
├── docker-compose.yml         # Orchestrierung (4 Services)
└── docs/                      # Architektur, API, Roadmap, Checkliste
```

---

## 🚀 Schnellstart

```bash
# 1) Edge-Agent lokal starten (Python)
cd edge-agent
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
uvicorn agent:app --host 0.0.0.0 --port 8080

# 2) Web-Visualizer starten (Node)
cd ../web-visualizer
npm install
node server.js            # → http://localhost:3000

# 3) Oder alles als Docker-Stack
docker compose up -d
```

```bash
# Status prüfen
curl http://localhost:8080/api/v1/agent/state

# Karten von 2 CT45P zusammenführen
curl -X POST http://localhost:8080/api/v1/agent/merge \
  -H "Content-Type: application/json" \
  -d '{"device_ids":["CT45P-01","CT45P-02"]}'

# Datenpipeline (v2.0) ausführen
curl -X POST http://localhost:8080/api/v1/pipeline/run \
  -H "Content-Type: application/json" \
  -d '{"device_id":"CT45P-01","points":[...]}'
```

---

## ✅ Tests

```bash
cd edge-agent
source .venv/bin/activate
python -m pytest tests/ -v
```

---

## 📊 Die 5 Einsatzszenarien

1. **Taktische Einsatzbesprechung** (Behörden/BOS) — 3D-Scan ohne Baupläne, Avatare, GLTF-Export
2. **Gefahren- & Evakuierungssimulation** — Rauchausbreitung, ABM, UWB-Atemdetektion
3. **Architektur & Bestandsanalyse** — LiDAR-SLAM, IFC-Export für BIM
4. **Temporäre Szenarien** — BLE-Token-Personenströme, ICP-Map-Merging
5. **Forschung & Lehre** — versionierte, wiederholbare 3D-Datensätze

Weitere Details: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md),
[`docs/EXECUTIVE_SUMMARY.md`](docs/EXECUTIVE_SUMMARY.md),
[`docs/API.md`](docs/API.md),
[`docs/ROADMAP.md`](docs/ROADMAP.md),
[`docs/CHECKLIST.md`](docs/CHECKLIST.md).

---

## 🔗 Schnittstellen

| Protokoll | Endpunkt | Zweck |
|-----------|----------|-------|
| REST (HTTPS) | `:8080/api/v1/...` | Konfiguration, Historie, Szenarien, Map-Merge, Pipeline |
| WebSocket | `:8080/ws/agent/events` | Binärer Punktwolken-Stream + JSON-Status |
| MQTT | `:1883` | BLE-Token-Rohdaten externer Smartphones |
| USB-Serial | `/dev/ttyUSB0`, `/dev/ttyACM0` | LiDAR, mmWave (CT45P Host-Modus) |
