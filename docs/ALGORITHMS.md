# 🧠 Algorithmen & Implementierungstiefe (v4.1/4.3 + Aura/Triangulation)

Vollständige mathematische Grundlagen der Kernalgorithmen. Alle sind **offline-fähig**
und **ressourcenschonend** in Kotlin implementiert (CT45P, Qualcomm QCM4290).

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

Richtwerte (CT45P, Qualcomm QCM4290): EKF < 5 ms, Octree-Suche < 1 ms,
Punktwolke ~28 fps, Akku ~4 h Dauer-Scan.

## 11. Aura — IQ-Datagramm & Paketverlust-Erkennung

Header 12 Byte Big-Endian: Sequenznummer (UInt32) + Zeitstempel (UInt64 µs).
MTU 1420 − Header = **1408 Byte Payload = 704 IQ-Paare** (8-Bit I/Q).
Durchsatz: 2,4 MS/s × 8 Bit × 2 = 38,4 Mbit/s.

Lückenstatistik über UInt32-Differenzen (`(seq − last).toUInt()`, mod 2³² —
wickelt Sequenznummer-Überläufe korrekt ab), Reordering-Erkennung bei
Differenz > 2³¹. Implementierung: `aura/IqDatagram.kt`.

## 12. Aura — WireGuard/X25519 (RFC 7748)

Curve25519-Skalarmultiplikation über die Montgomery-Leiter
(`x₂/z₂`-Projektion, 255 Iterationen, konstante Laufzeit):

```
x1 = u; x2,z2 = 1,0; x3,z3 = u,1; swap = 0
für t = 254 … 0:
    k_t = Bit t des (geclampten) Skalars; swap ^= k_t; cswap(x2,x3); cswap(z2,z3)
    A = x2+z2; AA = A²; B = x2−z2; BB = B²; E = AA−BB
    C = x3+z3; D = x3−z3; DA = D·A; CB = C·B
    x3 = (DA+CB)²; z3 = x1·(DA−CB)²; x2 = AA·BB; z2 = E·(AA+A24·E)
Ergebnis: x2·z2⁻¹ mod (2²⁵⁵−19)
```

Clamping: `k[0] &= 248, k[31] &= 127, k[31] |= 64`. **Verifiziert gegen die
offiziellen RFC-7748-Testvektoren (§5.2, §6.1).**
Implementierung: `aura/WireGuardKeys.kt` (BigInteger, JVM-testbar) +
Konfigurations-Blueprint `aura/WireGuardConfig.kt`.

## 13. Aura — FFT & Cross-Korrelation

Radix-2-FFT (Cooley-Tukey, iterativ, Bit-Reversal) — `aura/Fft.kt`.

Kreuzkorrelation im Frequenzbereich (lineare Korrelation über Zero-Padding
auf N ≥ len(rx)+len(ref)−1):

```
R(τ) = F⁻¹{ F{S_rx} · F{S_ref}* }
```

- Laufzeit des direkten Pfads = Peak-Lage: `τ = (idx − (len(ref)−1)) / fs`
- Multipath: lokale Maxima mit Prominenz ≥ 12 dB, Mindestabstand 8 Samples
- Distanz: `d = τ · c`

Implementierung: `aura/CrossCorrelator.kt`, Referenzsignale (Chirp, 15-Bit-
LFSR-PN) in `aura/ReferenceSignals.kt`.

## 14. Aura — Radio-Tomographie (RTI)

Dämpfung je Link: `y_i = ∫ φ(x,y,z) ds + n`. Diskretisierung über Voxelgitter
+ **normalisiertes Ellipsen-Gewichtungsmodell**:

```
w_i(v) = 1/√(d_tx(v) + d_rx(v))   falls d_tx + d_rx < d_link + λ_w, sonst 0
(zeilennormiert: Σ_v w_i(v) = 1)
```

Lösung des linearen Systems `y = A·φ`:

- **Tikhonov:** `min ‖Aφ − y‖² + λ‖φ‖² + γ·φᵀLφ` über matrixfreies
  Conjugate-Gradient (AᵀA-Anwendung ohne explizite Matrix) —
  `aura/RtiSolver.kt` (Kotlin) und `edge-agent/rti_solver.py` (scipy.sparse).
- **Glättungs-Regularisierung γ:** diskreter Graph-Laplacian L über die
  6-Nachbarschaft des Voxelgitters (Differenzoperator-Ansatz nach
  SPIE 8753) — reduziert Rausch-Artefakte in dünn abgedeckten Voxeln,
  O(6n) matrixfrei.
- **Backprojection** (Echtzeit-Vorschau): `φ_v = Σ_i w_i,v·y_i / Σ_i w_i,v`.
- **Peak-Lokalisierung:** Schwellwert 30 % des Maximums + Chebyshev-
  Mindestabstand (Objekt-/Personenkandidaten).

Verifiziert mit synthetischem Szenario (12 Links, Ellipse 0,5 m): Tikhonov
lokalisiert den Dämpfungs-Blob auf ≤ 1 Voxel genau; γ = 2 senkt die
Feld-Variation, ohne die Lokalisierung zu verschieben.

## 15. CT45P-Triangulation (docs/TRIANGULATION.md)

**Trilateration** (`triangulation/TrilaterationEngine.kt`, `trilateration.py`):
lineare Startlösung (Referenz-Anker-Subtraktion) + **Levenberg-Marquardt**
mit analytischer Jacobi-Matrix und Gewichtung `w_i = 1/σ_i²`; Qualität über
Residuum-RMS und Positions-Sigma `√tr((JᵀWJ)⁻¹)`. **Robustheit:**
Reject-and-Resolve (LTS-1) — Leave-one-out-Lösungen werden über die
Trimmed-Kosten (m−1 kleinste quadratische Residuen) bewertet; Anker, deren
Entfernung die Kosten ≥ 40 % senkt, gelten als Ausreißer (robust gegen
Masking bei kleinen Ankerzahlen).

**Path-Loss:** `d = 10^((RSSI₀ − RSSI)/(10n))`; Kalibrierung per linearer
Regression über `x = 10·log10(d)` (Steigung = −n, Achsenabschnitt = RSSI₀)
+ R²; RSSI-Vorglättung über wählbare Filter je MAC (`RssiFilter`:
EMA `RssiSmoother`, Median `RssiMedianFilter`, 1D-Kalman
`RssiKalmanFilter`; vgl. docs/VERBESSERUNGEN.md).

**Fingerprinting:** gewichtetes k-NN (k = 3) mit Gauß-Kern über die
gemeinsamen BSSIDs (`WifiRssiFingerprinter.kt`).

**Fusion** (`EstimateGate.kt`, `TriangulationService.kt`):
Frische (RTT ≤ 5 s, BLE ≤ 3 s, FP ≤ 10 s) → Mahalanobis-Gate
`‖Δ‖ ≤ k√(σ_A²+σ_B²)` (k = 3) → invers-varianz-gewichteter Mittelwert →
EKF-Messupdate `EkfFusion.updateAbsolutePosition(z, R = σ²)`.
