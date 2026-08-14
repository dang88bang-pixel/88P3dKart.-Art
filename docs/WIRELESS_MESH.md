# 📶 Wireless Mesh Reconstructor — Machbarkeitsprüfung & Integration

> **Version:** v1.0 · **Datum:** 14. August 2026 ·
> **Eingabe:** „3dxAgent – Wireless Mesh Reconstructor" (v8.0.0 / v8.1.0)
>
> Die Spec beschreibt die Rekonstruktion einer 3D-Umgebung aus
> WiFi/BLE-Daten. Der Großteil der beschriebenen Funktionen existiert in der
> Plattform bereits — dieses Dokument ordnet zu, korrigiert Fehler im
> Spec-Code und integriert die fehlenden, testbaren Bausteine.

---

## 1. Mapping: Spec → bestehende Implementierung

| Spec-Komponente (v8.x) | Existiert bereits | Status |
| :--- | :--- | :--- |
| RSSI → Distanz (Path-Loss, Kalibrierung) | `triangulation/PathLossModel.kt` (Regression + R²) | ✅ |
| RSSI-Filter (Median, EMA, Kalman) | `triangulation/RssiFilter`-Familie | ✅ |
| Trilateration/Multilateration | `triangulation/TrilaterationEngine.kt` (LSQ + Levenberg-Marquardt, LTS-1-robust) | ✅ |
| Wi-Fi RTT / BLE-Scans | `triangulation/WifiRttTriangulator.kt`, `BleBeaconTriangulator.kt` | ✅ |
| Voxel-Fusion (Octree) | `offline/AdaptiveOctree.kt`, `offline/SmartMeshIntegrator.kt` | ✅ |
| Poisson-Rekonstruktion / Mesh | `offline/PoissonReconstruction.kt`, `pipeline/MeshGenerator.kt` (Delaunay) | ✅ |
| 3D-Visualisierung (Three.js, semantische Farben) | `web-visualizer/public/main.js` | ✅ |
| **Umgebungs-Adaption** (bestes Path-Loss-Preset wählen) | fehlte | **neu: `wireless/EnvironmentModels.kt`** ✅ |
| **Drift-Korrektur** | fehlte | **neu (korrigiert): `wireless/ReconstructionHelpers.kt#DriftCorrector`** ✅ |
| **Loop-Closure** | fehlte | **neu: `LoopClosureDetector`** ✅ |
| **Punkt-Cluster-Merger** (gewichtete Voxel-Kandidaten) | partiell (`SmartMeshIntegrator`) | **neu: `PointClusterMerger`** ✅ |
| BLE AoA/AoD (Submeter) | Vendor-Exposure nötig | ⏳ (wie docs/TRIANGULATION.md §2.5) |

## 2. Fehlerkatalog (im Spec-Code gefunden & korrigiert)

| # | Spec-Code (v8.0/8.1) | Problem | Korrektur |
| :--- | :--- | :--- | :--- |
| 1 | `Trilateration.trilaterate`: Gewichte in A **und** in AᵀWA | Doppelte Gewichtung (w²) | `TrilaterationEngine` gewichtet einmalig (1/σ²) |
| 2 | `x = result[0] + ref.x` nach LSQ | Das lineare System (A = 2(aᵢ−a₀), b = d₀²−dᵢ²+‖aᵢ‖²−‖a₀‖²) löst **bereits** die absolute Position — Referenz wird doppelt addiert | `TrilaterationEngine` addiert nichts |
| 3 | `environmentConfidence = 1f − bestScore` | Kann negativ werden | begrenzt auf 0,3…1,0 |
| 4 | `RssiToDistance.convert(rssi, txPower)` | Parameter `txPower` wird nie verwendet | entfernt; Modell nutzt Preset-Referenz-RSSI |
| 5 | Drift = **Steigung** der Positionshistorie | Eine Steigung ist Bewegung, kein Drift (Offset) | `DriftCorrector` schätzt den geglätteten **Offset** Mess−Referenz (EWMA, begrenzt) |
| 6 | `detectChargeCycles`: `level === 100 && charging` | zählt nur Vollladungen | bereits korrigiert in `maintenance/BatteryHealthTracker.kt` (kumulierte Entladung) |

## 3. Neue Kotlin-Module (`com.example.agent.wireless`)

| Modul | Funktion |
| :--- | :--- |
| `EnvironmentModels` + `AdaptiveEnvironmentSelector` | 6 Umgebungs-Presets (Freiraum→Industrie), RSSI-Konfidenzstaffel, Best-Fit-Auswahl über mittleren relativen Fehler |
| `DriftCorrector` | Offset-EWMA mit Begrenzung (Korrekturkonzept aus §2.5) |
| `LoopClosureDetector` | Wiederbesuch-Erkennung (Distanzschwelle) → Korrektur-Offset |
| `PointClusterMerger` | Greedy-Clustering im Merge-Radius, konfidenzgewichteter Schwerpunkt, dominante Quelle |

Verifiziert: 9 JVM-Unit-Tests (Preset-Auswahl unter Rauschen, Drift-Offset + Begrenzung,
Loop-Closure, Cluster-Gewichtung, Konfidenzschwelle).

## 4. Einordnung zur Recherche (BLE-Positionierung)

Die bereits übernommenen Verbesserungen (Median-/Kalman-Filter, robuste
Trilateration, Kalibrierungs-Regression) stammen aus derselben
Projektfamilie, die auch der Wireless-Mesh-Ansatz nutzt
(Bermuda/neXenio-Multi-Beacon-Muster, MDPI-Studien) — siehe
[VERBESSERUNGEN.md](VERBESSERUNGEN.md).

**Wichtigste Erkenntnis für den Wireless-Mesh-Anspruch:** RSSI-Rekonstruktion
allein liefert Strukturen nur mit 3–8 m Auflösung — die Spec selbst nennt
diese Werte. Der Mehrwert der Plattform liegt in der **Fusion**
(Triangulation → EKF → Octree), nicht in der Roh-Rekonstruktion; der
`PointClusterMerger` speist genau diese Kette.

## 5. Roadmap

| Phase | Inhalt |
| :--- | :--- |
| WM 1.0 | Environment-Selector, DriftCorrector, LoopClosureDetector, PointClusterMerger | ✅ |
| WM 1.1 | Anbindung an `SmartMeshIntegrator` (Cluster-Merger → Octree) in `MainActivity` | ⏳ |
| WM 1.2 | BLE-AoA (Honeywell-SDK-Exposure, Submeter) | ⏳ |
| WM 1.3 | Feldkalibrierung je Umgebung (Preset-Auswahl mit echten Messdaten) | ⏳ |
