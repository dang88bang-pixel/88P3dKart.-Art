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

**Betrieb & Wartung** (docs/SERVICE_WORKER.md) ergänzt die Plattform um die
Hintergrundverarbeitung nach WorkManager-/Workbox-Standards:
- **AdaptiveThresholdMonitor** — Schwellwerte (richtungskorrekt), 3σ-Spikes,
  Trends, Kontextregeln, selbstlernende Schwellwerte
- **BatteryHealthTracker** — Zyklusäquivalente (kumulierte Entladung),
  Alterungsmodell, Restlaufzeit, Empfehlungen
- **ExportPipeline** — JSON/GeoJSON/KML + Retention (Kotlin); REST
  `POST /api/v1/export` (Python-Port)
- **Web-Visualizer `sw.js`** — Offline-App-Shell (Cache-First /
  Network-First / Stale-While-Revalidate)

**Network3D & Taktik** (docs/NETWORK3D.md, docs/WIRELESS_MESH.md,
docs/TACTICAL.md) erweitern die Plattform um Topologie-Visualisierung,
Wireless-Mesh-Rekonstruktion und taktisches Map-Management:
- **Topologie-Graph** — Nodes/Edges, Dijkstra, **What-If-Failover-Simulation**,
  **Time Machine** (Snapshot-Replay); Visualizer-Layer mit Flow-Partikeln
  und pulsierenden Spatial Alerts (3d-force-graph-Muster, nativ Three.js)
- **Wireless Mesh** — Umgebungs-Preset-Auswahl, Drift-Korrektur (Offset-EWMA),
  Loop-Closure, konfidenzgewichteter Punkt-Cluster-Merger
- **Taktik** — modulare Szenario-Komposition (Abhängigkeitsauflösung),
  Map-Versionierung (Delta-Kette), zlib-Szenario-Kompression,
  22 Annotation-Templates, Geräte-Change-/Anomalie-Tracker
- REST: `/api/v1/network/*` (Topologie, Simulate, History, Devices) +
  WS-Typen `network_topology`, `topology_simulation`, `annotation_update`

**Ressourcenoptimierung** (docs/RESOURCE_OPT.md) — Politik-Kerne für den
ressourcensparenden Gesamtbetrieb (v11.0.0):
- **Adaptive Scan-Raten** — Bewegungszustand × Batterie × Temperatur →
  Raten + Qualität + Einsparungsstatistik
- **Energieprofile** — PERFORMANCE/BALANCED/POWER_SAVE/EMERGENCY-Automatik
- **ROI-Scanning** — Prioritäts-/Distanzgewichtung relevanter Bereiche
- **Adaptive Voxel-Fusion** — Ressourcen-abhängige Voxelgröße/LOD/Konfidenz,
  altersgewichtete Verschmelzung, Grid-Key-Merge mit Obergrenze
- **Adaptive Renderqualität** — FPS-basiertes PixelRatio-Management im
  Web-Visualizer (0,75…2,0)

**Grundriss-Integration** (docs/FLOORPLAN.md) — optionale Funktion mit
**verifizierten Datenquellen** (Live-Tests am 14.08.2026):
- **Geocoding:** Nominatim (Policy-konform) + Photon-Fallback
- **Gebäude:** Overpass-API mit automatischem Kumi-Spiegel-Fallback →
  GeoJSON (Etagen, Höhen, Adressen)
- **Quellen-Katalog** mit echtem Verfügbarkeitsstatus (hoowoge.de→HOWOGE
  ohne API, Mapzen tot, BIM Deutschland Info-Portal, KartaView für
  Street-Level-Bilder)
- REST `/api/v1/floorplan/*` + WS `floorplan_buildings` +
  3D-Extrusions-Layer im Web-Visualizer

**Personen-/Gegenstandserkennung** (docs/PERSON_DETECTION.md) — Kernel der
v13-Recherche-Mechanismen (Projekt-Verifikation inklusive):
- **CA-CFAR** — adaptiver Rauschboden-Detektor (IR-UWB/RadarHPE-Mechanismus)
- **MTI-Filter** — statische Clutter-Entfernung (TI-Edge-AI-SDK-Mechanismus)
- **Doppler-Geschwindigkeit** — v = λ·Δφ/(4πT) aus Phasendifferenzen
- **Multi-Target-Tracker** — NN-Assoziation + CV-Kalman (Piecewise-White-
  Noise-Q, Zwei-Punkt-Initialisierung, Gating, Coasting)
