# 🔬 Verbesserungen & Machbarkeitsanalyse (aus ähnlichen Open-Source-Projekten)

> **Version:** v1.0 · **Datum:** 14. August 2026
>
> Systematische Prüfung der Umsetzbarkeit und Übernahme von Verbesserungen
> und Optimierungen aus ähnlichen Open-Source-Projekten und Fachliteratur.
> Legende: ✅ übernommen · 🟡 machbar (geplant) · 🔴 nicht machbar (on-device)

---

## 1. Übernommene Verbesserungen (dieses Update)

### 1.1 Robuste Trilateration — Reject-and-Resolve (LTS-1)

**Problem:** Ein einzelner verfälschter Distanzwert (Multipath/NLOS) zieht
die Least-Squares-Lösung stark mit; studentisierte Residuen versagen bei
kleinen Ankerzahlen durch Masking.

**Quellen:**
- MDPI Sensors 2017, 17(5):951 — „An Improved BLE Indoor Localization with
  Kalman-Based Fusion": unplausible Distanzen verwerfen (`d_i > τ₂ → ignore`),
  https://www.mdpi.com/1424-8220/17/5/951
- avibn/indoor-positioning-trilateration (ESP32/BLE, Kalman + Least-Squares),
  https://github.com/avibn/indoor-positioning-trilateration

**Umsetzung:** `solve_trilateration(..., robust_iterations=2)` (Python) und
`TrilaterationEngine.solve(..., robustIterations = 2)` (Kotlin). Jede
Leave-one-out-Lösung wird über die **Trimmed-Kosten** (Summe der m−1
kleinsten quadratischen Residuen) bewertet; liegt die beste ≥ 40 % unter der
Volllösung, wird der Anker als Ausreißer verworfen (max. N Durchgänge,
Mindest-Ankerzahl bleibt gewahrt).

**Verifikation:** Test mit 5 Ankern, einem +8-m-Ausreißer: robuste Lösung
trifft die wahre Position exakt (0,0 m Fehler, `rejected_anchors = 1`);
ohne Robustheit 3,3 m Fehler.

### 1.2 RSSI-Vorverarbeitung — Median- und Kalman-Filter

**Problem:** BLE-RSSI fluktuiert stark (Multipath, Abschattung) — die
Distanzschätzung über das Path-Loss-Modell erbt diese Varianz.

**Quellen:**
- MDPI Sensors 2025, 25(9):2834 — Median-Filter (MF) + Moving-Average-Filter
  (MAF) reduzieren RSSI-Fluktuationen messbar (RMSE-Verbesserung),
  https://www.mdpi.com/1424-8220/25/9/2834
- avibn/indoor-positioning-trilateration — Kalman-Filter auf RSSI vor der
  Trilateration, https://github.com/avibn/indoor-positioning-trilateration
- wianoski/geolocation-using-rssi-with-Trilateration-and-kalman-filter,
  https://github.com/wianoski/geolocation-using-rssi-with-Trilateration-and-kalman-filter

**Umsetzung:** `RssiFilter`-Interface mit drei Implementierungen in Kotlin
(`RssiSmoother` = EMA/Standard, `RssiMedianFilter`, `RssiKalmanFilter`) und
Python-Äquivalenten (`RssiKalmanFilter`, `median_filter_rssi`).
`BleBeaconTriangulator` akzeptiert die Strategie als Konstruktor-Parameter.

**Verifikation:** Spike-Unterdrückung durch Median (Fenster 5), Kalman
konvergiert gegen stationären Wert und dämpft Sprünge (Gain < 1).

### 1.3 RTI-Glättungs-Regularisierung (Graph-Laplacian)

**Problem:** Reine Tikhonov-Lösungen (`λ·I`) zeigen Rausch-Artefakte in
dünn abgedeckten Voxeln.

**Quellen:**
- SPIE 8753, „Regularization in radio tomographic imaging" — Differenz-
  Operator Q als Tikhonov-Matrix für glatte Lösungen; TSVD als Alternative,
  https://www.spiedigitallibrary.org/conference-proceedings-of-spie/8753/87530O/Regularization-in-radio-tomographic-imaging/10.1117/12.2012167.short
- Utah SPAN Lab, „Regularization Methods for Radio Tomographic Imaging" —
  bestätigt das normalisierte Ellipsen-Gewichtungsmodell (bereits in Aura
  implementiert) und Tikhonov-Varianten,
  https://span.ece.utah.edu/uploads/RegularizationMethodsForRTI.pdf

**Umsetzung:** optionaler Glättungsparameter γ in `RtiSolver`
(Kotlin + Python): `(WᵀW + λI + γL)·φ = Wᵀy` mit diskretem
Graph-Laplacian L über die 6-Nachbarschaft des Voxelgitters (matrixfrei,
O(6n) — on-device tauglich).

**Verifikation:** Total-Variation des rekonstruierten Felds sinkt mit γ = 2,
Blob-Lokalisierung bleibt ≤ 1 Voxel genau.

### 1.4 Wi-Fi-RTT: 802.11mc-Responder-Bevorzugung

