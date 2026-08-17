# 🗺️ Roadmap

| Phase | Titel | Kern-Inhalt | Status |
|-------|-------|-------------|--------|
| 0 | Infrastruktur & Setup | Monorepo, Docker/Python/Node, Gradle, USB-Berechtigungen | ✅ |
| 1 | Android-Hardware-Kernel | USB-Serial, BLE-Scanner, EKF (Kotlin), SQLite WAL | ✅ |
| 2 | Edge-Agent & Fusion | EKF, UWB-FFT, REST/WS, adaptive Rauschanpassung | ✅ |
| 3 | Android UI & Konnektivität | 3D-View, Map, WebSocket-Client, Szenarien | ✅ |
| 4 | Web-Visualizer & Multi-Client | Three.js Binary-Streaming, LOD, MQTT-Bridge, ICP | ✅ |
| **2.0** | **Datenpipeline** | Sensor→Analyse→Mesh→Umgebung→Abbildung→Evaluation | ✅ |
| **Aura 0.1** | **SDR/RTI-Kern** | IQ-Datagramm/Tunnel, WireGuard-Blueprint, FFT/Cross-Korrelation, RTI-Solver (Kotlin + Python), Gatekeeper, Tag-Geschwindigkeit, Web-Visualizer-Layer | ✅ |
| **UI/UX 1.0** | **3D-Oberflächenplan** | Detailplan: 5 Tabs, HUD, Panels, 39 Aktionen, Kamera-Modi (inkl. Röntgenblick), Gesten-Matrix, Datenbindung (docs/UI_UX_PLAN.md) | 📋 geplant |
| **Triangulation 1.0** | **WiFi/BLE-Kern (CT45P)** | Trilateration (LM), Path-Loss + Kalibrierung, Fingerprinting, Wi-Fi-RTT-Wrapper, dual-BLE-Backend, Fusion + EKF, REST/WS/Visualizer (docs/TRIANGULATION.md) | ✅ |
| Triangulation 1.1 | Honeywell-SDK-Backend | 2. BLE-Hardware-Schnittstelle via Mobility SDK, AoA/AoD-Verifikation | ⏳ |
| Triangulation 1.2 | Flottenrollout | EZConfig-/OEMConfig-Rezepte, Feldkalibrierung | ⏳ |
| **Verbesserungen 1.0** | **OS-Adoption** | Robuste Trilateration (LTS-1), RSSI-Median-/Kalman-Filter, RTI-Glättungs-Regularisierung, RTT-Responder-Priorisierung (docs/VERBESSERUNGEN.md) | ✅ |
| **Service Worker 1.0** | **Hintergrund-Analyse** | Machbarkeitsprüfung v10.2.0-Spec, WorkManager/Workbox-Korrektur, AdaptiveThresholdMonitor, BatteryHealthTracker, ExportPipeline, `/api/v1/export`, sw.js (docs/SERVICE_WORKER.md) | ✅ |
| Service Worker 1.1 | WorkManager-Anbindung | CoroutineWorker-Jobs (Anomalie/Batterie/Export) mit Constraints | ⏳ |
| Service Worker 1.2 | Export-UI + Cloud | Analyse-Tab-Anbindung, S3/R2-Upload | ⏳ |
| Service Worker 1.3 | PWA offline | three.js vendoren, IndexedDB-Queue + Background Sync | ⏳ |
| Service Worker 1.4 | OTA via MDM | Honeywell Mobility Edge (OEMConfig/SOTI) | ⏳ |
| **Network3D 1.0** | **Topologie-Engine** | Graph-Kern (Dijkstra), What-If-Failover, Time Machine, Visualizer-Layer mit Flow-Partikeln (docs/NETWORK3D.md) | ✅ |
| Network3D 1.1 | Ingest-Adapter | SNMP/K8s/Prometheus hinter der ingest-Schnittstelle | ⏳ |
| **WirelessMesh 1.0** | **Rekonstruktions-Bausteine** | Umgebungs-Selector, DriftCorrector, LoopClosure, Cluster-Merger (docs/WIRELESS_MESH.md) | ✅ |
| **Taktik 1.0** | **Map-/Szenario-Kern** | ScenarioComposer, MapVersioning, Kompression, Annotation-Templates, DeviceTracker, WS-Sync (docs/TACTICAL.md) | ✅ |
| Taktik 1.1 | Room-Entities + UI | TacticalMap/MapAnnotation-Persistenz, Annotation-UI, 2D-Overlay (Heatmap/Clustering) | ⏳ |
| **Ressourcen 1.0** | **Scan-/Fusions-Politiken** | Adaptive Scan-Raten, Energieprofile, ROI-Scanning, adaptive Voxel-Fusion, FPS-PixelRatio (docs/RESOURCE_OPT.md) | ✅ |
| Ressourcen 1.1 | Anbindung + Feldmessung | Scan-Raten-Flow in MainActivity, Einsparungsziele mit Batterystats validieren | ⏳ |
| Ressourcen 1.2 | Progressives Mesh | Grob→Fein-Hintergrundverfeinerung im Edge-Agent | ⏳ |
| **Grundriss 1.0** | **Quellen-Adapter** | Nominatim/Photon/Overpass+Spiegel, Source-Katalog (verifiziert), REST/WS, Visualizer-Layer (docs/FLOORPLAN.md) | ✅ |
| Grundriss 1.1 | KartaView-Foto-Adapter + INSPIRE/WFS | Fassaden-Texturen, Kommune-Geoportale (Berlin zuerst) | ⏳ |
| **PersonDetect 1.0** | **Radar-Kernel** | CA-CFAR, MTI-Clutter-Entfernung, Doppler-Geschwindigkeit, Multi-Target-Tracker (CV-Kalman) — Kotlin + Python (docs/PERSON_DETECTION.md) | ✅ |
| PersonDetect 1.1 | SerialManager-Anbindung + Pose-Modelle | mmWave-Targets → CFAR → Tracker; mm-Pose/mmHPE-Modell-Assets | ⏳ |
| **DeviceInteract 1.0** | **Geräte-Steuerungsebene** | DeviceRegistry (Merge/Layer/Staleness), Action-Engine (Capability-Gating), SourceMapper, REST/WS, Visualizer-Layer (docs/DEVICE_INTERACTION.md) | ✅ |
| DeviceInteract 1.1 | MainActivity-Anbindung + UI | SourceMapper ← Scanner; Layer-Bottom-Sheet; Transport-Adapter (BLE-Kommandos) | ⏳ |
| **NetworkLive 1.0** | **Aktive Netzwerkvisualisierung** | Traffic-Simulator, zentrales Farb-Mapping, Aktivitäts-/Heatmap-Aggregation, REST/WS, Visualizer-Upgrade (docs/NETWORK_LIVEVIEW.md) | ✅ |
| NetworkLive 1.1 | SNMP/NetFlow-Adapter + Zeitreihen | Adapter hinter /api/v1/network/traffic; Latenz-/Durchsatz-Diagramme | ⏳ |
| **DeviceDB 1.0** | **Offline-Gerätedatenbank** | OUI/GATT/Tracker-Kern (Python + Kotlin), Konsolidierungs-Builder, REST-Lookups (docs/DEVICE_DATABASE.md) | ✅ |
| **DeviceDB 1.1** | **Erweiterte Kategorien (v17)** | SIG-Company-IDs (verifiziert, 34 im Seed), Thread/Matter, LoRaWAN EU868, Wireless M-Bus, ISM 433, Medizin-BLE; Technologie-Filter, Company-Lookup, Visualizer-Panel (docs/DEVICE_DATABASE.md v1.1) | ✅ |
| DeviceDB 1.2 | Snapshot-Build | Builder-Lauf mit Netzwerkzugang → data/device_db.json (volle Company-IDs, Z2M, OUI) ins Repo/Asset | ⏳ |
| DeviceDB 1.3 | TTN/CSA-Import | LoRaWAN-Device-Repository (YAML-Snapshot) + CSA/Thread-Zertifizierungs-Dump | ⏳ |
| DeviceDB 1.4 | App-Integration | Erkennung in DeviceSourceMapper/DeviceRegistry, Room-Persistenz, UI-Suche (UI_UX_PLAN) | ⏳ |
| **CognitiveRadar 5.0** | **Cognitive & Neural Radar Integration** | Cognitive policy (on-device), NLOS physics (HoloRadar-style), Neural Submaps (MANG), Gaussian Splatting export, mmNorm shape reconstruction, WiFi Vision adapter. See `docs/ADVANCED_RADAR_SLAM_INTEGRATION.md` | 📋 planned |
| Aura 0.2 | SDR-USB-Treiber | RTL-SDR-v5 via USB Host (libusb-Portierung), `IqSource`-Anbindung | ⏳ |
| Aura 0.3 | VPN-Einbindung | `com.wireguard.android:tunnel` im Flavour `aura-vpn` (VpnService) | ⏳ |
| Aura 0.4 | Maps 3D Preview | Google Maps 3D SDK (Experimental): extrudierte Heatmap, RTI-Voxel, „Röntgenblick"-Kamera | ⏳ |
| Aura 0.5 | Zusatzmodule | DAB+ (libwelle/libdab), Maps Grounding Lite | ⏳ |

## Nächste Meilensteine

1. **Feldtest Phase 1** (Q3 2026) — Validierung der 5 Szenarien mit 10 BLE-Token.
2. **Hardware-Zertifizierung** — IP67/ATEX/IECEx-Konformität des CT45P-Setups.
3. **Cloud-Integration** — anonymisierter 3D-Export nach Azure/AWS für KI-Training.
4. **3dxStage-Schnittstelle** — `Transform3D` (Offset/Rotation/Skalierung) für Unity/Unreal.
5. **Aura Feldtest** — Tunnel-Link zweier CT45P mit 2× Nooelec v5 (powered USB-C-Hub), RTI-Validierung hinter Wänden.
