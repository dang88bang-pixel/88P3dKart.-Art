# 🗺️ Taktisches Map-Management, Annotationen & Live-Netzwerk

> **Version:** v1.0 · **Datum:** 14. August 2026 ·
> **Eingabe:** „Taktisches Map-Management & Szenarien-Konfiguration"
> (v9.0.0) und „Interaktive Map-Annotation & Live-Netzwerk-Overlay"
> (v9.1.0–v9.3.0)
>
> Machbarkeitsprüfung und Integration der umsetzbaren Kernlogik
> (Szenario-Komposition, Map-Versionierung, Kompression, Annotation-
> Templates, Geräte-Tracker) sowie Einordnung der nicht/anders
> umgesetzten Teile.

---

## 1. Machbarkeitsbefund

| Spec-Komponente | Befund | Umsetzung |
| :--- | :--- | :--- |
| Modulare Szenario-Komposition (Abhängigkeiten) | ✅ | **neu:** `tactical/ScenarioComposer.kt` + `edge-agent/tactical.py` |
| Map-Versionierung (Delta-Kette) | ✅ | **neu:** `tactical/MapVersioning.kt` + Python-Port |
| Szenario-Kompression | ✅ (zlib statt LZ4 — keine Zusatzabhängigkeit) | **neu:** `tactical/ScenarioCompressor.kt` + `compress_json` (Python, identisches Format) |
| Annotation-Modell + 20+ Templates | ✅ | **neu:** `tactical/AnnotationTemplates.kt` + Python-Templates |
| Geräte-Change-/Anomalie-Erkennung | ✅ | **neu:** `network/NetworkDeviceTracker.kt` + `edge-agent/network_tracker.py` |
| Room-Persistenz (TacticalMapDatabase, AnnotationDatabase) | 🟡 vorhandenes Muster | `AppDatabase`/`SpatialDao` existieren; neue Room-Entities als Roadmap (Schema-Migration nicht ohne Android-SDK verifizierbar) |
| **WASM-Distanzmodul (Rust)** | 🔴 **nicht übernommen** | Begründung: Distanzberechnung ist auf dem CT45P Nanosekunden-Mathematik in Kotlin; WASM+JNI-Bridge wäre **langsamer** (Overhead), brächte eine Rust-Toolchain ins Monorepo und löst kein reales Problem — die Spec-Begründung („exakte Abstandsbestimmung") gilt für die bereits vorhandene `TrilaterationEngine`. |
| KI-Szenariogenerierung (LLM) | 🟡 Interface | LLM-Client + Prompt-Muster dokumentiert; Anbindung über Maps-Grounding-Lite-Roadmap (docs/AURA.md §6) |
| Kollaborative Lagebesprechung | ✅ partiell | WebSocket-Hub existiert; **neu:** WS-Typ `annotation_update` (Broadcast an alle Teilnehmer) |
| ATAK-Export (KML) | ✅ vorhanden | `ExportPipeline.toKml` / `edge-agent/export_formats.py` |
| UI (Bottom Sheet, Listenansicht, 2D-Overlay mit Heatmap/Clustering) | 🟡 geplant | `docs/UI_UX_PLAN.md` (Aktionen A-42…A-46, Roadmap-Phasen) |

## 2. Neue Module

### 2.1 Kotlin

| Paket/Modul | Funktion |
| :--- | :--- |
| `tactical/ScenarioComposer.kt` | Modul-Auswahl → Abhängigkeits-Hülle (DFS, Zyklus-Erkennung, topologische Ordnung) → Konfig-Merge je Typ |
| `tactical/MapVersioning.kt` | Basis-Snapshot + Delta-Kette (Upsert/Remove), Rekonstruktion mit defensiven Kopien |
| `tactical/ScenarioCompressor.kt` | zlib (Deflater/Inflater) — formatgleich mit dem Python-Port |
| `tactical/AnnotationTemplates.kt` | 22 Templates, 7 Layer, 22 Typen; `MapAnnotation`-Modell |
| `network/NetworkDeviceTracker.kt` | added/removed, Signalsprünge (> 10 dBm), Historien-Anomalien (> 20 dBm Abweichung) |

### 2.2 Edge-Agent (Python)

| Modul | Funktion |
| :--- | :--- |
| `tactical.py` | `ScenarioComposer`, `MapVersioning`, `compress_json/decompress_json`, 22 Annotation-Templates |
| `network_tracker.py` | `DeviceTracker` (Change-/Anomalie-Erkennung) |
| WS-Typ `network_devices_update` | Scan-Zyklus → Tracker → Broadcast `network_devices` |
| WS-Typ `annotation_update` | kollaborativer Annotation-Broadcast |

### 2.3 Verifikation

- **Python: 21 neue Tests** (Composer-Abhängigkeiten/Zyklen/Duplikate, Delta-Rekonstruktion,
  Kompression-Roundtrip, Templates, Tracker-Change/Anomalie) — Gesamt **65/65 grün**.
- **Kotlin: 22 neue JVM-Tests** — Gesamt 99 (Ausführung in Android Studio/CI).

## 3. Nicht übernommen (mit Begründung)

| Spec-Teil | Begründung |
| :--- | :--- |
| WASM-Distanzmodul (Rust, `wasm_bindgen`) | Bridge-Overhead > Berechnungszeit; Toolchain-Aufwand ohne Mehrwert (s. o.) |
| `LiveSyncManager` als zweiter WS-Client | existiert als `AgentWebSocketClient` (+ Backoff-Reconnect); Annotation-Sync über den neuen Broadcast-Typ |
| `ProtocolAdapterWorker` (CoAP) | kein CoAP-Einsatzszenario (wie docs/SERVICE_WORKER.md) |
| Simulierte Scan-Daten (`scanWiFi()`-Dummies) | echte Scanner existieren (`WifiRttTriangulator` etc.) |
| Google-Maps-2D-Abhängigkeiten (`SupportMapFragment`, `HeatmapTileProvider`) | App baut auf eigenem Renderer (docs/AURA.md §1-Hinweis); Heatmap/Clustering im UI-Plan (Roadmap) |

## 4. Roadmap

| Phase | Inhalt |
| :--- | :--- |
| TAC 1.0 | Composer, Versionierung, Kompression, Templates, DeviceTracker, WS-Typen | ✅ |
| TAC 1.1 | Room-Entities (TacticalMap, MapAnnotation) + Manager-Anbindung in `MainActivity` | ⏳ |
| TAC 1.2 | Annotation-UI (Bottom Sheet, Liste) + 2D-Overlay (Heatmap/Clustering) — UI_UX_PLAN Phasen 1–3 | ⏳ |
| TAC 1.3 | LLM-Szenariogenerator (Interface + Prompt-Muster, Maps Grounding Lite) | ⏳ |
| TAC 1.4 | ATAK-Import/Export-Roundtrip (KML vorhanden, CoT-Events prüfen) | ⏳ |