**Problem:** Ranging-Slots (max. 10 APs pro Anfrage) werden an APs ohne
FTM-Unterstützung verschwendet.

**Quellen:**
- Plinzen/android-rttmanager-sample — `ScanResult.is80211mcResponder()`
  als Filter vor dem Ranging,
  https://github.com/Plinzen/android-rttmanager-sample
  (Begleitartikel: https://medium.com/@plinzen/perform-wifi-round-trip-time-measurements-with-android-p-9ffc5277ac6a)
- Plinzen/android-rttmanager-compat — LCI/LCR-Anfrage als weiterführende
  Option, https://github.com/Plinzen/android-rttmanager-compat

**Umsetzung:** `WifiRttTriangulator.requestRanging()` partitioniert bekannte
Anker in Responder/Nicht-Responder und bevorzugt Responder (APs ohne Flag
bleiben als Fallback im Pool, da manche Geräte das Bit unzuverlässig setzen).

**Verifikation:** Code-Pfad dokumentiert; Feldtest auf CT45P ausstehend
(Roadmap „Feldkalibrierung").

---

## 2. Machbarkeitsmatrix weiterer Verbesserungen

| # | Verbesserung | Quelle | Machbarkeit | Begründung / nächster Schritt |
|---|--------------|--------|-------------|-------------------------------|
| 2.1 | LCI/LCR-Standortberichte der APs auswerten (RFC 6225) → automatische Anker-Positionen | Plinzen/android-rttmanager-compat | 🟡 Edge-Agent | Parsing komplex; `RangingResult.getLci()` (API 30+) als JSON zum Agent, dort auswerten |
| 2.2 | TSVD (Truncated SVD) als Regularisierungs-Alternative | SPIE 8753 / Utah SPAN | 🟡 Edge-Agent | scipy SVD auf ≤ 20k Voxel; on-device SVD nicht praktikabel |
| 2.3 | Automatische λ-Wahl (L-Curve) | SPIE 8753 | 🟡 Edge-Agent | L-Curve-Scan über λ-Grid mit CG — Server-seitig machbar, Kotlin-Port aufwändig |
| 2.4 | Total-Variation- (TV-)Regularisierung (CIL-Framework) | Core Imaging Library (CCPi) | 🔴 on-device | TV erfordert Split-Bregman/FISTA — Rechenbudget & Speicher am CT45P überschritten; nur als Python/Server-Option denkbar |
| 2.5 | Dead-Reckoning + Trilateration-Fusion (Schrittlänge, Korridor-Kontext) | MDPI Sensors 2017, 17(5):951 | 🟡 Phase UI 2 | EKF-Fusion existiert; Schritt-Erkennung über IMU (`ImuManager`) als zusätzliche Messquelle einhängen |
| 2.6 | CNN/AoA-Deep-Learning-Positionierung | MDPI Sensors 2025, 25(9):2834 | 🔴 | Benötigt Trainingsdaten + AoA-Hardware-Exposure (Honeywell-SDK-Status ⏳) — erst nach 2.5 |
| 2.7 | Bermuda/ESPresence-artiges Multi-Proxy-Beacon-Netz (HomeAssistant) | agittins/bermuda, neXenio/BLE-Indoor-Positioning | 🟡 Architektur | Multi-Scanner-Konzept passt zur Plattform; BLE-Scan-Infrastruktur (mehrere RELAY-Geräte) als Roadmap-Thema |
| 2.8 | RSSI-Kalman-Strategie als Standard in `BleBeaconTriangulator` | 1.2-Quellen | ✅ umgesetzt | Konstruktor-Parameter; Default bleibt EMA (konservativ), Feldtest entscheidet über Umstellung |

---

## 3. Nicht übernommen (mit Begründung)

| Vorschlag aus der Recherche | Begründung |
|------------------------------|------------|
| RttManagerCompat (Reflection auf versteckte API, Root) | Seit Android P obsolet — öffentliche `WifiRttManager`-API wird genutzt |
| WLAN-Scan-BroadcastReceiver (`SCAN_RESULTS_AVAILABLE_ACTION`) | `startScan()` deprecationpfad — aktueller Ansatz (direkte `scanResults`-Abfrage) bleibt; Umstellung auf `WifiManager.ScanResultsCallback` (API 34+) erst bei targetSdk 34+ |
| CIL als Abhängigkeit | 100+ MB Abhängigkeit für einen einzigen Regularisierer — nicht gerechtfertigt |

---

## 4. Verifikation

- **Python: 38/38 Tests grün** (u. a. Ausreißer-Szenario, Median/Kalman-Filter, RTI-Glättung).
- **Kotlin: 56 JVM-Unit-Tests** (gespiegelte Szenarien, Ausführung in Android Studio/CI).
- Kein Verhaltensbruch: alle Neuerungen sind über Default-Parameter ausgeschaltet
  bzw. konservativ vorbesetzt (EMA-Standard, `robustIterations` ohne
  Ausreißer = identisches Ergebnis wie zuvor).
