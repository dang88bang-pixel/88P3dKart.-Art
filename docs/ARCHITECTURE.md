# 🏗️ Systemarchitektur

Die Plattform ist in drei logische Schichten unterteilt, die nahtlos über
REST/WebSocket und MQTT kommunizieren.

| Schicht | Komponente | Technologie / Hardware | Funktion |
| :--- | :--- | :--- | :--- |
| **1. Edge-Sensorik** | Honeywell CT45P | Qualcomm QCM4290 (2,0 GHz), USB-OTG, Android 11/13 (Mobility Edge™, Updates bis 15) | LiDAR (RPLIDAR), mmWave (TI IWR6843), UWB (Qorvo), BLE (nRF52) & IMU |
| | BLE-Token (2. Akku) | nRF52840 + BMI270 (IMU) | Bewegungsverfolgung, RSSI-Triangulation, adaptive Advertising-Rate |
| **2. Fusions-Kernel** | Edge-Agent | Python 3.11, FastAPI, WebSockets, NumPy/SciPy | 6-DOF EKF, UWB-Atemfrequenz (FFT), ICP-Map-Merging, Datenpipeline, SQLite-WAL |
| | MQTT-Bridge | Eclipse Mosquitto | Anbindung externer Smartphones (BLE-Relay) |
| **3. Visualisierung** | Android-App (CT45P) | Kotlin, OpenGL ES 2.0, Retrofit | Live-3D-Punktwolke, 2D-Karte, Szenarien-Controller |
| | Web-Visualizer | Node.js, Three.js, Binary WebSocket | Multi-Client-3D-Ansicht, LOD, Avatare, Export, RF-Feld-Layer (Aura) |
| **4. Aura (SDR/RTI)** | Android-App (`aura/`-Paket) | Kotlin, WireGuard-Tunnel, UDP, FFT | IQ-Datagramme, Cross-Korrelation, RTI-Voxel, Gatekeeper, Tag-Geschwindigkeit |
| | Edge-Agent | Python (`rti_solver.py`) | RTI-Rekonstruktion (Tikhonov/Backprojection), Heatmap-Aggregation |
| **5. Triangulation** | Android-App (`triangulation/`-Paket) | Kotlin, `WifiRttManager` (802.11mc), dual-BLE | Trilateration (LM), Path-Loss, Fingerprinting (k-NN), Fusion + 6-DOF-EKF |
| | Edge-Agent | Python (`trilateration.py`) | REST-Solver `/api/v1/triangulation/solve`, Positions-Broadcast |
| **6. Betrieb & Wartung** | Android-App (`maintenance/`-Paket) | Kotlin (Coroutines, WorkManager Roadmap) | Adaptive Schwellwerte, Batterie-Health, Export-Formate |
| | Edge-Agent / Web | Python (`export_formats.py`), `sw.js` | `/api/v1/export`, Offline-App-Shell (Workbox-Strategien) |
| **7. Network3D & Taktik** | Edge-Agent | Python (`network_topology.py`, `tactical.py`, `network_tracker.py`) | Topologie-Graph (Dijkstra), What-If-Failover, Time Machine, Szenario-Komposition, Map-Versionierung, Annotation-Templates |
| | Android-App | Kotlin (`wireless/`, `tactical/`, `network/`) | Umgebungs-Adaption, Drift/Loop-Closure, Cluster-Merger, Szenario-Composer, Delta-Versionierung, Kompression, DeviceTracker |
| | Web-Visualizer | Three.js-Layer | Topologie-Nodes/-Edges, Flow-Partikel, Spatial-Alert-Pulse |

---

## v2.0.0 — Datenpipeline

Die Erweiterung **2.0.0-DataPipeline** ergänzt eine sechsstufige
Evaluierungspipeline (sowohl Python im Edge-Agent als auch Kotlin auf dem CT45P):

```
Sensor-/Netzwerkdaten → Analyse → Mesh → 3D-Umgebung → Exakte Abbildung → Evaluierungsagent
```

| Stufe | Komponente (Python / Kotlin) | Funktion |
|-------|------------------------------|----------|
| 1 | `DataAcquisition` / `DataAcquisitionService` | Sensor-/Netzwerkdaten-Erfassung mit Qualitätsbewertung |
| 2 | `DataInterpreter` | Segmentierung (Boden/Wand/Person) |
| 3 | `MeshGenerator` | Delaunay-Mesh-Generierung |
| 4 | `EnvironmentReconstructor` | Grenzen, Volumen, Bodenfläche |
| 5 | `ExactMapper` | Referenz-Frame-Transformation (Transform3D) |
| 6 | `EvaluationAgent` | Konfidenz, Dichte, Mapping-Residuum |

---

## Algorithmen

- **Adaptiver 6-DOF EKF** — Zustand [x,y,z,vx,vy,vz]; bei LiDAR-Streuung
  (Rauch/Staub) wird `R` um Faktor 1000 erhöht → mmWave übernimmt.
- **UWB Micro-Doppler** — FFT auf Phasen-Ringbuffer (20 Hz, 5 s Fenster),
  Peak 0.15–0.6 Hz, Konfidenz > 30 %.
- **ICP-Map-Merging** — Kabsch-Umeyama (SVD), Toleranz 1e-6, max. 50 Iterationen.
- **Binary WebSocket** — `uint32 N + N*3 float32`, > 80 % Overhead-Reduktion.
- **Aura RTI** — Voxel-Modell + Ellipsen-Gewichtung, Tikhonov-Regularisierung
  (Conjugate-Gradient, matrixfrei) bzw. Backprojection; Kotlin- und
  Python-Implementierung mit identischem Datenmodell.
- **Aura IQ-Tunnel** — 12-Byte-Datagramm-Header (Seq + µs-Timestamp), MTU 1420,
  704 IQ-Paare/Paket, WireGuard (ChaCha20-Poly1305, UDP) als Backbone.
- **CT45P-Triangulation** — Trilateration (lineares LSQ + Levenberg-Marquardt),
  Log-Distance-Path-Loss mit Regressionskalibrierung, gewichtetes k-NN-
  Fingerprinting; Fusion über Frische-Prüfung + Mahalanobis-Gate +
  inverse Varianz, eingespeist in den 6-DOF-EKF.
- **Betrieb & Wartung** — adaptive Schwellwerte (richtungskorrekt,
  3σ-Spikes, Trends, Lernmodus), Batterie-Health (Zyklusäquivalente,
  Alterungsmodell, Restlaufzeit), Export (GeoJSON/KML/Retention); Hintergrund-
  ausführung nach WorkManager-/Workbox-Standard (docs/SERVICE_WORKER.md).
- **Network3D & Taktik** — Topologie-Graph mit Dijkstra, What-If-Failover-
  Simulation und Time-Machine-Replay; modulare Szenario-Komposition,
  Delta-Versionierung, Annotation-Templates, Geräte-Change-Tracking
  (docs/NETWORK3D.md, docs/WIRELESS_MESH.md, docs/TACTICAL.md).

Weitere Details in [`docs/EXECUTIVE_SUMMARY.md`](EXECUTIVE_SUMMARY.md),
[`docs/AURA.md`](AURA.md), [`docs/TRIANGULATION.md`](TRIANGULATION.md) und
[`docs/SERVICE_WORKER.md`](SERVICE_WORKER.md).
