# ✅ 3dxAgent — Step-by-Step Build & Verification Checklist

**Version:** 2.0.0-DataPipeline + Aura 0.1 + Triangulation 1.0 · **Datum:** 14. August 2026
**Repository:** `dang88bang-pixel/88P3dKart.-Art` · **Branch:** `arena/01a0018e-88p3dkart-art`

**Legende:** ✅ = erledigt & verifiziert · 🧩 = geliefert (benötigt externe Hardware/SDK) ·
⚠️ = offen

> **Update v4.4.0:** Die Dokumente `88p3Kart.txt` (Versionen 2.0 → 4.4.0) wurden
> abgeglichen. Folgende **fehlende Teile** wurden ergänzt (Schritt 7–9).
>
> **Update Aura/Triangulation/UI (14.08.2026):** Dokumenten-Audit — SoC-Angaben
> auf Qualcomm QCM4290 korrigiert, Testzahlen aktualisiert (85 Python / 123 JVM),
> neue Schritte 10–12 ergänzt.

---

## 🗂️ Schritt 0 — Monorepo-Grundstruktur

| # | Aufgabe | Status |
|---|---------|--------|
| 0.1 | Verzeichnisstruktur (`edge-agent/`, `web-visualizer/`, `android-app/`, `ble-token-firmware/`, `docs/`) | ✅ |
| 0.2 | `.gitignore` (venv, node_modules, __pycache__, build, *.db, .env) | ✅ |
| 0.3 | `README.md` mit Ein-Befehl-Start und Architekturübersicht | ✅ |

## 🐍 Schritt 1 — Edge-Agent (Python, lauffähig)

| # | Aufgabe | Status |
|---|---------|--------|
| 1.1 | `config.py` | ✅ |
| 1.2 | `models.py` | ✅ |
| 1.3 | `ekf_fusion.py` | ✅ |
| 1.4 | `uwb_processor.py` | ✅ |
| 1.5 | `pointcloud_compressor.py` | ✅ |
| 1.6 | `database.py` | ✅ |
| 1.7 | `icp_merger.py` | ✅ |
| 1.8 | `pipeline.py` (**NEU v2.0**) | ✅ |
| 1.9 | `mqtt_bridge.py` | ✅ |
| 1.10 | `agent.py` | ✅ |
| 1.11 | `requirements.txt`, `Dockerfile`, `openapi.yaml` | ✅ |
| 1.12 | **Test**: `pip install` aller Abhängigkeiten | ✅ |
| 1.13 | **Test**: Unit-Tests grün (85/85 — inkl. RTI, Trilateration, Export, Topologie, Taktik, Ressourcenpolitik, Grundriss) | ✅ |
| 1.14 | **Test**: Server + REST-Endpunkte (health, state, pipeline, merge, history, aura, triangulation) | ✅ |
| 1.15 | **Test**: WebSocket Binär-/JSON-Stream + Telemetrie + Persistenz | ✅ |

## 🌐 Schritt 2 — Web-Visualizer (Node/Three.js, lauffähig)

| # | Aufgabe | Status |
|---|---------|--------|
| 2.1 | `server.js` (Express + WS-Proxy) | ✅ |
| 2.2 | `public/index.html`, `styles.css`, `main.js` | ✅ |
| 2.3 | `package.json`, `Dockerfile` | ✅ |
| 2.4 | **Test**: `npm install` + Server liefert Assets | ✅ |
| 2.5 | **Test**: WebSocket-Proxy-Chain (Browser→Viz→Agent, Binary) | ✅ |

## 🐳 Schritt 3 — Orchestrierung

| # | Aufgabe | Status |
|---|---------|--------|
| 3.1 | `docker-compose.yml` (edge-agent, web-visualizer, mosquitto, nginx) | ✅ |
| 3.2 | `mosquitto/config/mosquitto.conf` | ✅ |
| 3.3 | `nginx/nginx.conf` | ✅ |
| 3.4 | **Test**: YAML syntaktisch validiert | ✅ |
| 3.5 | **Test**: `docker compose up` | ⚠️ Docker in dieser Sandbox nicht verfügbar (Dateien validiert) |

