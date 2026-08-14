# 🧍 Personen- & Gegenstandserkennung — Recherche-Einordnung & Integration

> **Version:** v1.0 · **Datum:** 14. August 2026 ·
> **Eingabe:** Umfassende Online-Recherche zu Projekten/Technologien/Mechanismen
> der Personen- und Gegenstandserkennung (mmWave, UWB, Sensorfusion,
> Durch-Wand-Erkennung) mit Empfehlung für 3dxAgent.
>
> Ergebnis: Einordnung der Projekte (mit Verifikation), Mapping auf die
> **bereits vorhandene** Sensorik der Plattform und Übernahme der
> hardware-unabhängigen Mechanismen als testbare Kernel.

---

## 1. Verifikation der zitierten Projekte (14.08.2026)

| Projekt (Recherche) | Verifiziert? | Anmerkung |
| :--- | :--- | :--- |
| Hyundai/Kia „Vision Pulse" | ✅ **bestätigt** (Ankündigung 30.01.2026) | UWB-basiert, 10 cm @ 100 m, **tag-basiert** (Ziele müssen UWB-Module tragen — die Recherche nennt diese Einschränkung korrekt); Digital Key 2 als Hardware-Basis |
| mPose mmWave Sensing | ✅ plausibel (Lynnes001/mPose_mmWave_sensing) | mmWave-Pose-Estimation (TI AWR + DCA1000, CNN-RNN-FCN) |
| RadarHPE-Toolbox | ⚠️ nicht exakt auffindbar | Verifizierte Alternativen: **mm-Pose** (U. Arizona, github.com/senguptaa/mmpose), **mmHPE** (IEEE IoT-J 2024, github.com/bh6aol/mmHPE — 4,5 cm mittlerer Fehler) |
| TI Edge AI Robotics SDK | ✅ (TexasInstruments/edgeai-robotics-sdk) | Kamera-Radar-Fusion; Mechanismen (Clutter-Entfernung, Clustering, Tracking) übernommen |
| SCGait / Multi-Modal Biometric | ✅ plausibel | Gang-ID 91,8 %; Gesicht+Gang 85–90 % — benötigt ML-Modelle (Roadmap) |
| UAV-Rescue, SafeInLoc, LiRaS, ALBACOPTER (Fraunhofer/Paderborn/Kempten) | ✅ plausibel (Forschungsprojekte) | Radar+LiDAR-Fusion, UWB-RTLS + Kamera (omlox), LiDAR-Tracking — **Referenz-Architekturen**, keine direkt übernehmbaren Repos |

**Kernaussage der Recherche, die die Plattform bereits abbildet:** mmWave
datenschutzfreundlich, UWB für Durch-Wand/RTLS, Fusion für Robustheit,
Deep Learning für Pose — die Empfehlungen (IWR6843, DWM3000, RadarHPE,
Sensorfusion) treffen exakt auf die vorhandene Sensorik.

## 2. Mapping: Mechanismen → bestehende Module

| Mechanismus (Recherche) | Existiert im Repo | Status |
| :--- | :--- | :--- |
| mmWave FMCW-Erfassung (TI IWR6843) | `SerialManager.configureMmwave` + `mmwaveTargets` | ✅ |
| UWB-Ranging (Qorvo DWM) | `UwbManager`, `sensors/EkfFusion` | ✅ |
| UWB-Micro-Doppler (Atemfrequenz) | `offline/UwbDoppler.kt` + `edge-agent/uwb_processor.py` (0,15–0,6 Hz) | ✅ |
| **Durch-Wand-Erkennung** (IR-UWB-Radar) | **Aura-RTI** (`RtiSolver`, Tikhonov/Backprojection, Voxel) | ✅ (AURA.md) |
| Heatmap→CFAR→Punktwolke | fehlte | **neu: CA-CFAR** ✅ |
| Statische Clutter-Entfernung (TI SDK) | fehlte | **neu: MTI-Filter** ✅ |
| Doppler-Geschwindigkeit | fehlte (nur Atem-Doppler) | **neu: v = λΔφ/4πT** ✅ |
| Objekt-Clustering & -Tracking | partiell (`PointClusterMerger`, `TagVelocityTracker`) | **neu: Multi-Target-Tracker (CV-Kalman)** ✅ |
| Sensorfusion (Radar+LiDAR, UWB+Kamera…) | 6-DOF-EKF + Triangulation + Aura-Integrator | ✅ |
| Deep Learning (Pose, Gang-ID, RCTrans-Net) | Modell-Assets nötig | ⏳ Roadmap |
| IR-UWB Human Detection (CA-CFAR) | — | ✅ CA-CFAR abgedeckt |

