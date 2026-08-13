# 🏗️ Systemarchitektur

Die Plattform ist in drei logische Schichten unterteilt, die nahtlos über
REST/WebSocket und MQTT kommunizieren.

| Schicht | Komponente | Technologie / Hardware | Funktion |
| :--- | :--- | :--- | :--- |
| **1. Edge-Sensorik** | Honeywell CT45P | Snapdragon 662, USB-OTG, Android 13 | LiDAR (RPLIDAR), mmWave (TI IWR6843), UWB (Qorvo), BLE (nRF52) & IMU |
| | BLE-Token (2. Akku) | nRF52840 + BMI270 (IMU) | Bewegungsverfolgung, RSSI-Triangulation, adaptive Advertising-Rate |
| **2. Fusions-Kernel** | Edge-Agent | Python 3.11, FastAPI, WebSockets, NumPy/SciPy | 6-DOF EKF, UWB-Atemfrequenz (FFT), ICP-Map-Merging, Datenpipeline, SQLite-WAL |
| | MQTT-Bridge | Eclipse Mosquitto | Anbindung externer Smartphones (BLE-Relay) |
| **3. Visualisierung** | Android-App (CT45P) | Kotlin, OpenGL ES 2.0, Retrofit | Live-3D-Punktwolke, 2D-Karte, Szenarien-Controller |
| | Web-Visualizer | Node.js, Three.js, Binary WebSocket | Multi-Client-3D-Ansicht, LOD, Avatare, Export |

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

Weitere Details in [`docs/EXECUTIVE_SUMMARY.md`](EXECUTIVE_SUMMARY.md).