## 📱 Schritt 4 — Android-App (Kotlin, Scaffolding)

| # | Aufgabe | Status |
|---|---------|--------|
| 4.1 | Gradle + Manifest | ✅ |
| 4.2 | Sensoren (Serial, BLE, EKF, UWB, IMU) | ✅ |
| 4.3 | Storage (Room WAL) | ✅ |
| 4.4 | Netzwerk (WS-Client, REST, Models) | ✅ |
| 4.5 | UI (3D, Map, Scenario) | ✅ |
| 4.6 | **NEU v2.0** Pipeline (6 Stufen) | ✅ |
| 4.7 | `MainActivity` | ✅ |
| 4.8 | **Build**: mit Android Studio/CT45P-SDK | 🧩 benötigt Android SDK |

## 📡 Schritt 5 — BLE-Token-Firmware (nRF52 Zephyr)

| # | Aufgabe | Status |
|---|---------|--------|
| 5.1 | `prj.conf`, `CMakeLists.txt`, `main.c` | ✅ |
| 5.2 | **Build**: mit Zephyr-Toolchain | 🧩 benötigt nRF52-Toolchain |

## 🚀 Schritt 6 — GitHub-Publikation

| # | Aufgabe | Status |
|---|---------|--------|
| 6.1 | Alle Dateien committen | ✅ |
| 6.2 | Push auf `arena/019ff93c-88p3dkart-art` (historisch; aktuelle Session: `arena/01a0018e-88p3dkart-art`) | ✅ |
| 6.3 | Pull Request öffnen (aktuell: PR #4) | ✅ |

## 🧩 Schritt 7 — Offline-Paket (v3.x, neu ergänzt)

| # | Aufgabe | Status |
|---|---------|--------|
| 7.1 | `VoxelNode.kt`, `AdaptiveOctree.kt` | ✅ |
| 7.2 | `MotionDetector.kt`, `SemanticEngine.kt`, `SmartMeshIntegrator.kt` | ✅ |
| 7.3 | `OpenHPSAdapter.kt` (Trilateration + Madgwick) | ✅ |
| 7.4 | `UwbDoppler.kt` (DFT) | ✅ |
| 7.5 | `ICPMerger.kt` (Kabsch + Jacobi-SVD) | ✅ |
| 7.6 | `LocalWebSocketServer.kt`, `LocalApiServer.kt` | ✅ |
| 7.7 | `PoissonReconstruction.kt` (vereinfacht) | ✅ |
| 7.8 | `pipeline/LiveSensorPipeline.kt` | ✅ |

## 📡 Schritt 8 — Client-Regelwerk (v4.4.0, neu ergänzt)

| # | Aufgabe | Status |
|---|---------|--------|
| 8.1 | `network/ClientModels.kt` (Typen, Capabilities, Signale) | ✅ |
| 8.2 | `network/ClientRegistry.kt`, `ClientConnectionManager.kt` | ✅ |
| 8.3 | `network/ClientHealthEvaluator.kt`, `ClientRecoveryManager.kt` | ✅ |
| 8.4 | `pipeline/SignalInterpreter.kt`, `pipeline/DataIntegrator.kt` | ✅ |
| 8.5 | `sensors/NetworkDataCollector.kt` | ✅ |

## 🔧 Schritt 9 — Utils, Ressourcen & Docs (neu ergänzt)

| # | Aufgabe | Status |
|---|---------|--------|
| 9.1 | `utils/Logger.kt`, `ErrorRecovery.kt`, `Profiler.kt` | ✅ |
| 9.2 | `colors.xml`, `strings.xml`, `themes.xml`, `file_paths.xml` | ✅ |
| 9.3 | `proguard-rules.pro`, `gradle.properties`, `build.gradle.kts` (+Java-WebSocket) | ✅ |
| 9.4 | `docs/ALGORITHMS.md`, `CLIENT_RULES.md`, `OFFLINE.md`, `UX.md` | ✅ |
| 9.5 | **Build**: Android-App mit Android-SDK | 🧩 (nicht in Sandbox kompilierbar) |

## 📡 Schritt 10 — Projekt Aura (SDR/RTI, docs/AURA.md)

| # | Aufgabe | Status |
|---|---------|--------|
| 10.1 | `aura/IqDatagram.kt` (12-Byte-Header, MTU 1420, GapTracker) | ✅ |
| 10.2 | `aura/IqTunnelReceiver.kt` (UDP, DROP_OLDEST) | ✅ |
| 10.3 | `aura/WireGuardKeys.kt` (X25519, RFC-7748-Vektoren) + `WireGuardConfig.kt` | ✅ |
| 10.4 | `aura/Fft.kt`, `CrossCorrelator.kt`, `ReferenceSignals.kt` | ✅ |
| 10.5 | `aura/RtiSolver.kt` (Tikhonov/Backprojection, Peaks) + `edge-agent/rti_solver.py` | ✅ |
| 10.6 | `aura/RfBandClassifier.kt`, `Gatekeeper.kt` | ✅ |
| 10.7 | `aura/TagVelocityTracker.kt`, `GeoPoseMapper.kt`, `RfHeatmapBuilder.kt`, `AuraIntegrator.kt` | ✅ |
| 10.8 | Integration: `LiveSensorPipeline`, `MainActivity`, `AgentWebSocketClient`, REST `/api/v1/aura/*` | ✅ |
| 10.9 | Web-Visualizer: RF-Voxel-/Heatmap-Layer + `edge-agent/aura_demo.py` | ✅ |
| 10.10 | **Test**: JVM-Unit-Tests Aura (36) + Python-Tests RTI (8) grün | ✅ |
| 10.11 | WireGuard-Bibliothek (`com.wireguard.android:tunnel`), SDR-USB-Treiber, Maps-3D-Preview | ⏳ Roadmap |

## 📶 Schritt 11 — Triangulation (Wi-Fi RTT / BLE, docs/TRIANGULATION.md)

| # | Aufgabe | Status |
|---|---------|--------|
| 11.1 | `triangulation/TrilaterationEngine.kt` (LSQ + Levenberg-Marquardt) + `trilateration.py` | ✅ |
| 11.2 | `triangulation/PathLossModel.kt` (Kalibrierung) + `RssiSmoother` | ✅ |
| 11.3 | `triangulation/WifiRttTriangulator.kt` (802.11mc, Feature-Checks) | ✅ |
| 11.4 | `triangulation/BleRadioBackend.kt` + `BleBeaconTriangulator.kt` | ✅ |
| 11.5 | `triangulation/WifiRssiFingerprinter.kt` (k-NN) | ✅ |
| 11.6 | `triangulation/TriangulationService.kt` (Fusion) + `PositionEstimate.kt`/`EstimateGate.kt` | ✅ |
| 11.7 | EKF-Anbindung (`EkfFusion.updateAbsolutePosition`), Manifest, `MainActivity` | ✅ |
| 11.8 | REST `/api/v1/triangulation/solve` + WS-Broadcast + Visualizer-Layer | ✅ |
| 11.9 | **Test**: JVM-Unit-Tests (15) + Python-Tests (8) grün, End-to-End-Broadcast | ✅ |
| 11.10 | Honeywell-SDK-Backend (2. BLE-Hardware), AoA/AoD, EZConfig/OEMConfig-Rollout | ⏳ Roadmap |

## 🎛️ Schritt 12 — UI/UX-Detailplan (docs/UI_UX_PLAN.md)

| # | Aufgabe | Status |
|---|---------|--------|
| 12.1 | Detailplan: 5 Tabs, Wireframes, HUD, Panels, 46 Aktionen, Kamera-Modi, Gesten, Datenbindung, Zustandsmaschine | ✅ |
| 12.2 | Umsetzungsphasen 0–5 mit Datei-Mapping und Definition of Done | ✅ |
| 12.3 | Umsetzung Phase 0 (Web) + Phase 1–5 (Android) | ⏳ Roadmap |

## 🔬 Schritt 13 — Verbesserungen aus ähnlichen Projekten (docs/VERBESSERUNGEN.md)

| # | Aufgabe | Status |
|---|---------|--------|
| 13.1 | Robuste Trilateration (Reject-and-Resolve, LTS-1) — Kotlin + Python | ✅ |
| 13.2 | RSSI-Filter: Median + 1D-Kalman (`RssiFilter`-Interface), Python-Äquivalente | ✅ |
| 13.3 | RTI-Glättungs-Regularisierung (Graph-Laplacian, matrixfrei) | ✅ |
| 13.4 | Wi-Fi-RTT: 802.11mc-Responder-Bevorzugung (`is80211mcResponder`) | ✅ |
| 13.5 | Machbarkeitsmatrix (LCI/LCR, TSVD, L-Curve, TV, Dead-Reckoning, Bermuda-Netz) | ✅ dokumentiert |
| 13.6 | **Test**: Python 38/38 grün; JVM-Tests gespiegelt (56) | ✅ |
| 13.7 | LCI/LCR-Auswertung, TSVD, Dead-Reckoning-Fusion | ⏳ Roadmap |

## ⚙️ Schritt 14 — Service Worker Bedarf (docs/SERVICE_WORKER.md)

| # | Aufgabe | Status |
|---|---------|--------|
| 14.1 | Machbarkeitsanalyse der v10.2.0-Spec (13 Worker, Fehlerkatalog, Mapping) | ✅ |
| 14.2 | `maintenance/AdaptiveThresholdMonitor.kt` (richtungskorrekte Schwellwerte, Spikes, Trends, Lernmodus) | ✅ |
| 14.3 | `maintenance/BatteryHealthTracker.kt` (Zyklusäquivalente, Alterung, Restlaufzeit) | ✅ |
| 14.4 | `maintenance/ExportPipeline.kt` (JSON/GeoJSON/KML + Retention) + `edge-agent/export_formats.py` | ✅ |
| 14.5 | REST `POST /api/v1/export` + OpenAPI 3.1.0 (10 Pfade) | ✅ |
| 14.6 | Web-Visualizer `sw.js` (Workbox-Strategien, Offline-Shell) + Registrierung | ✅ |
| 14.7 | **Test**: Python 44/44 grün; 20 neue JVM-Tests (77 gesamt) | ✅ |
| 14.8 | WorkManager-Anbindung, Export-UI, PWA-Volloffline, OTA-MDM | ⏳ Roadmap |

## 🌐 Schritt 15 — Network3D (docs/NETWORK3D.md)

| # | Aufgabe | Status |
|---|---------|--------|
| 15.1 | `network_topology.py`: TopologyGraph (Dijkstra), What-If-Failover, Time Machine | ✅ |
| 15.2 | REST `/api/v1/network/topology|simulate|history|devices` + WS `network_topology`/`topology_simulation` | ✅ |
| 15.3 | Web-Visualizer: Topologie-Layer (Typ-/Statusfarben, Spline-Edges, Flow-Partikel, Spatial-Alert-Pulse) | ✅ |
| 15.4 | **Test**: 8 Python-Tests (Rerouting, Unreachable, Kaskade, Replay) | ✅ |
| 15.5 | SNMP/K8s/Prometheus-Adapter, Live-Force-Layout, LOD | ⏳ Roadmap |

## 📶 Schritt 16 — Wireless Mesh (docs/WIRELESS_MESH.md)

| # | Aufgabe | Status |
|---|---------|--------|
| 16.1 | `wireless/EnvironmentModels.kt` + AdaptiveEnvironmentSelector | ✅ |
| 16.2 | `wireless/ReconstructionHelpers.kt` (DriftCorrector, LoopClosureDetector, PointClusterMerger) | ✅ |
| 16.3 | Spec-Fehlerkatalog dokumentiert (Doppelgewichtung, Referenz-Doppeladdierung, negatives Konfidenz-Intervall, Drift-als-Steigung) | ✅ |
| 16.4 | **Test**: 13 neue JVM-Tests | ✅ |
| 16.5 | SmartMeshIntegrator-Anbindung, BLE-AoA, Feldkalibrierung | ⏳ Roadmap |

## 🗺️ Schritt 17 — Taktik & Annotation (docs/TACTICAL.md)

| # | Aufgabe | Status |
|---|---------|--------|
| 17.1 | `tactical/ScenarioComposer.kt`, `MapVersioning.kt`, `ScenarioCompressor.kt`, `AnnotationTemplates.kt` | ✅ |
| 17.2 | `network/NetworkDeviceTracker.kt` (Change-/Anomalie-Erkennung) | ✅ |
| 17.3 | Python-Ports `tactical.py`, `network_tracker.py` + 13 Tests | ✅ |
| 17.4 | WS-Typen `network_devices_update`/`annotation_update` (Live-Sync) | ✅ |
| 17.5 | **Test**: Python 65/65 grün; JVM gesamt 104 | ✅ |
| 17.6 | Room-Entities, Annotation-UI, LLM-Szenariogenerator, ATAK-Roundtrip | ⏳ Roadmap |

## ⚡ Schritt 18 — Ressourcenoptimierung (docs/RESOURCE_OPT.md)

| # | Aufgabe | Status |
|---|---------|--------|
| 18.1 | `resource/ResourcePolicies.kt` (Scan-Raten, Energieprofile, Einsparungsstatistik) | ✅ |
| 18.2 | `resource/RoiWeightMap.kt` (Prioritäts-/Distanzgewichtung, Kapazität) | ✅ |
| 18.3 | `resource/FusionPolicy.kt` (Adaption, LOD-Snap, altersgewichtete Verschmelzung, Grid-Merge) | ✅ |
| 18.4 | Python-Port `resource_optimizer.py` + 11 Tests | ✅ |
| 18.5 | Web-Visualizer: FPS-basiertes PixelRatio-Management (ersetzt wirkungslosen Spec-Block) | ✅ |
| 18.6 | Spec-Fehlerkatalog (Triple/Quadruple, Node-API im Browser, Div-0, setAnimationLoop) | ✅ |
| 18.7 | **Test**: Python 76/76 grün; JVM gesamt 118 | ✅ |
| 18.8 | Scan-Raten-Anbindung in MainActivity, Feldmessung, progressives Mesh | ⏳ Roadmap |

## 🏛️ Schritt 19 — Grundriss-Integration (docs/FLOORPLAN.md)

| # | Aufgabe | Status |
|---|---------|--------|
| 19.1 | Quellen-Verifikation (Nominatim ✅, Photon ✅, Overpass ✅ via Kumi-Spiegel, KartaView ⚠️ Coverage, hoowoge.de → HOWOGE ❌, BIM Deutschland ❌, Mapzen ❌ tot, Mapillary ❌) | ✅ |
| 19.2 | `edge-agent/floorplan.py` (Geocoder, Overpass+Spiegel-Fallback, GeoJSON, KartaView-URL, Source-Katalog) | ✅ |
| 19.3 | REST `/api/v1/floorplan/sources|geocode|buildings` + WS `floorplan_buildings` | ✅ |
| 19.4 | Kotlin `floorplan/` (Source-Katalog, Overpass-Query, Parser) | ✅ |
| 19.5 | Web-Visualizer: Grundriss-Layer (Extrusion, Etagenhöhen, Labels, Toggle) | ✅ |
| 19.6 | **Test**: Python 85/85 grün; JVM gesamt 123; Live-Verifikationsprotokoll in der Doku | ✅ |
| 19.7 | KartaView-Foto-Adapter, INSPIRE/WFS-Adapter, Nominatim-Cache, FloorPlanFragment-UI | ⏳ Roadmap |
