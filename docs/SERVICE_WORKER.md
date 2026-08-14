# ⚙️ Service Worker Bedarf — Machbarkeitsanalyse & Integration

> **Abdeckung der Eingabe-Versionen:** Dieses Dokument deckt die
> Spezifikations-Versionen **10.0.0, 10.1.0 und 10.2.0** ab — die
> Worker-Listen aus 10.1.0 (Sensor Preprocessor, Network Monitor, Offline
> Sync, Anomaly Detector, Render Optimizer, Tactical AI, Battery Health,
> Data Export, OTA, Orchestrator) sind vollständig in der Mapping-Tabelle
> (§2) und der Machbarkeitsmatrix abgebildet.

> **Version:** v1.0 · **Datum:** 14. August 2026 ·
> **Eingabe:** „3dxAgent – Gesamter Service Worker Bedarf" (v10.2.0-ServiceWorkerComplete)
>
> Prüfung der Umsetzbarkeit der v10.2.0-Spezifikation (13 Service Worker),
> Korrektur der Architekturannahmen, Übernahme der sinnvollen Logik
> (referenziert an WorkManager/Workbox-Standards) und Status der Integration
> in die 3dxAgent-Plattform.

---

## 1. Befund der Machbarkeitsprüfung

### 1.1 Architektonisches Kernproblem

Die Spezifikation beschreibt die „Service Worker" als JavaScript-Klassen
(`class SensorPreprocessorWorker { … }`) mit `setInterval`, `window.addEventListener`,
`navigator.serviceWorker.controller.postMessage`, `process.memoryUsage()` und
`indexedDB`. Dieser Code läuft so in **keiner Laufzeitumgebung**:

| Verwendete API im Spec-Code | Wo sie existiert | Konflikt |
| :--- | :--- | :--- |
| `process.memoryUsage()`, `process.uptime()` | Node.js | In Browser-Service-Workern **nicht verfügbar** |
| `window.addEventListener('online')` | Browser-Fensterseite | In Workern **kein `window`** |
| `navigator.serviceWorker.controller.postMessage` | **Seite** → Worker | Im Worker existiert `navigator.serviceWorker` nicht (Worker sendet über `clients`) |
| `setInterval` für Dauerbetrieb | Seite/Node | Service Worker werden **suspendiert/terminiert** — Timer sind nicht garantiert |
| Web-Bluetooth/WLAN/UWB-Scans im Worker | Browser-Seite, nutzergesteuert | In Service Workern **nicht möglich**; auf Android ohnehin native APIs |
| Ladekontrolle (`targetVoltage`, `targetRate`) | Lade-Hardware/Firmware | **Keine** App/Worker kann Ladestrom setzen |
| OTA-Installation via `fetch` + Cache | Honeywell Mobility Edge/MDM | Ein Service Worker kann **keine APK installieren** |

### 1.2 Korrekte Zielarchitekturen (für dieses Repository)

Die 3dxAgent-Plattform hat **zwei Laufzeitumgebungen**, für die jeweils das
kanonische Werkzeug gilt:

```text
┌─ NATIVE APP (CT45P, Kotlin) ──────────────────────────────────────┐
│ Sofortige In-Process-Arbeit  → Kotlin Coroutines (Dispatchers.IO/ │
│                                Default) — bereits im Einsatz      │
│ Deferrable/garantierte Jobs  → WorkManager (Constraints: Akku,    │
│                                Netz, Laden; Doze-konform)         │
│ Live-Scans (SDR/BLE/RTT)     → Foreground Service                 │
└───────────────────────────────────────────────────────────────────┘
┌─ WEB-VISUALIZER (Node.js + Three.js) ─────────────────────────────┐
│ Offline-App-Shell/Caching    → Service Worker (Workbox-Strategien)│
│ CPU-intensive Vorverarbeitung→ Web Worker (dediziert)             │
│ Persistente Queues           → IndexedDB                          │
└───────────────────────────────────────────────────────────────────┘
```

Referenzen: WorkManager ist die Android-Empfehlung für garantierte
Hintergrundarbeit mit System-Constraints (Batterie/Netz/Laden, Doze-Konform);
Workbox ist das Google-Standard-Werkzeug für PWA-Service-Worker mit
Caching-Strategien (cache-first, network-first, stale-while-revalidate) und
Background Sync.

