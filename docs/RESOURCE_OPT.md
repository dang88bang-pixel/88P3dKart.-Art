# ⚡ Ressourcensparende 3D-Map-Generierung — Machbarkeitsprüfung & Integration

> **Version:** v1.0 · **Datum:** 14. August 2026 ·
> **Eingabe:** „3dxAgent – Ressourcensparende Generierung von 3D-Maps für
> den Gesamtbetrieb" (v11.0.0-ResourceOptimized)
>
> Prüfung der Umsetzbarkeit, Fehlerkatalog des Spec-Codes, Integration der
> testbaren Kernpolitiken und Einordnung der Ressourcenziele.

---

## 1. Machbarkeitsbefund

Die v11.0.0-Spezifikation beschreibt **6 Phasen** (Erfassung →
Vorverarbeitung → Fusion → Mesh → Visualisierung → Betrieb) mit konkreten
Einsparungszielen. Der Befund:

- **Adaptive Scan-Raten, ROI, Energieprofile, adaptive Voxel-Fusion** sind
  reine, JVM-testbare Politiken — übernommen.
- **Sensor Preprocessor / Progressive Mesh Generator / LOD-Viewer** sind
  JavaScript geschrieben und duplizieren vorhandene Funktionalität
  (`MeshGenerator`, LOD im UI_UX_PLAN/Web-Visualizer, `sw.js`).
- **ResourceManagementService** (Android `Service` + Broadcasts) folgt dem
  bereits etablierten Muster: Foreground Service/WorkManager
  (docs/SERVICE_WORKER.md) — übernommen wird die reine Profil-Logik.
- Die **Einsparungs-Prozentsätze** (40–60 % Akku usw.) sind Zielwerte ohne
  Messmethodik — als Ziele + Kalibrier-Workflow übernommen (Phase 6 der
  Roadmap).

## 2. Fehlerkatalog (Spec-Code, korrigiert)

| # | Spec-Code | Problem | Korrektur |
| :--- | :--- | :--- | :--- |
| 1 | `Triple(20f, 20f, 10f, 5f)` + 4-fach-Destrukturierung (`AdaptiveScanManager`) | Kotlins `Triple` hat genau **drei** Werte — kompiliert nicht | `ScanRates`-Datenklasse (`resource/ResourcePolicies.kt`) |
| 2 | Rückgabetyp `Quadruple<Float, Float, Float, Float>` (`getScanRates`) | existiert in der Kotlin-Standardbibliothek **nicht** | `ScanRates` |
| 3 | `renderer.setAnimationLoop(() => {})` zweimal im FPS-Block (`LODAware3DViewer`) | `setAnimationLoop` ist eine WebXR-API; doppelter Aufruf ohne Effekt | FPS-basiertes **PixelRatio-Management** in `web-visualizer/public/main.js` (0,75…2,0 in 0,25-Schritten) |
| 4 | `process.memoryUsage ? process.memoryUsage().heapUsed : 0` im Browser | Node-API — in Service Workern/Browsern nicht verfügbar | entfernt (wie bereits docs/SERVICE_WORKER.md §1.1 dokumentiert) |
| 5 | `calculateRatio`: `originalSize / processedSize` ohne Guard | Division durch 0 bei leerem Ergebnis (NaN/Infinity) | Guard in `savings()` (Kotlin + Python) |
| 6 | `getCacheKey` nutzt nur den ersten Voxel | schwacher Cache-Schlüssel (Kollisionen) | dokumentiert; Grid-Key-Merge in `FusionPolicy` statt Cache |
| 7 | `VoxelFusionOptimizer` dupliziert `offline/VoxelNode.mergeWith` + Octree-Lookup | Redundanz | nur die **neue** Policy (Adaption/LOD-Snap/Grid-Merge) umgesetzt; Persistenz bleibt beim Octree |
| 8 | `ROIScanManager`-Dedupe über exakte Float-Gleichheit | numerisch fragil | Toleranz 1e-3 |

## 3. Übernommene Module

### 3.1 Kotlin — Paket `com.example.agent.resource` (rein, JVM-testbar)

