# 🧭 3dxAgent — Autonomes 3D-Kartierungs- & Lageerkennungssystem

**Version:** 4.4.0-ClientRules · **Zielplattform:** Honeywell CT45P + Multi-Sensor Edge-Netzwerk

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

Die Erweiterungen **v3.x–v4.4.0** ergänzen zudem:
- **Offline-Betrieb** — UWB-DFT, ICP/Kabsch, Madgwick-IMU, Trilateration und ein
  lokaler REST/WebSocket-Server direkt in Kotlin auf dem CT45P (Package `offline/`)
- **Smart Mesh Integrator** — adaptiver Octree, semantische Klassifikation
  (Person/Gegenstand/Wand/Boden), Bewegungsdetektion
- **Client-Regelwerk** — Anbindung beliebiger externer Geräte (Token, Relay,
  Sensor, Gateway, Wearable) mit Authentifizierung, Signalauswertung und Health-Check

**Projekt Aura** (docs/AURA.md) erweitert die Plattform um die Erfassung und
3D-Visualisierung der elektromagnetischen Umgebung:
- **SDR-Tunnel** — WireGuard-Blueprint (MTU 1420) + UDP-IQ-Datagramme
  (12-Byte-Header, 704 IQ-Paare/Paket, DROP_OLDEST-Pufferung)
- **Radio-Tomographie (RTI)** — Voxel-Rekonstruktion per Tikhonov/Backprojection
  (Kotlin `aura/`-Paket + Python-Port `edge-agent/rti_solver.py`),
  Cross-Korrelation (FFT) für Laufzeit/Multipath
- **Gatekeeper** — RF-Bandklassifikation 433/868 MHz, Anomalie-Alerts,
  Port-Scan-/DNS-Heuristik
- **Smart Tags** — Live-Geschwindigkeit aus BLE/UWB-Positionsänderungen
- **Integration** — Aura-Kanäle in `LiveSensorPipeline`, REST-Endpunkte
  `/api/v1/aura/*`, RF-Voxel-/Heatmap-Layer im Web-Visualizer

**WiFi-/BLE-Triangulation** (docs/TRIANGULATION.md) nutzt die
CT45P-Hardwarefähigkeiten (Wi-Fi 6/802.11mc RTT, dual-BLE) für die
Positionsbestimmung:
- **Wi-Fi RTT (802.11mc)** — `WifiRttManager`-Wrapper mit Feature-Checks
  (1–2 m Zielgenauigkeit)
- **BLE-RSSI-Triangulation** — dedizierter Scan-Kanal (`BleRadioBackend`),
  Path-Loss-Kalibrierung, EMA-Glättung
- **Fingerprinting** — gewichtetes k-NN über eingemessene RSSI-Vektoren
- **Sensorfusion** — Frische-Prüfung + Mahalanobis-Gate + invers-varianz-
  gewichteter Mittelwert → 6-DOF-EKF (`EkfFusion`) → WebSocket/Visualizer
- Kotlin-Paket `triangulation/` + Python-Port `edge-agent/trilateration.py`,
  REST `/api/v1/triangulation/solve`

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

```bash
# Aura-Demo: synthetische RTI-/RF-Daten an den Agent senden
# (→ Voxel + Heatmap erscheinen im Web-Visualizer)
cd edge-agent && source .venv/bin/activate
python aura_demo.py --loop 12

# Triangulation (REST-Fallback zur App)
curl -X POST http://localhost:8080/api/v1/triangulation/solve \
  -H "Content-Type: application/json" \
  -d '{"anchors":[{"id":"AP-1","x":0,"y":0,"z":0},{"id":"AP-2","x":10,"y":0,"z":0},
       {"id":"AP-3","x":10,"y":10,"z":0},{"id":"AP-4","x":0,"y":10,"z":0}],
       "distances":{"AP-1":7.07,"AP-2":7.07,"AP-3":7.07,"AP-4":7.07}}'
```

---

## ✅ Tests

```bash
cd edge-agent
source .venv/bin/activate
python -m pytest tests/ -v    # 38 Tests: EKF, ICP, UWB, Pipeline, RTI, Trilateration, robuste Filter
```

Kotlin: 56 JVM-Unit-Tests in `android-app/app/src/test/` (X25519 gegen
RFC-7748-Vektoren, IQ-Datagramm, FFT/Korrelation, RTI, Path-Loss,
Fusions-Gate) — Ausführung in Android Studio/CI (`./gradlew test`).

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
[`docs/CHECKLIST.md`](docs/CHECKLIST.md),
[`docs/AURA.md`](docs/AURA.md) (Projekt Aura — SDR/RTI/3D),
[`docs/TRIANGULATION.md`](docs/TRIANGULATION.md) (WiFi-/BLE-Triangulation auf dem CT45P),
[`docs/UI_UX_PLAN.md`](docs/UI_UX_PLAN.md) (UI/UX-Detailplan: Aktionen & Interaktionen der 3D-Oberfläche),
[`docs/VERBESSERUNGEN.md`](docs/VERBESSERUNGEN.md) (Machbarkeitsanalyse & übernommene Optimierungen aus Open-Source-Projekten).

---

## 🔗 Schnittstellen

| Protokoll | Endpunkt | Zweck |
|-----------|----------|-------|
| REST (HTTPS) | `:8080/api/v1/...` | Konfiguration, Historie, Szenarien, Map-Merge, Pipeline |
| WebSocket | `:8080/ws/agent/events` | Binärer Punktwolken-Stream + JSON-Status |
| MQTT | `:1883` | BLE-Token-Rohdaten externer Smartphones |
| USB-Serial | `/dev/ttyUSB0`, `/dev/ttyACM0` | LiDAR, mmWave (CT45P Host-Modus) |
