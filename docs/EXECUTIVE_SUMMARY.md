# 📌 Executive Summary

Die **3dxAgent-Plattform** verwandelt das Industrie-Smartphone **Honeywell CT45P**
(Qualcomm QCM4290, Wi-Fi 6, dual-BLE) in ein hochpräzises, autonomes
3D-Kartierungs- und Lageerkennungssystem. Durch die Fusion von **LiDAR,
mmWave-Radar, UWB-Micro-Doppler, BLE-Token-Triangulation** und **IMU** mit einem
**adaptiven 6-DOF EKF** entsteht ein digitaler Zwilling der Umgebung — inklusive
Detektion von Personen und Objekten hinter Mauern.

Die **v2.0.0-DataPipeline** erweitert die Plattform um eine vollständige
**Sensor-/Netzwerkdaten-Evaluierungspipeline** (Erfassung → Analyse → Mesh →
3D-Umgebung → Exakte Abbildung → Evaluierungsagent).

## Projekt Aura — elektromagnetische Umgebung in 3D

Aura erfasst und visualisiert die elektromagnetische Umgebung (SDR, Radio-
Tomographie, Geospatial):

- **SDR-Tunnel:** WireGuard-Blueprint (ChaCha20-Poly1305, UDP, MTU 1420) +
  IQ-Datagramme (12-Byte-Header, 704 IQ-Paare/Paket) zwischen zwei CT45P.
- **Radio-Tomographie (RTI):** Voxel-Rekonstruktion des Dämpfungsfelds
  (Tikhonov/Backprojection), Cross-Korrelation (FFT) für Laufzeit/Multipath —
  „Sehen hinter Wänden".
- **Gatekeeper:** RF-Bandklassifikation 433/868 MHz, Anomalie-Alerts,
  Port-Scan-/DNS-Heuristik.
- **Smart Tags:** Live-Geschwindigkeit aus BLE/UWB-Positionsänderungen.

Details: [`AURA.md`](AURA.md)

## WiFi-/BLE-Triangulation auf dem CT45P

Positionsbestimmung aus der CT45P-Hardware (Wi-Fi 6/802.11mc RTT, dual-BLE):

- **Wi-Fi RTT (IEEE 802.11mc):** 1–2 m Zielgenauigkeit über `WifiRttManager`
  mit Laufzeit-Feature-Checks.
- **BLE-RSSI-Triangulation:** dedizierter Scan-Kanal, kalibrierbares
  Log-Distance-Path-Loss-Modell, EMA-Glättung.
- **Fingerprinting:** gewichtetes k-NN über eingemessene RSSI-Vektoren.
- **Fusion:** Frische-Prüfung + Mahalanobis-Gate + invers-varianz-gewichteter
  Mittelwert → 6-DOF-EKF → Visualisierung (Anker-Ringe, Geräte-Marker).

Details: [`TRIANGULATION.md`](TRIANGULATION.md)

## UI/UX-Detailplan

Vollständige Spezifikation der 3D-Oberfläche: 5 Tabs, HUD, Panels, 41
Aktionen, Kamera-Modi (inkl. „Röntgenblick"), Gesten-Matrix, Datenbindung,
Zustandsmaschine und 6-Phasen-Umsetzungsplan.
Details: [`UI_UX_PLAN.md`](UI_UX_PLAN.md)

## Die 5 Einsatzszenarien

1. **Taktische Einsatzbesprechung** — 3D-Scan ohne Baupläne, Avatare, GLTF-Export.
2. **Gefahren- & Evakuierungssimulation** — Rauch, ABM, UWB-Atemdetektion (0.15–0.6 Hz).
3. **Architektur & Bestandsanalyse** — LiDAR-SLAM, IFC-Export für BIM.
4. **Temporäre Szenarien** — BLE-Token-Personenströme, ICP-Map-Merging.
5. **Forschung & Lehre** — versionierte, wiederholbare 3D-Datensätze.

## Nutzen für Stakeholder

- **Architekten:** schnelle, kostengünstige Bestandserfassung.
- **Behörden:** realistische, datenschutzkonforme Einsatzvorbereitung;
  RF-Lagebild und „Röntgenblick" für die taktische Erkundung.
- **Universitäten:** wiederholbare Ground-Truth-Datensätze für KI/ML
  (RF-Tomographie, Sensorfusion).
- **Bauunternehmen:** effiziente Baustellenlogistik & Umleitungsplanung.
- **Betreiber kritischer Infrastruktur:** Gatekeeper-Spektrumsüberwachung
  (433/868 MHz) und Wi-Fi-RTT-Positionsbestimmung in Industrieumgebungen.

## Verifikation

- **137/137 Python-Tests** (Edge-Agent: EKF, ICP, UWB, Pipeline, RTI, Trilateration).
- **167 JVM-Unit-Tests** (Kotlin-Kernmodule: IQ-Datagramm, X25519 gegen
  RFC-7748-Vektoren, FFT/Korrelation, RTI, Path-Loss, Fusions-Gate).
- End-to-End-Smoke-Tests der REST-/WebSocket-Schnittstellen (Aura, Triangulation).