| Modul | Funktion |
| :--- | :--- |
| `ResourcePolicies.ScanRates` + `computeScanRates` | Bewegungszustand (4 Stufen) × Batterie-Faktor (5 Stufen) × Temperatur-Faktor (4 Stufen) → Raten + Qualität; Einsparungsstatistik vs. Baseline |
| `ResourcePolicies.determinePowerProfile` + `scanRatesForProfile`/`qualityForProfile` | Energieprofil-Automatik (EMERGENCY/POWER_SAVE/PERFORMANCE/BALANCED) nach v11-Schwellwerten |
| `RoiWeightMap` | Region-of-Interest-Scanning: Prioritätsschwelle, Kapazität (Top-N), Toleranz-Dedupe, linearer Distanz-Falloff (Basis 0,5, coerce 0,1…1,0) |
| `FusionPolicy` | Ressourcen-Adaption (Voxelgröße ×1/1,5/2, LOD 0/1/2, Konfidenzschwelle 0,3/0,4/0,5), LOD-Raster-Snapping (2^lod), konfidenz-/altersgewichtete Verschmelzung (e^(−Δt/60 s)), Batch-Fusion mit Grid-Key-Merge + 50k-Obergrenze |

### 3.2 Python-Port (`edge-agent/resource_optimizer.py`)

Identische Numerik (Scan-Raten, Profile, ROI, Fusion) — für Edge-seitige
Auswertung und als Referenz für die Kotlin-Tests.

### 3.3 Web-Visualizer

`adaptRenderQuality()`: FPS-Fenster (2 s) → PixelRatio 0,75…Geräte-Maximum
in 0,25-Schritten (Ersetzt den wirkungslosen Spec-Block; Rendering-Budget
aus Phase 5).

## 4. Ressourcenbudget (aus der Spec, als Zielwerte)

| Ressource | Budget | Ziel | Toleranz |
| :--- | :--- | :--- | :--- |
| CPU | 40 % | 30 % | ±10 % |
| RAM | 256 MB | 128 MB | ±64 MB |
| GPU | 30 % | 20 % | ±10 % |
| Akku | 500 mAh/h | 300 mAh/h | ±100 mAh/h |
| Speicher | 2 GB/Tag | 500 MB/Tag | ±500 MB |
| Netzwerk | 100 MB/h | 20 MB/h | ±10 MB/h |

> Die Prozentsätze sind **Modellziele**; echte Messwerte liefert der
> Feldtest (Roadmap R11.3: Messmethodik über `PowerProfiler`/Batterystats
> des CT45P).

## 5. Mapping zu bestehenden Komponenten

| v11-Komponente | Bestehend im Repo | Status |
| :--- | :--- | :--- |
| Batterie-Überwachung/Profile | `maintenance/BatteryHealthTracker`, `AdaptiveThresholdMonitor` | ✅ (Profil-Automatik neu) |
| Daten-Aggregation/Batching | `LiveSensorPipeline`, `AuraIntegrator` (Rebuild alle 16 Chunks) | ✅ |
| Voxel-Fusion | `offline/AdaptiveOctree`, `VoxelNode.mergeWith`, `SmartMeshIntegrator` | ✅ (Adaptions-Policy neu) |
| Mesh-Generierung/LOD | `pipeline/MeshGenerator`, LOD im Web-Visualizer + UI_UX_PLAN §11 | ✅ (progressives Mesh: Roadmap) |
| Scan-Raten-Steuerung | `BatteryManagerService`-Muster aus v10 → WorkManager-Hinweis (SERVICE_WORKER.md) | 🟡 Anbindung über Broadcast/Flow |

## 6. Verifikation

- **Python: 11 neue Tests** (Bewegungsstufen, Raten-Skalierung, Einsparungs-
  intervalle, Profil-Schwellen, ROI-Falloff/Kapazität, LOD-Snap,
  Alters-Gewichtung, Batch-Fusion).
- **Kotlin: 14 neue JVM-Tests** (gespiegelte Szenarien).
- `node --check` für den adaptiven Renderer sauber.

## 7. Roadmap

| Phase | Inhalt | Status |
| :--- | :--- | :--- |
| **R11.1** | Politik-Kerne (Scan/Profil/ROI/Fusion) Kotlin + Python, Visualizer-PixelRatio | ✅ |
| R11.2 | Anbindung: `AdaptiveScanManager`-Flow in `MainActivity` (Scan-Raten → `SerialManager`/Scanner), Broadcast-Muster durch SharedFlow ersetzen | ⏳ |
| R11.3 | Feldmessung: Einsparungsziele mit CT45P-Batterystats validieren, Politik-Parameter kalibrieren | ⏳ |
| R11.4 | Progressives Mesh (Grob→Fein-Hintergrundverfeinerung) im Edge-Agent | ⏳ |
