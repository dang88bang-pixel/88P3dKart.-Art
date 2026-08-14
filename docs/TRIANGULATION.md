# 📶 WiFi-/BLE-Triangulation auf dem Honeywell CT45P (XON)

> **Version:** v1.0 · **Status:** Kernmodule implementiert (siehe
> [Umsetzungsstatus](#8-umsetzungsstatus)) · **Gerät:** Honeywell CT45P,
> Modellreihe `CT45P-X0N` (CT45 XP) auf der **Mobility Edge™**-Plattform
>
> Dieses Dokument beschreibt die optimale Umsetzung der WiFi- und
> BLE-basierten Triangulation auf dem CT45P: Hardware-Fähigkeiten,
> Entwicklertools, Industriestandards, die Mathematik der Positionsbestimmung
> und die Integration in die 3dxAgent-Plattform dieses Repositories.

---

## 1. Hardware-Voraussetzungen (verifiziert)

| Merkmal | CT45P-X0N | Bedeutung für die Triangulation |
| :--- | :--- | :--- |
| SoC | **Qualcomm QCM4290**, Octa-Core, 2,0 GHz | Echtzeit-Trilateration + EKF ohne Cloud |
| WLAN | **Wi-Fi 6 (802.11ax)**, 2×2 MU-MIMO | Hohe Abtastrate, stabile Messung in dichten Netzen |
| Bluetooth | **Bluetooth 5.1** + optionale **zweite BLE-Schnittstelle** | Paralleles Scanning ohne Unterbrechung der aktiven Verbindung |
| NFC | integriert | Einfache Paarung/Provisionierung von BLE-Token und Sensoren |
| Betriebssystem | Android 11, Updates bis Android 15 (Mobility Edge) | Langfristige API-Stabilität (`WifiRttManager` etc.) |
| Sicherheit | WPA3-Enterprise | Sichere Anbindung der Anker-Infrastruktur in Unternehmensnetzen |

> **Hinweis zur Modellbezeichnung:** „XON" entspricht der
> Produktnummer-Struktur `CT45P-X0N…` (CT45 XP). Der SoC ist laut
> Datenblatt der **Qualcomm QCM4290 (2,0 GHz)** — die älteren
> Docs-Angaben („Snapdragon 662") wurden im Zuge des Dokumenten-Audits
> korrigiert (siehe `docs/ARCHITECTURE.md`, `docs/ALGORITHMS.md`).

## 2. Technische Präzisierungen (wichtig für die Umsetzung)

### 2.1 Wi-Fi 6 ≠ 802.11mc RTT

Wi-Fi 6 (802.11ax) garantiert **nicht** automatisch Round-Trip-Time-Messung.
RTT/FTM ist **IEEE 802.11mc** (2016) — eine separate Fähigkeit, die Gerät
**und** Access Point unterstützen müssen (Nachfolger: 802.11az/NG FTM in
Wi-Fi-6E/7-Umgebungen). Die App prüft die Unterstützung zur Laufzeit:

- `PackageManager.hasSystemFeature(FEATURE_WIFI_RTT)`,
- `WifiRttManager.isAvailable()` und `is80211mcSupported()`
  (→ `WifiRttTriangulator.supported` / `ieee80211mcSupported`).

### 2.2 Genauigkeitsangaben sind Zielwerte

| Methode | Zielgenauigkeit | Bedingungen |
| :--- | :--- | :--- |
| Wi-Fi RTT/FTM (802.11mc) | **1–2 m** | Sichtlinie; Multipath degradiert die Messung |
| Wi-Fi RSSI-Fingerprinting | 1–3 m | Gute AP-Dichte, eingemessene Datenbank, ruhige Umgebung |
| BLE RSSI-Triangulation | 3–8 m | Stark multipath-abhängig; kalibrierte Anker erforderlich |
| BLE AoA/AoD (BLE 5.1 Direction Finding) | Submeter | Benötigt Antennen-Arrays + **Vendor-Exposure** — Standard-Android exponiert AoA nicht; über Honeywell SDK zu prüfen (Status ⏳) |

### 2.3 Berechtigungen & Drosselung

- `ACCESS_FINE_LOCATION` — zwingend für RTT und für RSSI-Scans mit
  Standortbezug.
- Android 13+: `NEARBY_WIFI_DEVICES` mit `usesPermissionFlags="neverForLocation"`
  (im Manifest deklariert) für WLAN-APIs ohne Standortfreigabe.
- Android drosselt Hintergrund-Scans (~4 Scans/2 min) und Ranging-Anfragen;
  Vordergrundbetrieb mit Standortfreigabe ist entdrosselt. In verwalteten
  Flotten kann die Honeywell-OEMConfig/MDM-Policy Limits anpassen.
- Ranging-Intervall nicht unter ~1 s wählen (Standard: 2 s).

### 2.4 Zweite BLE-Schnittstelle

Die Standard-Android-API erlaubt **mehrere parallele Scanner**
(mehrere `ScanCallback`-Instanzen) auf einer Funkhardware — dafür steht die
Abstraktion `BleRadioBackend` (Primärkanal = bestehender `BleTokenManager`,
Sekundärkanal = `BleBeaconTriangulator`). Ob die zweite **Hardware**-Schnittstelle
des CT45P über das Honeywell Mobility SDK getrennt ansprechbar ist, ist
geräteabhängig und als SDK-Backend-Erweiterung vorgesehen (Status ⏳).

### 2.5 AoA/AoD

BLE-5.1-Direction-Finding (AoA/AoD) erfordert Antennen-Arrays und eine
Hersteller-API — die Standard-Android-APIs liefern nur RSSI. Nutzung erst
nach Verifikation der Honeywell-SDK-Exposure (Roadmap-Phase „AoA/AoD").

---

## 3. Entwicklertools & Bereitstellung

| Werkzeug | Zweck | Nutzung im Projekt |
| :--- | :--- | :--- |
| **Honeywell Mobility SDK for Android** | Herstellerspezifische Hardware-Features (DataCollection, Gerätekonfiguration); Bezug über das *Technical Support Downloads Portal* | Anker-Konfiguration, 2.-BLE-Backend (⏳), AoA/AoD (⏳) |
| **EZConfig for Mobility** | Zentrale Konfiguration von Wi-Fi/BT über Barcodes oder XML | Bereitstellung der Triangulations-Einstellungen auf mehreren Geräten |
| **OEMConfig-App** | Fernsteuerbare Gerätekonfiguration über MDM (z. B. SOTI, AirWatch) | Flottenverwaltung, Scan-Policies, WLAN-Profile |
| **Entwickleroptionen** | 7× Tippen auf Build-Nummer | Bluetooth-HCI-Logging, Wi-Fi/BT-Debug-Erweiterungen |

---

## 4. Architektur & Datenfluss

```text
                ┌────────────────────────────────────────────┐
                │            CT45P (Android-App)             │
                │                                            │
 Wi-Fi-6-APs ───┤► WifiRttTriangulator   (802.11mc, 1–2 m) ──┤
 (802.11mc)     │► WifiRssiFingerprinter (k-NN, 1–3 m)    ──┤
                │                                            │
 BLE-Beacons ───┤► BleBeaconTriangulator  (RSSI→PathLoss→   ─┤
 (Anker)        │                          Trilateration)    │
                │                                            │
                │   ┌─ Frische-Prüfung (EstimateGate) ──────┐ │
                │   ├─ Mahalanobis-Konsistenztest ──────────┤ │
                │   ├─ Invers-Varianz-Fusion ───────────────┤ │
                │   └─ EKF-Messupdate (EkfFusion 6-DOF) ────┘ │
                └───────────────────┬────────────────────────┘
                                    │ WebSocket `position_update` /
                                    │ `triangulation_anchors`
                                    ▼
                          Edge-Agent (FastAPI)
                REST /api/v1/triangulation/solve (Fallback)
                                    │ Broadcast
                                    ▼
                      Web-Visualizer (Three.js)
              Anker-Ringe + Geräte-Marker + Statuszeile
```

---

## 5. Mathematik

### 5.1 Trilateration (`TrilaterationEngine` / `trilateration.py`)

Distanzen $d_i$ zu Ankern $\mathbf{a}_i$ mit bekannter Position:

$$d_i = \|\mathbf{p} - \mathbf{a}_i\| + \varepsilon_i$$

1. **Lineare Startlösung** (Subtraktion des Referenz-Ankers):
   $2(\mathbf{a}_i - \mathbf{a}_0)\cdot\mathbf{p} = d_0^2 - d_i^2 + \|\mathbf{a}_i\|^2 - \|\mathbf{a}_0\|^2$
   → Kleinste-Quadrate (≥ 3 Anker in 2D, ≥ 4 in 3D).
2. **Levenberg-Marquardt-Verfeinerung**: $\min_\mathbf{p}\sum_i w_i(d_i-\|\mathbf{p}-\mathbf{a}_i\|)^2$
   mit $w_i = 1/\sigma_i^2$, analytischer Jacobi-Matrix, adaptiver Dämpfung λ.
3. **Qualität:** Residuum-RMS, Positions-Sigma $\sqrt{\mathrm{tr}((J^T W J)^{-1})}$,
   Konfidenz 0–1.

### 5.2 Path-Loss-Modell (`PathLossModel`)

$$d = 10^{\,(RSSI_0 - RSSI)\,/\,(10\,n)}$$

- $RSSI_0$: Referenzpegel bei 1 m, $n$: Pfadverlustexponent (2 Freiraum, 2,7–4 Indoor).
- Kalibrierung per linearer Regression über $10\log_{10}(d)$ → beide Parameter
  + Bestimmtheitsmaß R². RSSI-Vorverarbeitung über EMA-Glättung je MAC
  (`RssiSmoother`).

### 5.3 Fingerprinting (`WifiRssiFingerprinter`)

Gewichtetes k-NN: normalisierter euklidischer Abstand der RSSI-Vektoren über
die gemeinsamen BSSIDs, Gauß-Kern-Gewichtung, k = 3.

### 5.4 Sensorfusion (`TriangulationService` + `EstimateGate`)

1. **Frische:** RTT ≤ 5 s, BLE ≤ 3 s, Fingerprint ≤ 10 s.
2. **Konsistenz:** Mahalanobis-Gate $\|\Delta\| \le k\sqrt{\sigma_A^2+\sigma_B^2}$
   (k = 3).
3. **Fusion:** invers-varianz-gewichteter Mittelwert (die genauere Quelle
   dominiert automatisch).
4. **EKF:** fusionierte Schätzung als absoluter Messwert in den bestehenden
   6-DOF-EKF (`EkfFusion.updateAbsolutePosition`, R = σ²) — zusammen mit den
   IMU-Daten des CT45P ergibt das eine robuste Positionsschätzung, auch bei
   zeitweisem Ausfall einzelner Quellen (Modi FULL/DEGRADED/MINIMAL).

---

## 6. Kotlin-Module (`com.example.agent.triangulation`)

| Modul | Funktion |
| :--- | :--- |
| `TrilaterationEngine` | 2D/3D-Trilateration (LSQ + Levenberg-Marquardt, gewichtet) — reine Mathematik |
| `PathLossModel` + `RssiSmoother` | Log-Distance-Modell, Kalibrierung (Regression), EMA-Filter |
| `PositionEstimate` + `EstimateGate` | Einheitliche Schätzung, Frische/Konsistenz/gewichteter Mittelwert |
| `BleRadioBackend` (+ `StandardAndroidBleBackend`) | Abstraktion der BLE-Funkkanäle (parallele Scanner; SDK-Backend ⏳) |
| `BleBeaconTriangulator` | Dediziertes BLE-Scanning → RSSI → PathLoss → Trilateration |
| `WifiRttTriangulator` | `WifiRttManager` (802.11mc), Feature-Checks, mm→m, σ je Messung |
| `WifiRssiFingerprinter` | Fingerprint-DB, gewichtetes k-NN, Scan-Loop mit Drosselungs-Hinweis |
| `TriangulationService` | Orchestrierung + Fusion + EKF-Anbindung, Modus-State |

Integration: `EkfFusion.updateAbsolutePosition`, Manifest-Berechtigungen,
`MainActivity`-Verdrahtung, WebSocket-Typen `position_update`/
`triangulation_anchors` (`AgentWebSocketClient`).

## 7. Edge-Agent & Web-Visualizer

- **REST:** `POST /api/v1/triangulation/solve` — Anker + Distanzen → Position
  (identische Numerik via `edge-agent/trilateration.py`).
- **WebSocket:** `position_update` → Broadcast an Visualizer + Persistenz
  (`db.save_transform`, Kind `triangulation`); `triangulation_anchors` →
  Broadcast.
- **Web-Visualizer:** Anker-Ringe (Wi-Fi grün / BLE blau) mit Labels,
  Geräte-Marker (gelb) + Statuszeile (±Genauigkeit), Toggle `📶 Triang.`.

---

## 8. Umsetzungsstatus

| Komponente | Implementierung | Status |
| :--- | :--- | :--- |
| Trilateration (2D/3D, LM, gewichtet) | `TrilaterationEngine.kt` + `trilateration.py` | ✅ |
| Path-Loss + Kalibrierung + EMA | `PathLossModel.kt` + Python | ✅ |
| Fusion (Frische/Mahalanobis/inverse Varianz) | `EstimateGate.kt`, `TriangulationService.kt` | ✅ |
| Wi-Fi RTT (802.11mc, Feature-Check) | `WifiRttTriangulator.kt` | ✅ |
| BLE-RSSI-Triangulation (2. Scan-Kanal) | `BleBeaconTriangulator.kt`, `BleRadioBackend.kt` | ✅ |
| Wi-Fi-Fingerprinting (k-NN) | `WifiRssiFingerprinter.kt` | ✅ |
| EKF-Anbindung | `EkfFusion.updateAbsolutePosition` | ✅ |
| App-Integration + Berechtigungen | `MainActivity.kt`, `AndroidManifest.xml` | ✅ |
| Edge-Agent-Endpoint + Broadcast | `agent.py`, `models.py` | ✅ |
| Web-Visualizer-Layer | `main.js`, `index.html` | ✅ |
| Tests | 8 Python- + 11 JVM-Unit-Tests (Math, Kalibrierung, Fusion) | ✅ |
| Honeywell-SDK-Backend (2. Hardware-Radio) | `BleRadioBackend`-Extension-Point | ⏳ |
| AoA/AoD (BLE 5.1 Direction Finding) | nach SDK-Verifikation | ⏳ |
| EZConfig/OEMConfig-Bereitstellungsrezepte | geplant (Flottenrollout) | ⏳ |
| Feldkalibrierung (Path-Loss, Fingerprints) | Workflow §9 | ⏳ |

## 9. Kalibrier-Workflow

1. **Anker erfassen:** Positionen der Wi-Fi-APs/Beacons im lokalen
   Koordinatensystem einmessen (Ziel: ≥ 3 RTT-fähige APs, ≥ 3 Beacons).
2. **Path-Loss kalibrieren:** je Anker ≥ 3 Messpunkte in bekannter Distanz →
   `PathLossModel.calibrate` (App) bzw. `calibrate_path_loss` (Python) →
   R² > 0,9 anstreben.
3. **Fingerprints einmessen (optional):** Rasterbegehung, je Zelle
   RSSI-Vektor → `WifiRssiFingerprinter.addFingerprint`.
4. **Validierung:** Referenzpunkte gegen bekannte Positionen — RTT ≤ 2 m,
   BLE ≤ 8 m, Fusion ≤ RTT-Genauigkeit; `TriangulationService.mode` = FULL.
5. **Rollout:** Anker-Konfiguration als Asset/JSON; Geräte-Konfiguration via
   EZConfig-Barcodes bzw. OEMConfig-MDM in der Flotte.

## 10. Rechtlicher Hinweis

Wi-Fi-Scans und Standortbestimmung verarbeiten Umgebungsdaten; bei Betrieb in
fremden Infrastrukturen sind Nutzungsbedingungen zu beachten. Die
Triangulation ist für **eigene Anker-Infrastruktur** ausgelegt (passive
Messung, keine Dekodierung fremder Nutzdaten). Personenbezogene Standortdaten
unterliegen der DSGVO — die Plattform speichert ausschließlich Geräte- und
Ankerdaten.
