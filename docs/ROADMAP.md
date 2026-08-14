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