### 1.3 Weitere Befunde

- **Zählfehler:** Die Spec nennt „12 Service Worker", beschreibt aber 13
  (inkl. Workflow Orchestrator).
- **Logikfehler Schwellwerte:** `if (value > threshold.critical)` ist für
  Metriken mit „niedriger = schlechter" falsch — Batterie 10 % löst weder
  Warning (30) noch Critical (15) aus, RSSI −85 wird nie kritisch.
  **Korrigiert** in `maintenance/AdaptiveThresholdMonitor.kt`.
- **Trendrichtung fehlt:** aufwärts = Alarm gilt nur für CPU/Temperatur;
  bei RSSI ist ein **fallender** Trend der Alarmfall. Korrigiert.
- **Ladezyklen naiv:** `level === 100 && charging` zählt nur Vollladungen.
  **Korrigiert:** kumulierte Entladung (100 %-Punkte = 1 Zyklusäquivalent)
  in `maintenance/BatteryHealthTracker.kt`.
- **Simulierte Batteriedaten** (0,1 %/s-Entladung) statt echter Messwerte —
  im Repo existiert die Telemetrie bereits (MainActivity, `telemetry`-WS).

---

## 2. Mapping: Spec-Worker → bestehende Implementierung

| # | Spec-Worker | Existiert im Repo | Lücke | Empfehlung |
| :--- | :--- | :--- | :--- | :--- |
| 1 | Sensor Preprocessor | `SerialManager`, `BleTokenManager`, `triangulation/*` (Filter, EMA/Median/Kalman), `aura/*` | — | Coroutines (bereits umgesetzt) |
| 2 | Data Aggregator | `LiveSensorPipeline` (Frame-Sync 50 ms) + `AuraIntegrator` | Batch/EKF-Fusion | vorhanden (EKF separat) |
| 3 | Mesh Generator | `MeshGenerator`, `PoissonReconstruction`, `SmartMeshIntegrator` | Draco | Edge-Agent (Python), Ziel < 5 MB |
| 4 | Render Optimizer | Web: LOD + InstancedMesh; LOD-Stufen im UI-Plan | — | vorhanden |
| 5 | Network Monitor | `NetworkDataCollector`, `Gatekeeper`, `WifiRttTriangulator`, `BleBeaconTriangulator` | UWB-Scan-Loop | vorhanden (native APIs) |
| 6 | Offline Sync | `offline/` (LocalApiServer/WS), SQLite WAL, Edge-Agent | Web-Offline-Queue | **neu: `sw.js`** ✅ |
| 7 | Live Sync | `AgentWebSocketClient` + Edge-Agent-WS-Hub + MQTT-Bridge | WebRTC/P2P | vorhanden (Client/Server-Modell reicht) |
| 8 | Protocol Adapter | `mqtt_bridge.py` (MQTT↔WS), REST, BLE direkt | CoAP | nicht benötigt (kein CoAP-Einsatz) |
| 9 | Anomaly Detector | `Gatekeeper` (RF/Netz), `EvaluationAgent` | adaptive Schwellwerte, Trends, Spikes | **neu: `maintenance/AdaptiveThresholdMonitor.kt`** ✅ |
| 10 | Battery Health | Telemetrie (Batterie %, Temperatur) | Zyklen, Alterung, Restlaufzeit | **neu: `maintenance/BatteryHealthTracker.kt`** ✅ |
| 11 | OTA Manager | — | **nicht als SW umsetzbar** | Honeywell Mobility Edge OTA via MDM (OEMConfig/SOTI) — dokumentiert |
| 12 | Data Export | UI-Plan (Export-Dialog), Edge-Agent-DB | Format-Konvertierung | **neu: `maintenance/ExportPipeline.kt` + `/api/v1/export`** ✅ |
| 13 | Workflow Orchestrator | `PipelineOrchestrator` (Kotlin), `pipeline.py` | Prioritäts-Queue | WorkManager-Verketten (Roadmap) |

---

## 3. Umgesetzte Integration (dieses Update)

