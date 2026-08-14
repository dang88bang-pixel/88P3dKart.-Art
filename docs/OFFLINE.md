# 🚀 Vollständige Offline-Integration (v3.x)

Ziel: Das gesamte 3dxAgent-System läuft **offline auf dem Honeywell CT45P**,
ohne externen Edge-Agent, MQTT-Broker oder Cloud.

## Portierung Python → Kotlin

| Komponente | Python (Edge) | Kotlin (CT45P) |
|------------|---------------|----------------|
| UWB-FFT (Atemfrequenz) | `uwb_processor.py` | `offline/UwbDoppler.kt` (DFT) |
| ICP-Map-Merging | `icp_merger.py` | `offline/ICPMerger.kt` (Kabsch + Jacobi-SVD) |
| Trilateration | `trilateration.py` | `triangulation/TrilaterationEngine.kt` (LSQ + Levenberg-Marquardt) |
| Madgwick-IMU | – | `offline/OpenHPSAdapter.kt#MadgwickFilter` |
| WebSocket-Server | `agent.py` (FastAPI) | `offline/LocalWebSocketServer.kt` |
| REST-Server | `agent.py` (FastAPI) | `offline/LocalApiServer.kt` |
| MQTT-Bridge | `mqtt_bridge.py` | entfällt (BLE direkt) |
| Aura RTI-Solver | `rti_solver.py` (Tikhonov/Backprojection) | `aura/RtiSolver.kt` (identisches Datenmodell) |
| Aura Heatmap | `rti_solver.py#build_heatmap` | `aura/RfHeatmapBuilder.kt` |
| Aura FFT/Korrelation | – | `aura/Fft.kt`, `aura/CrossCorrelator.kt` (offline, ohne Bibliotheken) |
| Path-Loss-Kalibrierung | `trilateration.py` | `triangulation/PathLossModel.kt` |

> Alle Aura- und Triangulations-Kernmodule sind **ohne Cloud-Anbindung**
> lauffähig (reine Kotlin-/NumPy-Mathematik); nur die SDR-Quelle (USB-Host,
> Status ⏳) und die Wi-Fi-RTT-API sind gerätegebunden.

## Smart Mesh Integrator (v3.1.0-Semantic)

```
Neue Sensorpunkte → AdaptiveOctree → Cluster → SemanticEngine → MotionDetector
      → Integrationslogik (Verschmelzung + Semantik-Konsolidierung) → Mesh
```

Dateien (Package `com.example.agent.offline`):
`VoxelNode`, `AdaptiveOctree`, `SemanticEngine`, `MotionDetector`,
`SmartMeshIntegrator`, `PoissonReconstruction`.

## Ressourcenschonung & Fehlerbehandlung

- Rauschen: Distanz-/Plausibilitätsfilter (< 50 m)
- Drift: zeitliche Gewichtung der Voxel-Verschmelzung
- Person vs. Gegenstand: Geometrie + Bewegung, Konfidenzschwelle 0.6
- Speicher: Octree max. 50k Voxel
- Threadsicherheit: `synchronized`-Locks

## UX-Spezifikation (v3.2.0-UX)

5 Hauptansichten (Live-3D, 2D-Karte, Szenarien, Analyse/Export, Einstellungen),
Touch-Gesten, Material-Design-3-Palette, Farbkodierung nach Semantik
(Person=rot, Wand=blau, Boden=grün, Möbel=braun). Details siehe `docs/UX.md`.
