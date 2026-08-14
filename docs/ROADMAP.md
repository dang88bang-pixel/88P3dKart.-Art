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
