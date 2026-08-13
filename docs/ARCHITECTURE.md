# 🏗️ Systemarchitektur

> Die verifizierte Hardwarebasis, UHAL-, Token-, Ortungs-, Security- und
> Sensorfusionsarchitektur ist ausführlich in der
> [CT45P-Master-Detailarchitektur](CT45P_MASTER_ARCHITECTURE.md) beschrieben.
> Dort werden Herstellermerkmale, Projektziele und noch offene Hardware-Gates
> getrennt ausgewiesen. Die verbindliche praktische Zielverteilung ist
> [Option C: CT45P Control Plane plus Linux-Gateway Data Plane](ALTERNATIVE_IMPLEMENTATIONS.md#5-option-c--geteilte-control-data-plane).

Die Plattform ist in drei logische Schichten unterteilt, die über
REST/WebSocket und MQTT kommunizieren.

| Schicht | Komponente | Technologie / Hardware | Funktion |
| :--- | :--- | :--- | :--- |
| **1. Edge-Sensorik/Control Plane** | Honeywell CT45P | Qualcomm QCS4290/QCM4290, Android, USB-Host/OTG | Bedienung, Enrollment und signierte Commands; interne IMU/BLE nur für freigegebene lokale Funktionen und degradierten Fallback |
| | 3dxAgent BLE-Token | nRF52840 + BMI270 (IMU) | Bewegungsbeobachtung, RSSI-Grobortung, adaptives Advertising |
| **2. Gateway/Fusions-Kernel** | Edge-Agent auf robustem Linux-Gateway | Python 3.11, FastAPI, WebSockets, NumPy/SciPy | autoritative Sensoradapter, Qualitätsbewertung und Persistenz; 6-Zustands-EKF-Prototyp, experimentelle FFT externer UWB-Rohdaten, ICP-Map-Merging, SQLite-WAL |
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
- **UWB Micro-Doppler (experimentell)** — FFT auf extern geliefertem
  Rohphasen-Ringbuffer (20 Hz, 5 s Fenster), Peak 0.15–0.6 Hz. Benötigt ein
  geeignetes externes UWB-Modul und eine eigene Validierung.
- **ICP-Map-Merging** — Kabsch-Umeyama (SVD), Toleranz 1e-6, max. 50 Iterationen.
- **Binary WebSocket** — `uint32 N + N*3 float32`, > 80 % Overhead-Reduktion.

Weitere Details in [`docs/EXECUTIVE_SUMMARY.md`](EXECUTIVE_SUMMARY.md).
