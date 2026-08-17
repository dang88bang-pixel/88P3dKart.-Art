# Advanced Radar & Neural SLAM Integration for 3dxAgent / SwarmRadar

**Date:** 2026-08-17  
**Version:** v18.0.0 + Research Integration  
**Focus:** Transfer of cutting-edge research (MIT mmNorm, Rad-GS, MNE/MANG-SLAM, HoloRadar, Cognitive Radar, WiFi Vision) into the real, resource-constrained CT45P-based system while preserving the "0 simulation in critical paths" rule.

---

## 1. Executive Integration Strategy

The project already has a strong foundation:
- Adaptive 6-DOF EKF (`EkfFusion.kt`)
- mmWave + LiDAR fusion
- UWB Micro-Doppler
- Offline-capable pipelines (Kotlin + Python edge)
- Real hardware chains (no mocks)

**Core Principle for Integration:**
- Heavy neural / Gaussian Splatting work → **Edge / Master device or cloud**
- Lightweight real-time adaptation + physics models → **CT45P (on-device)**
- All production paths remain **real Android APIs + real sensor data**

---

## 2. Mapping Research to Existing Architecture

| Research | Core Idea | Current Project Component | Proposed Enhancement | Execution Location |
|----------|-----------|---------------------------|----------------------|--------------------|
| **mmNorm (MIT)** | mmWave → detailed 3D surface geometry (96% accuracy) | `SemanticEngine`, `MotionDetector`, point cloud | `MmWaveShapeEstimator` (real crude 3D bounding box + type hint from mmWave targets) | **On-device (real)** + full on Edge |
| **Rad-GS** | 4D Radar + 3D Gaussian Splatting + Doppler masking | 3D renderer + EKF | `GaussianSplattingRenderer` + Doppler-based dynamic masking (approximated via Cognitive + MotionDetector) | Web Visualizer + Edge (3DGS) |
| **MNE-SLAM / MANG-SLAM** | Distributed neural Submaps + P2P | Master/Slave + ICPMerger | Decentralized submap + neural alignment (documented) | Edge (MANG fusion) + CT45P local submaps |
| **HoloRadar** | Physics-based NLOS reconstruction | Aura RTI + UWB Doppler | `NLOSGeometry` (real UWB phase variance → ghost geometry estimate) | **On-device (real)** + full physics on Edge |
| **Cognitive Radar** | Self-adapting sensor parameters | `adaptToEnvironment()` in EKF + `FusionPolicy` | `CognitiveRadarPolicy` (real on-device: IMU + thermal + battery + UWB phase + Doppler → EKF + sensor recommendation) | **On-device (real, lightweight)** |
| **WiFi Vision** | Smartphone WiFi as radar | Existing BLE/WiFi RTT + Triangulation | `WifiVisionAdapter` (real WifiManager RSSI variance for motion hints) | **On-device (real, zero extra hardware)** |

---

## 3. Concrete Implementation Plan (Phased)

### Phase 5.1 — Cognitive Radar (On-Device, Immediate)

**Goal:** Turn the EKF into a true learning agent.

**Files to create / extend:**
- `sensors/CognitiveRadarPolicy.kt`
- Extend `EkfFusion.adaptToEnvironment(...)` and `FusionPolicy.kt`

**Real behavior (no simulation):**
```kotlin
// Example real decision
when {
    thickWallDetected() && uwbAvailable() -> switchToUwb()
    highScattering && mmWaveDopplerStrong -> increaseMmWaveFrequency()
    lowBattery -> reduceScanRateAndPreferBLE()
}
```

### Phase 5.2 — NLOS Geometry (HoloRadar-style)

- New package: `offline/NLOSReconstructor.kt`
- Uses existing UWB phase + mmWave multipath + Aura RTI data
- Outputs "ghost geometry" voxels behind walls

### Phase 5.3 — Neural Submaps + Decentralized SLAM

- `offline/NeuralSubmap.kt` (lightweight embedding or feature vectors)
- Use `MANG-SLAM` philosophy: each CT45P produces a submap
- Master/Edge fuses via improved `ICPMerger` + future neural alignment

### Phase 5.4 — Gaussian Splatting Visualization

