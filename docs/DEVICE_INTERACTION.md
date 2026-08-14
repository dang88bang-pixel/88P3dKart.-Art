# 🛰️ Geräteinteraktion & -aktion im 3D-Raum — Machbarkeitsprüfung & Integration

> **Version:** v1.0 · **Datum:** 14. August 2026 ·
> **Eingabe:** „3dxAgent – Geräteinteraktion & -aktion im 3D-Raum
> (Ein-/Ausblendbar)" (v13.0.0-DeviceInteraction)
>
> Einordnung, Fehlerkatalog des Spec-Codes, Übernahme der testbaren Kerne
> (DeviceRegistry, DeviceActionEngine, DeviceSourceMapper) in Kotlin +
> Python sowie Geräte-Layer + Kontextmenü im Web-Visualizer.

---

## 1. Machbarkeitsbefund

| Spec-Komponente | Befund | Umsetzung |
| :--- | :--- | :--- |
| DeviceRegistry (Upsert, Layer, Selektion) | ✅ reiner Kern | **neu:** `device/DeviceRegistry.kt` + `edge-agent/device_registry.py` |
| DeviceActionEngine (Capability-Gating) | ✅ reiner Kern | **neu:** `device/DeviceActionEngine.kt` + Python |
| DeviceSync (BLE/WiFi/mmWave → Geräte) | ✅ Mapper | **neu:** `device/DeviceSourceMapper.kt` |
| Device3DMarker (Three.js) | ✅ machbar | **neu:** Geräte-Layer im Web-Visualizer (Marker, Labels, Raycast-Auswahl, Kontextmenü) |
| DeviceLayerControl / Bottom-Sheet-UI | 🟡 Android-UI | vorhandenes Muster (UI_UX_PLAN); Umsetzung in Roadmap-Phase |
| DeviceInteractionManager | 🟡 UI-Glue | Kernlogik in der Action-Engine konsolidiert (s. Fehlerkatalog) |
| Hardware-Ansteuerung (BLE-Kommando, Firmware-Update) | 🟡 Transport-Adapter | Standard-Aktionen sind deterministische Registry-/Status-Operationen; echte Kommandos über Adapter (Roadmap) |

## 2. Fehlerkatalog (Spec-Code, korrigiert)

| # | Spec-Code | Problem | Korrektur |
| :--- | :--- | :--- | :--- |
| 1 | `item.textContent = capabilitie.description \|\| capabilitie.type` (JS) | **Tippfehler `capabilitie`** — Variable existiert nicht (Parameter heißt `capability`) → ReferenceError beim Öffnen des Kontextmenüs | korrekt über `capability` iteriert |
| 2 | `device.type.name` (JS) | `type` ist im JSON ein **String**, kein Enum → `.name` = `undefined` → alle Icons/‑Farben fallen auf den Default | String-Schlüssel direkt verwendet |
| 3 | `@Serializable` + `metadata: Map<String, Any>` (Kotlin) | kotlinx.serialization kann `Any` nicht serialisieren | `Map<String, String>` (Python: `Dict[str, str]`) |
| 4 | `upsertDevice`: `copy(name, position, status, …)` | überschreibt `capabilities`/`connectionType` nie (partielle Kopie) | Merge-Semantik: leere/fehlende Capabilities behalten, sonst ersetzen; connectionType übernehmen |
| 5 | `LayerConfig`-Farben `0x44FF88` ohne Alpha | `setBackgroundColor` rendert transparent (ARGB nötig) | Farben mit `0xFF…`-Alpha |
| 6 | `DeviceInteractionManager.executeAction` | dupliziert die Engine (hartkodierte `when`-Zweige) | konsolidiert in `DeviceActionEngine` (einzige Quelle für Capability-Prüfung + Ausführung) |
| 7 | `suspend`-Funktionen für In-Memory-StateFlow-Ops | unnötig (kein I/O) | non-suspend |
| 8 | MainActivity-Sketch: `wifiManager.scanResults` als Flow + Positions-Stubs | `scanResults` ist kein Flow; Positionen müssen aus Triangulation/EKF kommen | `DeviceSourceMapper` auf quellenneutralen Datensätzen; Positionsquellen dokumentiert |
| 9 | Status-Lifecycle fehlt | `lastSeen` wird gesetzt, aber nie ausgewertet → Geräte bleiben ewig ONLINE | `markStale` (ONLINE → OFFLINE nach Fenster) |

