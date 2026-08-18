# 🧭 3dxAgent — Autonomes 3D-Kartierungs- & Lageerkennungssystem

**Version:** 4.5.0-BT-Accessories · **Zielplattform:** Honeywell CT45P + Multi-Sensor Edge-Netzwerk + Bluetooth-Zubehör Ökosystem

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

**Mehrwert & Synergien** (docs/MEHRWERT_SYNERGIE.md) — Detaillierte Bewertung
der 5 Kernkomponenten (3D-Kartierung, Akustik, UWB, IMU, BLE-Mesh) und ihrer
Kombinations-Mehrwerte auf dem CT45P. Besondere Betonung der Hardware-Synergien
(IMU-Set, NFC, Kameras, Wi-Fi 6) und der Fusion mit Tactical Health Monitoring.

**Taktisches Stressmonitoring** (v17.2.0-TacticalOps) — Vollständige Erweiterung
für Einsatzkräfte (Polizei, Feuerwehr, Rettung, Militär):
- **TacticalHealthMonitoring** (Kotlin) — Echtzeit-Vitalmonitoring (HR, HRV, SpO2, EDA, Temp)
  + wissenschaftlich validierte Stress-Level-Klassifikation (LOW/MEDIUM/HIGH/CRITICAL)
  + Combat Readiness Score + Personnel Status (OPERATIONAL → KIA)
- **TacticalOverlay.js** (Three.js) — Farbcodierte 3D-Avatare + schwebende Vital-Labels
  + pulsierende Status-Ringe + Echtzeit-Stats-Overlay
- **TacticalDashboardFragment** — Android-UI mit RecyclerViews, Einsatz-Start/Stopp,
  Alarme, Export von Einsatzberichten
- WS-Integration: `tactical_personnel`, `tactical_alert`, `tactical_overview`
- Demo-Button + Tastenkürzel (T) im Web-Visualizer
- Offline-fähig + automatische Berichtserstellung

**Aktive Netzwerkvisualisierung** (docs/NETWORK_LIVEVIEW.md) — Live-Traffic
in der 3D-Ansicht (v14.1.0):
- **Traffic-Simulator** (seeded, Bursts, Latenz-Auslastungs-Kopplung) +
  zentrales Bandbreiten-/Latenz-Farb-Mapping (Kotlin/Python/JS identisch)
- **Aktivitäts-Aggregation** — Durchsatz je Knoten, Flusszahl, max. Latenz;
  **Bandbreiten-Heatmap** (relative Säulenhöhen)
- REST `/api/v1/network/traffic|simulate` + WS `network_traffic_update`
- Visualizer: Partikelzahl/-geschwindigkeit/-farbe ∝ Bandbreite,
  Knoten-Aktivitätspuls, Latenz-Alarm, Heatmap-Säulen

**Offline-Gerätedatenbank** (docs/DEVICE_DATABASE.md) — Erkennung von
Drahtlosgeräten ohne Cloud (v16.0.0 + v17.x):
- **OUI-Lookup** (MAC → Hersteller, 24/28/36-Bit), **GATT-Standard-
  Services** (Bluetooth-SIG-verifiziert), **Tracker-Profile** (Apple/
  Samsung/Tile/Google — mit korrigierter Tile-UUID-Zuordnung)
- **SIG-Company-IDs** (34 verifizierte Einträge inkl. aller
  v17-Korrekturen) + erweiterte Kategorien: **Thread/Matter, LoRaWAN
  (EU868), Wireless M-Bus, ISM 433, Medizin-BLE** (Seed: 71 Records)
- **DeviceDatabase-Kern** (Python + Kotlin identisch) mit Technologie-
  Filter, Frequenzband-Metadaten + **Konsolidierungs-Builder**
  (Zigbee2MQTT, Bluetooth-Numbers-DB inkl. Company-IDs, MAC-Vendor-DB
  → `data/device_db.json`)
- REST `/api/v1/devicedb/*` (Status, MAC-/Service-/Company-Lookup,
  Suche) + Visualizer-Panel „🗃️ Geräte-DB" über REST-Proxy

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
└── docs/                      # Architektur, API, Roadmap, Checkliste, BLUETOOTH_ACCESSORIES.md
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

## 🚢 Deployment (vollständig ausführbar)

