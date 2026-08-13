# 🧭 3dxAgent — Autonomes 3D-Kartierungs- & Lageerkennungssystem

**Version:** 4.4.0-ClientRules · **Zielplattform:** Honeywell CT45P + Multi-Sensor Edge-Netzwerk

Die **3dxAgent-Plattform** nutzt das Industrie-Smartphone **Honeywell CT45P**
als mobilen Bedien-, Enrollment- und Autorisierungsmaster. In der empfohlenen
Zielarchitektur normalisiert und fusioniert ein robustes Linux-Gateway die Daten
externer **LiDAR-, mmWave-, UWB- und BLE-Sensoren**; der CT45P visualisiert den
revisionierten Zustand und sendet signierte Benutzerintentionen. Präzisions-
oder Wanddurchdringungsfunktionen sind keine zugesicherten CT45P-Eigenschaften,
sondern benötigen geeignete externe Infrastruktur, Kalibrierung und bestandene
Feldtests.

Die **v2.0.0-DataPipeline** enthält einen prototypischen
Sensor-/Netzwerkdaten-Evaluierungspfad:

```
Sensor-/Netzwerkdaten → Analyse → Mesh → 3D-Umgebung → bewertete Abbildung → Evaluierungsagent
```

Die Erweiterungen **v3.x–v4.4.0** enthalten außerdem Prototypen für:
- **Offline-Verarbeitung** — experimentelle DFT für extern gelieferte
  UWB-Rohdaten, ICP/Kabsch, Madgwick-IMU, Multilateration und einen einfachen
  lokalen REST/WebSocket-Server im Kotlin-Package `offline/`
- **Smart Mesh Integrator** — adaptiver Octree, semantische Klassifikation
  (Person/Gegenstand/Wand/Boden), Bewegungsdetektion
- **Client-Regelwerk** — Grundmodelle für Token, Relay, Sensor, Gateway und
  Wearable; produktive Enrollment-, Authentisierungs- und Health-Verträge sind
  noch umzusetzen

---

## 📁 Monorepo-Struktur

```text
88P3dKart.-Art/
├── edge-agent/                # Python 3.11 + FastAPI + NumPy/SciPy (18 Bestandstests bestanden)
├── web-visualizer/            # Node.js + Three.js (Dependency-/Syntax-/HTTP-Smoke geprüft)
├── android-app/               # Native Kotlin/XML-CT45P-App; reproduzierbarer Build noch offen
├── ble-token-firmware/        # nRF52-Zephyr-Prototyp; Build- und Hardwaretest noch offen
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
2. **Gefahren- & Evakuierungssimulation** — Rauchausbreitung, ABM, experimentelle Auswertung externer UWB-Rohdaten
3. **Architektur & Bestandsanalyse** — LiDAR-SLAM, IFC-Export für BIM
4. **Temporäre Szenarien** — BLE-Token-Personenströme, ICP-Map-Merging
5. **Forschung & Lehre** — versionierte, wiederholbare 3D-Datensätze

Weitere Details: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md),
[**CT45P-Master-Detailarchitektur**](docs/CT45P_MASTER_ARCHITECTURE.md),
[**Alternative Implementierungen**](docs/ALTERNATIVE_IMPLEMENTATIONS.md),
[**Geräteverwaltung und Interaktionsplattform**](docs/DEVICE_MANAGEMENT_PLATFORM.md),
[**Dauerhafter Hintergrund-Abstandsalarm**](docs/BACKGROUND_DISTANCE_ALARM.md),
[**Release-Readiness-Audit**](docs/RELEASE_READINESS_AUDIT.md),
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