- Kotlin (`radar/`) + Python (`radar_processing.py`) mit identischer Numerik;
  Deep-Learning-Pose-Modelle (mm-Pose/mmHPE) als Roadmap

**Geräteinteraktion** (docs/DEVICE_INTERACTION.md) — Steuerungsebene für
alle Geräte im Raum (ein-/ausblendbar, capability-geprüfte Aktionen):
- **DeviceRegistry** — Upsert mit Merge-Semantik, Layer-Sichtbarkeit
  (Kategorie-Propagation), Selektion, Staleness (ONLINE→OFFLINE)
- **DeviceActionEngine** — Capability-Gating + Standard-Aktionen
  (Status, Ortung, Sichtbarkeit, LED)
- **DeviceSourceMapper** — BLE-Token/Netzwerkgeräte/mmWave-Targets → Geräte
- REST `/api/v1/devices*` + WS `devices_update`/`device_action` +
  Geräte-Layer im Web-Visualizer (Raycast-Auswahl, Kontextmenü)

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
python -m pytest tests/ -v    # 111 Tests: EKF, ICP, UWB, Pipeline, RTI, Trilateration, Export, Topologie, Taktik, Ressourcenpolitik, Grundriss, Radar, Geräteinteraktion
```

Kotlin: 148 JVM-Unit-Tests in `android-app/app/src/test/` (X25519 gegen
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
[`docs/VERBESSERUNGEN.md`](docs/VERBESSERUNGEN.md) (Machbarkeitsanalyse & übernommene Optimierungen aus Open-Source-Projekten),
[`docs/SERVICE_WORKER.md`](docs/SERVICE_WORKER.md) (Service-Worker-Bedarfsanalyse: WorkManager/Workbox-Architektur, Wartungsmodule, Offline-Shell),
[`docs/NETWORK3D.md`](docs/NETWORK3D.md) (3D-Netzwerk-Topologie: What-If, Time Machine, Visualizer-Layer),
[`docs/WIRELESS_MESH.md`](docs/WIRELESS_MESH.md) (Wireless-Mesh-Rekonstruktion: Umgebungs-Adaption, Drift, Loop-Closure),
[`docs/TACTICAL.md`](docs/TACTICAL.md) (Taktisches Map-Management: Szenario-Komposition, Versionierung, Annotationen),
[`docs/RESOURCE_OPT.md`](docs/RESOURCE_OPT.md) (Ressourcensparende 3D-Kartierung: Scan-Politik, Energieprofile, ROI, Voxel-Fusion),
[`docs/FLOORPLAN.md`](docs/FLOORPLAN.md) (Grundriss-Integration: verifizierte Quellen, Overpass/Nominatim/Photon-Adapter, 3D-Extrusion),
[`docs/PERSON_DETECTION.md`](docs/PERSON_DETECTION.md) (Personen-/Gegenstandserkennung: Projekt-Verifikation, CA-CFAR/MTI/Doppler/Tracker),
[`docs/DEVICE_INTERACTION.md`](docs/DEVICE_INTERACTION.md) (Geräteinteraktion: Registry, Action-Engine, Source-Mapper, 3D-Marker mit Kontextmenü).

---

## 🔗 Schnittstellen

| Protokoll | Endpunkt | Zweck |
|-----------|----------|-------|
| REST (HTTPS) | `:8080/api/v1/...` | Konfiguration, Historie, Szenarien, Map-Merge, Pipeline |
| WebSocket | `:8080/ws/agent/events` | Binärer Punktwolken-Stream + JSON-Status |
| MQTT | `:1883` | BLE-Token-Rohdaten externer Smartphones |
| USB-Serial | `/dev/ttyUSB0`, `/dev/ttyACM0` | LiDAR, mmWave (CT45P Host-Modus) |