## 3. Übernommene Module

### 3.1 Kotlin — Paket `com.example.agent.device` (rein, JVM-testbar)

| Modul | Funktion |
| :--- | :--- |
| `DeviceModels` | Device/Typen/Kategorien/Status/Capabilities/Position3D; Typ→Kategorie-Zuordnung |
| `DeviceRegistry` | StateFlow-Registry: Upsert (Merge), Position/Status/Sichtbarkeit, Layer-Propagation, Selektion, Staleness |
| `DeviceActionEngine` | Aktionen-Registry + Capability-Gating; Standard-Aktionen (read_status, locate, set_visibility, toggle_led) |
| `DeviceSourceMapper` | BLE-Token/Netzwerkgerät/mmWave-Target → Device (Typ-Normalisierung, Staleness-Status) |

### 3.2 Python-Port (`edge-agent/device_registry.py`)

Identische Semantik (Registry + Action-Engine + Default-Layer); dient dem
Edge-Agent als zentrale Geräteverwaltung für alle CT45P-Clients.

### 3.3 Edge-Agent-Endpunkte & WebSocket

| Endpunkt | Funktion |
| :--- | :--- |
| `GET /api/v1/devices` | Geräte + Layer-Konfiguration |
| `POST /api/v1/devices/upsert` | Gerät upserten (Merge) + Broadcast `devices_update` |
| `POST /api/v1/devices/action` | Capability-geprüfte Aktion + Broadcast `device_action_result` |
| `GET/POST /api/v1/devices/layers` | Layer lesen / Sichtbarkeit setzen (Kategorie-Propagation) |

WS-Typen: `devices_update` (Ingest/Broadcast, DeviceSync),
`device_action` (Client → Agent), `device_action_result` (Broadcast).

### 3.4 Web-Visualizer — Geräte-Layer

- Marker (Kategorie-Farben: Sensoren grün, Netzwerk blau, Aktoren orange,
  Fahrzeuge violett), Status-Punkt (ONLINE grün / OFFLINE rot / UPDATING gelb),
  CSS2D-Labels,
- **Raycast-Auswahl** (Klick = Selektionsring mit Puls, Rechtsklick =
  Kontextmenü) mit Drag-Unterscheidung (> 5 px),
- **Kontextmenü** mit capability-gefilterten Aktionen (Status, Position,
  LED, Ausblenden) → `device_action` an den Agent,
- Layer-Sichtbarkeit je Kategorie aus dem Agent-Broadcast,
  Toggle `🛰️ Geräte` + Statuszeile, Cap 250 Marker.

## 4. Verifikation

- **Python: 12 neue Tests** (Upsert-Insert/Update, Capability-Erhalt,
  Layer-Propagation, Selektion-Remove, Staleness, Capability-Gating,
  unbekannte Geräte/Aktionen, Standard-Aktionen, Filter, Roundtrip,
  Normalisierung) — Gesamt **111/111 grün**.
- **Kotlin: 13 neue JVM-Tests** (gespiegelt inkl. Mapper) — Gesamt **148**.
- Live-Smoke: App-Import + OpenAPI 3.4.0 (21 Pfade, 15 Schemas).

## 5. Roadmap

| Phase | Inhalt | Status |
| :--- | :--- | :--- |
| **DI 1.0** | Registry, Action-Engine, SourceMapper, REST/WS, Visualizer-Layer | ✅ |
| DI 1.1 | Anbindung in `MainActivity`: `DeviceSourceMapper` ← BleTokenManager/NetworkDeviceTracker/SerialManager; Layer-Bottom-Sheet (UI_UX_PLAN) | ⏳ |
| DI 1.2 | Transport-Adapter: BLE-Kommandos (GATT), Zigbee/LoRa-Gateways, Firmware-OTA | ⏳ |
| DI 1.3 | Aktions-Pipeline (Mehrfachaktionen), Geräte-Gruppen, Automatisierungsregeln (z. B. bei Bewegung) | ⏳ |

## 6. Rechtlicher Hinweis

Die Steuerung fremder Geräte (z. B. Smart Locks, Aktoren) ist nur für
**eigene** oder explizit freigegebene Geräte zulässig; die Plattform führt
Aktionen ausschließlich auf Geräten des eigenen Registries aus (BLE-Pairing,
UWB-Session-Keys — vgl. CLIENT_RULES.md).
