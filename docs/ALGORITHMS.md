# 🧠 Algorithmen & Implementierungstiefe (v4.1/4.3)

Vollständige mathematische Grundlagen der Kernalgorithmen. Alle sind **offline-fähig**
und **ressourcenschonend** in Kotlin implementiert (CT45P, Snapdragon 662).

## 1. Adaptiver 6-DOF EKF

Zustand `x = [x, y, z, vx, vy, vz]^T`, konstantes Geschwindigkeitsmodell:

```
F = [[I, dt·I], [0, I]]        Q = diag(0.01, 0.01, 0.01, 0.1, 0.1, 0.1)
```

Messmodell (Position, `H = [I₃ 0]`):

```
z = H·x + v
```

**Adaptives LiDAR-Rauschen:**

```
r_lidar = 0.1 · α_scattering · β_thermal
α_scattering = 1000 bei Rauch/Staub, sonst 1
β_thermal    = 1 + max(0, T-60)·0.05
```

Kalman-Gain `K = P⁻ Hᵀ (H P⁻ Hᵀ + R)⁻¹`. Implementierung: `sensors/EkfFusion.kt`
(Kotlin, manuell entrollt) und `edge-agent/ekf_fusion.py` (NumPy).

## 2. Adaptiver Octree & Voxel-Fusion

Verschmelzungsgewichtung (Alter + Konfidenz):

```
w_alt = conf_alt · (0.5 + 0.5·e^(-Δt/60000))
w_neu = conf_neu
p_neu = (p_alt·w_alt + p_neu·w_neu) / (w_alt + w_neu)
```

Implementierung: `offline/AdaptiveOctree.kt`, `offline/VoxelNode.kt`.

## 3. Semantische Klassifikation (Regelwerk)

| Typ | Bedingung | Konfidenz |
|-----|-----------|-----------|
| Person (bewegt) | h ∈ [1.2, 2.2] ∧ r < 1.5 ∧ s > 0.4 | 0.85 + 0.15·s |
| Person (stehend) | h ∈ [1.2, 2.2] ∧ r < 1.5 ∧ s ≤ 0.4 | 0.70 |
| Wand | h < 0.3 ∧ (w > 2 ∨ d > 2) | 0.80 |
| Boden | h < 0.1 ∧ V > 0.5 | 0.90 |
| Möbel | V > 0.2 ∧ s < 0.3 | 0.70 |
| Unbekannt | sonst | 0.30 |

Implementierung: `offline/SemanticEngine.kt`.

## 4. Poisson-Rekonstruktion (vereinfacht)

Poisson-Gleichung `Δφ = ∇·V`; für den CT45P als **Distanzfeld + Oberflächen-Extraktion**
an besetzten/leeren Zellgrenzen umgesetzt (`offline/PoissonReconstruction.kt`).

## 5. UWB-Micro-Doppler (DFT)

DFT im Bereich 0.15–0.6 Hz (20 Bins) auf dem Phasen-Ringbuffer (20 Hz, 5 s),
Hanning-Fenster, Konfidenz = Peak-/Gesamtenergie, gültig bei > 0.3.
Implementierung: `offline/UwbDoppler.kt` (Kotlin) und `edge-agent/uwb_processor.py` (FFT).

## 6. ICP-Map-Merging (Kabsch-Umeyama)

`H = A'ᵀ B'`, SVD `H = U S Vᵀ`, `R = V Uᵀ` (Reflexionskorrektur), `t = μB - R·μA`.
Kotlin: `offline/ICPMerger.kt` (Jacobi-SVD 3×3), Python: `edge-agent/icp_merger.py`.

## 7. Madgwick-IMU-Filter

Quaternionen-Update mit Gradientenabstieg aus Beschleunigung + Gyro-Integration.
Implementierung: `offline/OpenHPSAdapter.kt#MadgwickFilter`.

## 8. OpenHPS-Trilateration

Least-Squares `p = (AᵀA)⁻¹ Aᵀ b` aus ≥ 3 Distanzmessungen.
Implementierung: `offline/OpenHPSAdapter.kt#trilaterate`.

## 9. MotionDetector

mmWave (|v| > 1 → 1.0, > 0.2 → 0.6, sonst 0) und IMU (Abweichung von 9.81 m/s²).
Implementierung: `offline/MotionDetector.kt`.

## 10. Performance-Optimierungen

- Manuell entrollte Matrix-Operationen (EKF) → ~60 % CPU-Ersparnis
- FloatArray statt List<Float> → ~30 % RAM
- Octree-Begrenzung (Tiefe 6, max. 50k Voxel)
- Gebündelte Integration (100 ms Debounce)
- LOD im Renderer

Richtwerte (CT45P, Snapdragon 662): EKF < 5 ms, Octree-Suche < 1 ms,
Punktwolke ~28 fps, Akku ~4 h Dauer-Scan.