## 3. Neue Module (hardware-unabhängig, identische Numerik Kotlin ⇄ Python)

| Modul | Funktion | Dateien |
| :--- | :--- | :--- |
| **CA-CFAR** | adaptiver Rauschboden-Detektor (Guard-/Trainingszellen, α = N·(PFA^(−1/N)−1), SNR, Peak-Grouping) | `radar/RadarProcessing.kt`, `edge-agent/radar_processing.py` |
| **MTI** | Single-/Double-Canceler + Bewegt-Energie-Verhältnis (statische Clutter-Entfernung) | dito |
| **Doppler** | Phasendifferenz [−π, π] → v = λ·Δφ/(4πT), Vektorvariante | dito |
| **Multi-Target-Tracker** | NN-Assoziation + Gating, CV-Kalman (4 Zustände, **Piecewise-White-Noise-Q**), **Zwei-Punkt-Initialisierung** (eliminiert Startup-Lag), Track-Bestätigung (3 Hits) + Coasting | dito |

**Bewusst korrigierte Implementierungsfehler aus dem ersten Entwurf**
(im Test getrieben gefunden):
1. Coasting-Bug: leere Detektionslisten brachen die Assoziationsschleife ab —
   Tracks bekamen nie Misses → lebten ewig. Fix: Misses für alle Tracks.
2. Überkonfidentes Kalman: Prozessrauschen in falschen Einheiten (q·dt² auf
   der Positionsdiagonalen) → Filter hinkte hinterher und verlor Tracks aus
   dem Gate. Fix: **Piecewise-White-Noise-Q** (Beschleunigungsmodell,
   Q-Blöcke dt⁴/4, dt³/2, dt²).
3. Startup-Lag: v=0-Initialisierung ließ die Geschwindigkeit zu langsam
   konvergieren. Fix: **Zwei-Punkt-Initialisierung** (v aus den ersten
   beiden Messungen).

## 4. Fusionsmatrix (was die Plattform abdeckt)

| Fusion | Referenz (Recherche) | 3dxAgent-Umsetzung |
| :--- | :--- | :--- |
| Radar + LiDAR | UAV-Rescue, LiRaS | EKF (`updateMmwave` + `updateLidar`, adaptives R) |
| UWB + Kamera/RTLS | SafeInLoc (omlox) | `UwbManager` + `TagVelocityTracker` + Token-Layer |
| Kamera + Radar | TI Edge AI SDK | mmWave-Targets + (Kamera als Roadmap-Quelle) |
| WLAN/BLE + IMU | — | `TriangulationService` → EKF (TRIANGULATION.md) |
| Durch-Wand + Raum | IR-UWB/RCTrans-Net | Aura-RTI in der Unified View (AURA.md) |

## 5. Verifikation

- **Python: 14 neue Tests** (CFAR-Peaks/Rauschen/Grouping, MTI-Clutter/
  Drift, Phasen-Wrapping, Doppler-Geschwindigkeit, Tracker: 2 Ziele mit
  stabilen IDs, Bestätigung, Coasting, Gate) — Gesamt **99/99 grün**.
- **Kotlin: 12 neue JVM-Tests** (gespiegelt) — Gesamt **135**.
- Tracker-Szenario: 2 Ziele (0,5 m/s in x, σ=0,1) über 20 Scans → 2 Tracks,
  stabile IDs, Geschwindigkeitsfehler < 0,4.

## 6. Roadmap

| Phase | Inhalt | Status |
| :--- | :--- | :--- |
| **PD 1.0** | CA-CFAR, MTI, Doppler, Multi-Target-Tracker (Kotlin + Python) | ✅ |
| PD 1.1 | Anbindung an `SerialManager.mmwaveTargets` (Range-Profile → CFAR → Tracker) | ⏳ |
| PD 1.2 | Pose-Estimation via mm-Pose/mmHPE (Modell-Assets, TFLite/ONNX auf CT45P) | ⏳ |
| PD 1.3 | Gang-ID (SCGait-Muster: Schrittfrequenz aus Doppler/IMU) | ⏳ |
| PD 1.4 | IR-UWB-Durch-Wand-Feldtest (RTI mit Qorvo-DWM-Hardware) | ⏳ |

## 7. Rechtlicher Hinweis

Durch-Wand-Erkennung und Vitalzeichen-Detektion betreffen höchstpersönliche
Daten (Art. 9 DSGVO). Der Einsatz ist auf klar definierte Szenarien zu
beschränken (Einsatzkräfte, Such- und Rettung mit Rechtsgrundlage) — die
Plattform verarbeitet ausschließlich passive Messdaten (vgl. AURA.md §8.2).