```bash
./scripts/generate-secrets.sh     # Secrets + TLS + MQTT-PKI (einmalig)
cp .env.example .env              # Host, Gateway-ID, Geräte-GID setzen
./scripts/deploy.sh production    # Backup → Build → Start → Health-Verify
./scripts/deploy.sh staging       # Dev-Stack (docker-compose.dev.yml)
./scripts/deploy.sh status        # Container-Status
python3 scripts/smoke_test.py     # 40+ End-to-End-Checks gegen Live-Server
```

- **API-Referenz:** `docs/openapi.yaml` — automatisch aus dem Code generiert (49 Pfade), niemals von Hand gepflegt: `python3 scripts/generate_openapi.py`
- **WebSocket-Protokoll:** `docs/websocket-api.md` (Ist-Stand, Envelope-Format)
- **Security:** `docs/security-headers.md` (nginx-Header, Container-Härtung, JWT)
- **Monitoring:** `config/prometheus.yml` + `config/alerts.yml` — der Agent liefert echte Metriken unter `GET /api/v1/metrics` (Prometheus-Textformat)
- **CI:** `.github/workflows/main.yml` (Tests bei jedem Push) und `.github/workflows/build-apk.yml` (signierte APK, manueller Trigger)

Datenbanken werden automatisch initialisiert (SQLite): `agent.db` (Positions-/Merge-/Geo-Daten), `credentials.db` (Enrollment/Sessions), `alarms.db` (autoritative Alarme + Outbox).

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
python -m pytest tests/ -v    # 144 Tests: EKF, ICP, UWB, Pipeline, RTI, Trilateration, Export, Topologie, Taktik, Ressourcenpolitik, Grundriss, Radar, Geräteinteraktion, LiveTraffic, Gerätedatenbank (inkl. Company-IDs/Kategorien)
```

Kotlin: 172 JVM-Unit-Tests in `android-app/app/src/test/` (X25519 gegen
RFC-7748-Vektoren, IQ-Datagramm, FFT/Korrelation, RTI, Path-Loss,
Fusions-Gate) — Ausführung in Android Studio/CI (`./gradlew test`).

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
[`docs/DEVICE_INTERACTION.md`](docs/DEVICE_INTERACTION.md) (Geräteinteraktion: Registry, Action-Engine, Source-Mapper, 3D-Marker mit Kontextmenü),
[`docs/NETWORK_LIVEVIEW.md`](docs/NETWORK_LIVEVIEW.md) (Aktive Netzwerkvisualisierung: Traffic-Simulator, Farb-Mapping, Heatmap, Live-Stream),
[`docs/DEVICE_DATABASE.md`](docs/DEVICE_DATABASE.md) (Offline-Gerätedatenbank: OUI/GATT/Tracker/Company-ID-Erkennung, erweiterte Kategorien, Builder, REST-Lookups).

---

## 🔗 Schnittstellen

| Protokoll | Endpunkt | Zweck |
|-----------|----------|-------|
| REST (HTTPS) | `:8080/api/v1/...` | Konfiguration, Historie, Szenarien, Map-Merge, Pipeline, Bluetooth Zubehör |
| WebSocket | `:8080/ws/agent/events` | Binärer Punktwolken-Stream + JSON-Status + BT Zubehör Live |
| MQTT | `:1883` | BLE-Token + `bluetooth/accessories/#`, `sensors/#`, `wearables/#`, `events/#` |
| USB-Serial | `/dev/ttyUSB0`, `/dev/ttyACM0` | LiDAR, mmWave (CT45P Host-Modus) |
| BLE GATT | `8d81e7c0-b7c8-...` | Custom 3dx Service – Data Notify, Config Write, Command Write |
| BLE Standard | `0x180F, 0x180A, 0x181A, 0x180D` | Battery, Device Info, Env Sensing, Heart Rate |
| BT Classic SPP | `00001101-...` | RFCOMM für HC-05, Headset, HID Remote |

### 🆕 Bluetooth-Zubehör REST

```bash
curl http://localhost:8080/api/v1/bluetooth/accessories | jq
curl http://localhost:8080/api/v1/bluetooth/stats | jq
curl http://localhost:8080/api/v1/bluetooth/health | jq
curl http://localhost:8080/api/v1/bluetooth/accessories/aa:bb:cc:dd:ee:01 | jq
```

Details: [`docs/BLUETOOTH_ACCESSORIES.md`](docs/BLUETOOTH_ACCESSORIES.md)