### 3.1 Kotlin — Paket `com.example.agent.maintenance` (rein, JVM-testbar)

| Modul | Funktion |
| :--- | :--- |
| `AdaptiveThresholdMonitor` | Schwellwerte (richtungskorrekt), 3σ-Spikes, lineare Trends, Kontextregeln (CPU+Temp, Latenz+Paketverlust), selbstlernende Schwellwerte (mean ± 1,5σ/3σ, begrenzt adaptierend) |
| `BatteryHealthTracker` | Kumulierte Entladung → Zyklusäquivalente, Health = 100 − Zyklen·0,01 − Jahre·0,5, Restlaufzeit aus Entladerate, Empfehlungen (nur Monitoring — keine Ladekontrolle) |
| `ExportPipeline` | JSON/GeoJSON/KML-Erzeugung (mit XML-/JSON-Escaping), Retention-Funktion |

### 3.2 Edge-Agent (Python)

- `export_formats.py` — GeoJSON (RFC 7946), KML (OGC 2.2), JSON, MultiPoint, Retention
- `POST /api/v1/export` — Format-Konvertierung (OpenAPI 3.1.0, 10 Pfade)
- 6 neue Tests (insgesamt **44/44 grün**)

### 3.3 Web-Visualizer — echter Service Worker

- `public/sw.js` — dependency-frei nach Workbox-Muster:
  **Cache-First** (App-Shell) · **Network-First** (API/Health mit Offline-Fallback) ·
  **Stale-While-Revalidate** (CDN-Assets) · Versions-Caches + Cleanup ·
  WebSocket bleibt unberührt (SW fangen kein WS ab)
- Registrierung in `index.html` (geprüft: nur bei `'serviceWorker' in navigator`)

### 3.4 Tests

- 20 neue JVM-Unit-Tests (Schwellwerte inkl. Richtungsfälle, Spike/Trend,
  Lernmodus, Batteriezyklen/Restlaufzeit/Empfehlungen, Export-Formate/Escaping/Retention)

---

## 4. Nicht umsetzbar / bewusst nicht übernommen

| Spec-Teil | Grund |
| :--- | :--- |
| OTA-Installation im Service Worker | Android-APK-Updates laufen über Honeywell Mobility Edge OTA/MDM — ein Web-SW kann keine System-Updates installieren |
| Lade-Steuerung (Spannung/Rate) | Hardware-/OS-Domäne; Apps erhalten nur Monitoring-Daten |
| WLAN/BLE/UWB-Scans im Service Worker | Browser-SWs haben keine Radio-APIs; auf Android sind die nativen Implementierungen bereits vorhanden (`triangulation/*`) |
| `setInterval`-Dauerbetrieb im SW | SW werden suspendiert; periodische Arbeit gehört in WorkManager (Android) bzw. den Edge-Agent |
| WebRTC-Mesh (P2P) für Live Sync | Client/Server-Modell (Agent-WebSocket-Hub) deckt den Bedarf; P2P nur bei vollständig dezentralen Szenarien (Roadmap) |
| Node.js-APIs (`process.*`) | gehören nicht in Browser-Kontext |

---

## 5. Roadmap (realistische Phasen)

| Phase | Inhalt | Basis |
| :--- | :--- | :--- |
| **SW 1.0** | Analyse + Kernlogik (Anomalie/Batterie/Export) + sw.js | ✅ abgeschlossen |
| SW 1.1 | WorkManager-Anbindung: `CoroutineWorker` für Anomalie-/Batterie-/Export-Jobs mit Constraints (`BATTERY_NOT_LOW`, `NETWORK`) | AndroidX Work |
| SW 1.2 | Export-UI anbinden (Analyse-Tab, UI_UX_PLAN A-35) + Cloud-Upload (S3/R2) | Edge-Agent |
| SW 1.3 | Vollständige PWA-Offline-Fähigkeit: three.js lokal vendoren (CDN-frei), IndexedDB-Annotations-Queue + Background Sync | Workbox-Muster |
| SW 1.4 | OTA via Honeywell Mobility Edge (MDM-Profil OEMConfig/SOTI) — Dokumentation/Bereitstellungsrezepte | Hersteller-OTA |