- Primarily in **web-visualizer** (`tactical/GaussianSplatLayer.js`)
- CT45P sends compressed splat parameters or key Gaussians
- Rad-GS Doppler masking can be approximated using existing `MotionDetector` + velocity from EKF

### Phase 5.5 — mmNorm-style Shape Reconstruction

- `offline/ShapeFromMmWave.kt`
- Fits simple geometric primitives (boxes, cylinders) + surface normals from mmWave intensity + Doppler

---

## 4. Hybrid Architecture (Resource-Aware)

```
CT45P (Slave)
├── Real sensors (mmWave, UWB, IMU, BLE, WiFi)
├── Lightweight Cognitive Policy (on-device)
├── Local EKF + Submap generation
└── Compressed features / partial splats → Edge

Edge / Master Device
├── Full MANG-SLAM fusion
├── 3D Gaussian Splatting (Rad-GS)
├── HoloRadar-style NLOS physics
├── mmNorm-style detailed reconstruction
└── Global consistent map + VR/AR export (3dxStage)
```

This matches the recommendation in the research summary.

---

## 5. Updated Roadmap Entries

See `docs/ROADMAP.md` (new phase added below).

---

## 6. Files Changed / To Be Changed in This Integration

- `docs/ADVANCED_RADAR_SLAM_INTEGRATION.md` (this document)
- `docs/ROADMAP.md` — new "Cognitive & Neural Radar" phase
- `docs/ARCHITECTURE.md` — new "Cognitive Fusion Layer"
- `docs/ALGORITHMS.md` — new sections 27–32
- `android-app/app/src/main/java/com/example/agent/sensors/CognitiveRadarPolicy.kt` (new, real)
- `android-app/app/src/main/java/com/example/agent/offline/NLOSReconstructor.kt` (skeleton)
- `android-app/app/src/main/java/com/example/agent/resource/FusionPolicy.kt` (extended)
- `web-visualizer/public/tactical/GaussianSplatLayer.js` (future)

---

## 7. Implementation Guardrails (Important)

1. **Never put heavy neural/3DGS code in the main critical path on CT45P.**
2. All on-device code must remain **real hardware driven** (like current EKF, Medical drivers, UartBleBridge).
3. Use existing `FusionPolicy` and `Resource` mechanisms for adaptive behavior.
4. Keep the "0 simulation in main paths" rule sacred.

---

## 8. Next Concrete Steps (Sequential)

1. Implement `CognitiveRadarPolicy.kt` (real decision engine using existing sensor data).
2. Extend `EkfFusion` + `FusionPolicy` with cognitive hooks.
3. Add NLOS basic model using current UWB phase data.
4. Prototype Gaussian splat parameter export from Android.
5. Update visualizer with first splat layer.

This research moves the project from "advanced measurement system" to **"Cognitive SwarmRadar"** — exactly the vision described.

---

**Status (2026-08-17):** 

**Implemented — real on-device (live sensor data, zero simulation in critical paths):**
- **Cognitive Radar** → `CognitiveRadarPolicy.kt` (real IMU accel + context → EKF noise + sensor recommendation). Fully wired in `MainActivity.imuUpdates`.
- **HoloRadar-style NLOS** → `NLOSGeometry.kt` + `UwbManager` (real UWB phase buffer → `onNlosEstimate` with ghost distance).
- **mmNorm-style shape** → `MmWaveShapeEstimator.kt` (real `serialManager.mmwaveTargets` → crude 3D box + type hint).
- **WiFi Vision** → `WifiVisionAdapter.kt` (real `WifiManager` RSSI variance from periodic system scans).

**Documented / Planned (heavy computation on Edge/Visualizer):**
- Rad-GS (full 3D Gaussian Splatting + Doppler masking)
- MNE/MANG-SLAM (decentralized neural submaps + P2P fusion)
- High-accuracy mmNorm surface reconstruction

**Hybrid principle strictly followed:**
Lightweight real-time adaptation + physics hints run on CT45P using real Android sensor APIs.
Heavy neural / splatting work stays on Edge/Master (as recommended in the research summary).

See implementation commits on `arena/01a00b5e-88p3dkart-art`.