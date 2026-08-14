# 🎛️ UI/UX-Detailplan — 3D-Grafikoberfläche: Aktionen & Interaktionen

> **Version:** v1.0 · **Geltungsbereich:** Android-App (CT45P) + Web-Visualizer
> · **Referenzen:** [AURA.md](AURA.md) (SDR/RTI-Funktionen),
> [UX.md](UX.md) (bestehende v3.2.0-UX), [ARCHITECTURE.md](ARCHITECTURE.md)
>
> Dieses Dokument arbeitet **jedes Detail** der grafischen Oberfläche aus:
> Bildschirmaufbau, HUD, Panels, Dialoge, alle Aktionen und
> Interaktionsmöglichkeiten, Kamera-Modi, Farb-/Legendensystem, Datenbindung,
> Zustandsmaschine und ein phasenweiser Umsetzungsplan mit Datei-Mapping auf den
> realen Code.

---

## 1. Designprinzipien

| # | Prinzip | Konsequenz für das UI |
|---|---------|----------------------|
| P1 | **Safety-First** | Kritische Info (Gatekeeper-Alerts, EKF-Modus, Tunnel-Status) ist **immer sichtbar**, nie hinter Interaktionen versteckt. Alerts erscheinen als nicht-blockierende Banner + Badge. |
| P2 | **Gloves-on** | Alle Touch-Targets ≥ 48 dp (Standard), primäre Aktionen ≥ 56 dp. Keine Geste als einziger Zugang — jede Aktion hat auch einen Button. |
| P3 | **Einhand-Bedienung** | Primäre Aktionen rechts unten (Daumen-Zone), Navigation unten. Sekundäre Aktionen links oben. |
| P4 | **Dark-Theme** | `background #0D0D1A`, `surface #1A1A2E` (bestehende `colors.xml`) — blendfrei im Außen- und Nachteinsatz. |
| P5 | **3-Ebenen-Information** | **HUD** (immer, passiv) → **Panel** (ein-/ausklappbar, kontextuell) → **Modal** (explizit bestätigen). Keine Ebene verdeckt dauerhaft den 3D-Canvas. |
| P6 | **60 FPS** | UI-Updates nie im Render-Thread; Debouncing (≥ 100 ms) für alle Text-HUDs; Instanced-Rendering für tausende Voxel/Zellen. |
| P7 | **Farbkonsistenz** | Gleiche Farbe = gleiche Bedeutung überall (semantische Farben aus `colors.xml`, RF-Skalen aus §8). |
| P8 | **Offline-Fähigkeit** | Alle Aura-/Szenario-Funktionen funktionieren ohne Cloud; Web-UI degradiert nur die Live-Quelle, nie die Interaktion. |

---

## 2. Informationsarchitektur

### 2.1 Android-App (CT45P) — Bottom-Navigation mit 5 Tabs

```text
┌─────────────────────────────────────────────┐
│  [Toolbar: Titel · Gerätestatus · ⚙️]        │
│                                             │
│             aktives Fragment                │
│   (Live 3D | Karte | Aura | Szenario |      │
│    Analyse)                                 │
│                                             │
│  [3D]  [Karte]  [📡Aura]  [Szen.]  [📊]     │   ← BottomNavigationView
└─────────────────────────────────────────────┘
```

| Tab | Fragment | Funktion | Status |
|-----|----------|----------|--------|
| **3D** | `LiveViewFragment` | Echtzeit-Punktwolke + **alle 3D-Layer** (RF, RTI, Tags) | Bestand (wird erweitert) |
| **Karte** | `MapFragment` | Top-Down-Karte: Token, Personen, Hindernisse, RF-Heatmap-Fußabdruck | Bestand (wird erweitert) |
| **📡 Aura** | `AuraFragment` (neu) | Spektrum-Wasserfall, Gatekeeper-Alerts, Tunnel-Status, RF-Bandliste | **Neu** |
| **Szenario** | `ScenarioFragment` | Evakuierung/Taktik/Architektur/Temporär/Forschung | Bestand (Panel-Ausbau) |
| **📊 Analyse** | `AnalysisFragment` (neu) | Historie, Export (glTF/OBJ/PLY/IFC/JSON), Evaluierungsbericht | **Neu** (aus UX.md „Analyse & Export") |

Einstellungen: Toolbar-`⚙️` → `SettingsFragment` (neu, ersetzt kein Tab).

### 2.2 Web-Visualizer (Desktop) — Ein-Canvas-Layout mit Zonen

```text
┌───────────────────────────────────────────────────────────────┐
│  Zone A: Kopfleiste  [🧭 3dxAgent] [Status ●] [Szenario ⏸]     │
│                                                                │
│  Zone B: ┌─────────┐                    Zone E: HUD            │
│  linkes  │ Layer   │   Zone D: 3D-Canvas      (Kompass,        │
│  Panel   │ Manager │                       Koordinaten, FPS)   │
│          ├─────────┤                                        │
│          │ RF-Feld │                                        │
│          │ Palette │                                        │
│          └─────────┘                                        │
│                                                                │
│  Zone C: untere Toolbar  [🚨Evakuierung][🎯Taktik][📡RF][⏹]     │
└───────────────────────────────────────────────────────────────┘
```

| Zone | Inhalt | Aktionen (Details §5) |
|------|--------|----------------------|
| A | Titel, Verbindungsstatus, Szenario-Status, Geräteanzahl | — |
| B | Layer-Manager (Checkboxen + Slider), RF-Palette (Farbskala + Legende) | A-01…A-09, A-18…A-21 |
| C | Szenario-Buttons, Layer-Toggles, Messmodus, Export | A-10…A-17, A-22…A-25 |
| D | Canvas (Three.js) | Alle Kameragesten §4, Pick §4.3 |
| E | Kompass, Maßstab, Koordinaten, FPS, Alert-Badge | — |

---

## 3. Bildschirm-Detailspezifikation

### 3.1 Live-3D-View (Android, `fragment_live.xml` — Ausbau)

```text
┌──────────────────────────────────────────────┐
│  ┌─ Statusbar (HUD, Zone 1) ──────────────┐  │
│  │ ● Verbunden · EKF: FULL · ⛓ Tunnel OK  │  │
│  └─────────────────────────────────────────┘  │
│                                               │
│   ┌─ Alert-Banner (Zone 2, nur bei Alert) ─┐  │
│   │ ⚠️ Starker Sender 433,92 MHz   [Details]│ │
│   └─────────────────────────────────────────┘  │
│                    ╭──────────╮                │
│    ┌────────┐      │ 3D-Canvas │   ┌──────┐    │
│    │ Kompass│      │ (GLSurface│   │ +/–  │    │
│    └────────┘      │   View)   │   │ Zoom │    │
│                    ╰──────────╯   ├──────┤    │
│  ┌─ Koordinaten ─┐                │ 🏠   │    │
│  │ x: 0.3 y: 1.2 │                │ Reset│    │
│  └───────────────┘                ├──────┤    │
│                                   │ 🎥   │    │
│  ┌─ Layer-Leiste (Zone 4) ────────┤ Modi │    │
│  │ ☁️Punkte ✅ 🕸Mesh ☐ 📡RF ☐    └──────┘    │
│  │ 🧱RTI ☐ 🏷Tags ☐ 👤Avatare ☐            │    │
│  └───────────────────────────────────────────┘  │
└──────────────────────────────────────────────┘
```

**Zonen:**

| Zone | Element | Details |
|------|---------|---------|
| 1 | Statusleiste | Höhe 28 dp, `surface #1A1A2E` mit 85 % Deckkraft; Chips: Verbindung (● grün/rot), EKF-Modus (`FULL`/`DEGRADED`/`MINIMAL`), Tunnel (`⛓ OK`/`⛓ —`), Akku/Temperatur rechtsbündig. Update ≤ 1 Hz. |
| 2 | Alert-Banner | Nur bei `GatekeeperAlert`: Icon nach Kategorie, Text (1 Zeile, Ellipsis), Aktion **„Details"** öffnet Panel 7.2. Automatisches Ausblenden nach 5 s (außer `CRITICAL` — bleibt bis Quittierung). Hintergrund: `warning #FF8800` (WARNING) / `error #FF3333` (CRITICAL). |
| 3 | Canvas | `GLSurfaceView`, RENDERMODE_CONTINUOUSLY. Alle Layer §5. Kompass oben links (44 dp, Kreis mit N-Markierung, dreht mit Kamera-Yaw). Zoom-Steuerung rechts (+, −, 🏠 Reset, 🎥 Modus-Wahl). |
| 4 | Layer-Leiste | Horizontal scrollbar, `Chip`-Buttons (Material `ChipGroup`, checkable). Jeder Chip: Icon + Label + Checked-State. Reihenfolge = Render-Reihenfolge. |

**Kamera-Gesten (Modus `FREE`, Standard):** siehe §4.2.

### 3.2 Kartenansicht (Android, `fragment_map.xml` — Ausbau)

Bestehender `SurfaceView` (Canvas-Zeichnung in `MapRenderer.kt`) wird ergänzt:

- **Overlays (Zeichenreihenfolge):** Gebäudeumriss → RF-Heatmap-Fußabdruck (Zellfarben, blau→rot, Alpha 0.45) → **Triangulations-Anker** (Wi-Fi = grüne Ringe, BLE = blaue Ringe, A-40) → BLE-Token (Kreis, semantische Farbe) → Personen (Avatar-Punkt, `person`) → Bewegte Objekte (Pulsring 2 s) → markierte Objekte (gelber Rahmen).
- **Interaktionen:** 1-Finger-Pan, Pinch-Zoom (min 1:2000, max 1:20), Tap auf Token → Tooltip-Callout (MAC, RSSI, Batterie, Geschwindigkeit aus `TagVelocityTracker`), Long-Press → Markierung (gelb).
- **HUD:** Nordpfeil, Maßstabsleiste (unten links, dynamisch), Koordinaten des Kartenmittelpunkts.
- **Modus-Umschalter (oben rechts):** `Karte` ⇄ `Heatmap` ⇄ `Live` (blendet Overlays um).

### 3.3 Aura-Tab (NEU, `fragment_aura.xml`)

Drei vertikale Sektionen (ScrollView), keine Modal-Ebenen:

```text
┌─ 📡 Aura ────────────────────────────────┐
│ ⛓ Tunnel            [Verbunden · 10.0.0.1]│
│    Verlust: 0,2 % · Jitter: — · MTU 1420 │
│                                         │
│ ┌─ Spektrum (Live-Wasserfall) ────────┐  │
│ │  ▓▓▓░░░▓▓░░▓  (letzte 60 Zeilen)    │  │
│ │  433.05       434.79 MHz            │  │
│ │  [Band: 433 ▾] [⏸ Pause] [⛶ Voll]   │  │
│ └─────────────────────────────────────┘  │
│                                         │
│ ┌─ Erkannte Sender (klassifiziert) ────┐  │
│ │ 433,92 MHz · 32 kHz · OOK  [➕ Whitelist]│
│ │ 868,30 MHz · 125 kHz · LoRa [⚠️ Stark]  │
│ └─────────────────────────────────────┘  │
│                                         │
│ ┌─ Gatekeeper-Alerts ──────────────────┐  │
│ │ ⚠️ 14:02  Port-Scan 10.0.0.2 (BLOCK)│  │
│ │ ✅ 14:00  DNS „api.track…" (WARN)   │  │
│ └─────────────────────────────────────┘  │
└─────────────────────────────────────────┘
```

**Element-Spec:**

| Element | Details |
|---------|---------|
| Tunnel-Karte | Status (Icon + Text), IPs beider Peers, Paketverlust aus `IqDatagram.GapTracker`, MTU, Keepalive. Tippen → Panel 7.7 (WireGuard-Blueprint anzeigen/kopieren). |
| Wasserfall | Eigene `View` (Canvas): X = Frequenzachse (Bandbereich aus Spinner: 433/868/beide), Y = Zeit (60 Zeilen à 1 s), Farbe = Leistung (Jet-Skala §8). Buttons: Band-Spinner, Pause, Vollbild. |
| Senderliste | `RecyclerView`; je Zeile: Frequenz, Bandbreite, Modulation, Leistungs-Badge (⚪/🟡/🔴), Aktion **➕ Whitelist** (→ `Gatekeeper.whitelistTransmitter`). Tap auf Zeile → springt im Wasserfall zum Band + zeichnet Markierung. |
| Alert-Liste | `RecyclerView`, neueste zuerst, max. 200 Einträge. Farbcodierung nach Severity. Swipe nach links = Quittieren. Tap = Detail-Dialog 7.2. |

**Datenquellen:** `Gatekeeper.alerts` (SharedFlow), `AuraIntegrator.chunks` → Spektrum (FFT im `Dispatchers.Default`), `IqTunnelReceiver.stats()` (1 Hz Polling).

### 3.4 Szenario-Tab (`fragment_scenario.xml` — Panel-Ausbau)

Bestehender Spinner + Start/Stopp bleiben; dazu:

- **Parameter-Panel** (je Szenario dynamisch):
  - *Evakuierung:* Personen (Slider 1–200), Rauch (0–100 %), Ausgänge (Mehrfachauswahl), Personenstrom (Pers/s).
  - *Taktik:* Einheiten, Ausrüstung, Zeitbasis.
  - *Architektur:* Scan-Auflösung, Raumhöhe, IFC-Export-Optionen.
  - *Temporär:* Token-IDs, Beobachtungsdauer.
  - *Forschung:* Datensatzname, Versionierung (aus Checkliste v4.x).
- **Live-Übersicht während des Laufs:** Fortschrittsbalken, Kennzahlen (Personen im Gebäude, detektierte Bewegung, Atemfrequenz-Peaks aus `UwbDoppler`), Abbrechen-Button.
- **Export-Shortcut:** nach Stopp → Button „Analyse öffnen" (weiter zu Tab 📊).

### 3.5 Analyse-Tab (NEU, `fragment_analysis.xml`)

- **Historienliste:** `SpatialRecord`-Einträge (Datum, Gerät, Punktzahl, Konfidenz) mit Filterleiste (Gerät, Zeitraum).
- **Evaluierungsbericht:** `PipelineResult` + `EvaluationAgent.Evaluation` als Karten: Konfidenz, Punktdichte, Mapping-Residuum, Objekte (Art + Anzahl).
- **Export-Dialog 7.5:** glTF (Draco, < 5 MB Ziel — AURA.md §2), OBJ, PLY, IFC (BIM), JSON (versionierter Datensatz), PNG (Screenshot).
- **Vergleichsansicht:** zwei Datensätze übereinander (farbig getrennt) — für Vorher/Nachher.

### 3.6 Web-Visualizer (Desktop) — Ausbaudetails

- **Zone B (Layer-Manager):** Checkboxen: Punktwolke, Mesh, RF-Heatmap, RTI-Voxel, Tag-Vektoren, Avatare, Grid. Slider: RF-Opacity (0–100 %), Extrusionsskala (0,5×–3×), Voxel-Threshold (dB). Alle Änderungen wirken **sofort** (kein Apply-Button).
- **RF-Palette:** vertikaler Farbverlauf (blau→rot) mit Min/Max-dBm-Beschriftung; interaktive Min/Max-Handles (dragbar) setzen die Farbnormierung live um.
- **Zone C:** Buttons wie bestehend + `📡 RF-Feld`-Toggle (existiert), `📏 Messen`, `📷 Screenshot`, `⬇ Export`.
- **Zone E (HUD):** Kompass (dreht mit Kamera), FPS (gleitender Mittelwert, 1 Hz), Kamerakoordinaten, Zoom-Faktor, Alert-Badge (roter Kreis mit Anzahl, klickbar → Alert-Liste links).
- **Tastatur:** siehe §4.4.

---

## 4. Interaktionssystem

### 4.1 Kamera-Modi

| Modus | Zweck | Kamera-Verhalten | Ein-/Ausstieg |
|-------|-------|------------------|---------------|
| **FREE** | Standard-Navigation | Orbit um Zielpunkt (Android: Wisch = Rotieren, Pinch = Zoom, 2-Finger-Drag = Pan; Web: Maus-Rotate, Rechts-Drag Pan, Rad Zoom) | Standard; Button 🎥 wählt andere Modi |
| **FOLLOW** | Scanner-Bewegung verfolgen | Kamera folgt EKF-Position (Glättung α = 0,1), Blickrichtung frei | 🎥 → „Folgen" |
| **RÖNTGENBLICK** | „Röntgenblick" (AURA.md §4.3) | Kamera = Gerätepose aus `GeoPoseMapper.CameraPose` (Heading/Tilt/Roll). RTI-Voxel werden bildschirmfest positionsgetreu eingeblendet; eine **Visier-Linie** (Fadenkreuz + Entfernungszahl) zeigt die Blickachse | 🎥 → „Röntgenblick"; verlassen bei Tab-Wechsel oder ⛔-Button. Hinweis-Banner: „Halte das Gerät gegen die Wand" |
| **TOP-DOWN** | Einsatzplanung | Orthografische Draufsicht (oder perspektivisch 90°-Tilt), Norden oben | 🎥 → „Draufsicht" |
| **INSPECT** | Objekt zentrieren | Kamera schwenkt sanft (500 ms Ease) auf gewähltes Objekt, Orbit-Ziel = Objekt | Automatisch bei Pick (Doppeltipp) |

**Invariante:** In `RÖNTGENBLICK` sind alle Kamera-Gesten deaktiviert (nur Exit-Geste: Gerät senken > 45° Tilt für 1 s → Rückkehr zu FREE, mit Haptik).

### 4.2 Touch-Gesten-Tabelle (Android, Modus FREE)

| Geste | Aktion | Feedback | Konfliktregel |
|-------|--------|----------|---------------|
| 1-Finger-Drag | Kamera rotieren (Yaw+Pitch) | Live-Rendering | > 24 dp Bewegung, sonst Tap |
| 2-Finger-Pinch | Zoom (0,5 m … 200 m Distanz, log-Skala) | Maßstab + Zoom-Wert im HUD | — |
| 2-Finger-Drag | Kamera-Pan (Zielpunkt verschieben) | Live-Rendering | — |
| Tap (kurz) | Pick: Objekt unter Finger wählen → Halo-Ring 800 ms | Halo + Mini-Callout (Name + Wert) | Doppeltipp-Timer 300 ms |
| Doppeltipp | Kontext-Menü (Inspektions-Panel 7.1) | Panel schiebt von unten ein | Tap-Callout wird verworfen |
| Long-Press (600 ms) | Markieren (gelb, `marked #FFCC00`) / im Messmodus: Punkt setzen | Vibration 30 ms + Pin | — |
| 3-Finger-Drag links/rechts | Zwischen den 5 Tabs wechseln | Tab-Indikator | Nur wenn kein Modal offen |
| 2-Finger-Rotation | Roll der Kamera (nur FREE) | Kompass dreht | — |

### 4.3 Pick & Kontextmenü (Selektion)

- **Raycast-Priorität** (oberste Ebene gewinnt): Tag/Token > RTI-Voxel > Heatmap-Zelle > Avatar > Mesh > Punktwolke.
- **Halo:** 3D-Ring (Billboard) in `marked`-Gelb, Radius skaliert mit Entfernung (immer 24 dp am Bildschirm).
- **Kontextmenü (Bottom Sheet):** zeigt je Typ unterschiedliche Aktionen:
  - *Voxel:* Dämpfung (dB), Koordinaten, „Peak hier suchen", „Markieren".
  - *Tag/Token:* MAC, RSSI, Batterie, Geschwindigkeitsvektor, „Trail anzeigen" (10 s Historie als Linie), „Verfolgen".
  - *Heatmap-Zelle:* dBm, Höhe, „Zelle fixieren" (bleibt bei Rebuild markiert).
  - *Person (Avatar):* Typ korrigieren (Person/Gegenstand — Workflow aus UX.md), „Bewegung verfolgen".
- **Selektions-API:** `PickResult(typ, id, position, extra)` — wiederverwendbar von 2D-Karte und 3D-View.

### 4.4 Web (Maus & Tastatur)

| Eingabe | Aktion |
|---------|--------|
| Links-Drag | Rotieren (Orbit) |
| Rechts-Drag / Shift+Drag | Pan |
| Mausrad / Pinch (Trackpad) | Zoom |
| Links-Klick | Pick (Halo + Callout) |
| Doppelklick | Zentrieren auf Punkt |
| `1`…`7` | Layer 1…7 umschalten |
| `M` | Messmodus |
| `F` | Kamera auf Selektion fokussieren |
| `T` | Top-Down |
| `R` | Reset-Kamera |
| `Esc` | Selektion/Messung/Modal schließen |
| `P` | Screenshot |
| `H` | HUD ein/aus |

---

## 5. Aktionskatalog (vollständig)

Jede Aktion: **ID · Name · Trigger · Verhalten · Feedback · Datenquelle**.

### 5.1 Layer-Aktionen (Android Zone 4 / Web Zone B)

| ID | Name | Verhalten | Datenquelle |
|----|------|-----------|-------------|
| A-01 | Punktwolke an/aus | Toggle `Points`-Render | `AgentWebSocketClient.onBinaryPointCloud` |
| A-02 | Mesh an/aus | Toggle Delaunay-Mesh (halbtransparent, `wall`-Farbton) | `MeshGenerator.Mesh` |
| A-03 | RF-Heatmap an/aus | Toggle extrudierte Zellen | `RfHeatmapBuilder.ExtrudedCell` |
| A-04 | RTI-Voxel an/aus | Toggle halbtransparente Voxel | `RtiSolver.Voxel` |
| A-05 | Tags an/aus | Toggle Token + Geschwindigkeitsvektoren (Pfeile ∝ Geschwindigkeit, Farbe §8) | `TagVelocityTracker.TrackedTag` |
| A-06 | Avatare an/aus | Toggle Personen-Avatare | `avatar_update` (Web) / `DataInterpreter.InterpretedObject` |
| A-07 | Grid an/aus | Referenzraster (1 m, `#335577`) | statisch |
| A-08 | Semantische Farben | Punktwolken-Farbmodus: `Semantik` ⇄ `Höhe` ⇄ `Signalstärke` ⇄ `Einheitlich` | `DataInterpreter` / z-Wert / `dbm` |
| A-09 | DAB+-Türme an/aus | 3D-Türme je Senderstandort; Transparenz ∝ Signalqualität (AURA.md §6) | DAB+-Decoder (geplant) |

### 5.2 Kamera-Aktionen

| ID | Name | Trigger | Verhalten |
|----|------|---------|-----------|
| A-10 | Zoom +/− | Buttons rechts | Stufen ±20 % um Bildschirmmittelpunkt |
| A-11 | Reset-Kamera | 🏠 / Taste `R` | Zurück zu Standardpose (15 m, 30° Elevation) mit 400 ms Ease |
| A-12 | Modus-Wechsel | 🎥 / Menü | Umschalten FREE/FOLLOW/RÖNTGENBLICK/TOP-DOWN (§4.1) |
| A-13 | Objekt fokussieren | Doppelklick / Menü „Fokussieren" | Kamera-Orbit-Ziel = Objekt (INSPECT) |
| A-14 | Blickrichtung senden | RÖNTGENBLICK aktiv | `GeoPoseMapper`-Pose → CameraController (Android) bzw. Three.js-Kamera (Web) |

### 5.3 Mess- & Analyse-Aktionen

| ID | Name | Trigger | Verhalten | Feedback |
|----|------|---------|-----------|----------|
| A-15 | Distanz messen | `📏`-Button | 2 Punkte per Tap setzen → Linie + Beschriftung (m, cm-Genauigkeit) | Zahlenlabel an Linienmitte |
| A-16 | Pfad messen | `📏` → Modus „Pfad" | beliebig viele Punkte → Polylinie + Summe | Live-Summenlabel |
| A-17 | Messung löschen | `Esc`/✕ | entfernt letzte Messung | — |
| A-18 | RF-Opacity | Slider 0–100 % | globaler Alpha-Wert der RF-Layer | sofort |
| A-19 | Extrusionsskala | Slider 0,5–3× | skaliert Heatmap-Höhen + Voxel-Größe | sofort |
| A-20 | Voxel-Threshold | Slider (dB) | blendet Voxel unter Schwelle aus | Legende aktualisiert |
| A-21 | Min/Max-Farbgrenzen | Handle-Drag in Palette | ändert dBm-Normierung | Legende aktualisiert |
| A-22 | Peak-Suche | Menü „Peaks" | `RtiSolver.locatePeaks` → Pin-Marker an Maxima | Pins + Liste |

### 5.4 Szenario-Aktionen

| ID | Name | Verhalten |
|----|------|-----------|
| A-23 | Szenario starten | Parameter-Panel → Lauf startet (`scenario_start`), Live-Kennzahlen |
| A-24 | Szenario stoppen | stoppt Simulation, Ergebnis-Chip + Export-Shortcut |
| A-25 | Rauch-Visualisierung | Partikel-Layer (Alpha ∝ Rauchwert, Wind aus mmWave-Targets) |

### 5.5 Gatekeeper- & Sicherheitsaktionen

| ID | Name | Trigger | Verhalten | Feedback |
|----|------|---------|-----------|----------|
| A-26 | Alert anzeigen | Banner/Liste | Panel 7.2 mit Frequenz, Quelle, Empfehlung | — |
| A-27 | Alert quittieren | Swipe/Button | Alert → „quittiert" (bleibt in Historie) | Badge-Zähler sinkt |
| A-28 | Sender whitelisten | `➕ Whitelist` | `Gatekeeper.whitelistTransmitter(f, bw)` → keine weiteren Alerts für diesen Sender | Toast „Whitelisted" |
| A-29 | Verbindung blocken | „Blockieren" | `inspectEndpoint`-Verdict BLOCK (VpnService-Integration, AURA.md §5) | Rotes Schild-Icon am Eintrag |
| A-30 | Paket-Statistik | Tunnel-Karte tippen | zeigt Verlustrate/Jitter/Reordering-Graphen (letzte 5 min) | — |

### 5.6 Tag- & Tracking-Aktionen

| ID | Name | Verhalten |
|----|------|-----------|
| A-31 | Trail anzeigen | 10 s Positionshistorie als Linie mit Geschwindigkeits-Gradient |
| A-32 | Geschwindigkeitsvektoren | Pfeile (Länge ∝ m/s, Farbe §8), aktualisiert je `updatePosition` |
| A-33 | RC-Leistungsanalyse | Mini-Panel: v_max, v_avg, Beschleunigungs-Spikes (AURA.md §6) |

### 5.7 Export- & System-Aktionen

| ID | Name | Verhalten |
|----|------|-----------|
| A-34 | Screenshot | PNG ohne HUD (Option „mit HUD" für Berichte) |
| A-35 | Export glTF/OBJ/PLY/IFC/JSON | Dialog 7.5; glTF mit Draco < 5 MB |
| A-36 | Datensatz versionieren | Forschungs-Szenario: Name + Commit-artige Version |
| A-37 | HUD ein/aus | Taste `H` / Einstellungen |
| A-38 | Handschuh-Modus | alle Targets auf 56 dp, Haptik verstärkt, Doppeltipp-Zone vergrößert |
| A-39 | Farbenblind-Modus | zusätzlich Muster (Schraffur) in Heatmap/Voxel |

### 5.8 Triangulations-Aktionen (CT45P, docs/TRIANGULATION.md)

| ID | Name | Trigger | Verhalten | Feedback |
|----|------|---------|-----------|----------|
| A-40 | Anker anzeigen | Toggle `📶 Triang.` / Layer-Chip | Wi-Fi-Anker (grüne Ringe) + BLE-Anker (blaue Ringe) mit Labels auf Karte/3D-View | sofort |
| A-41 | Positions-Trail | Marker-Tap / Panel „Trail" | Historienlinie der fusionierten Position (60 s) mit Genauigkeits-Halo (±accuracyM) | Linie + Halo |

Datenquellen: `TriangulationService.fused`, `position_update` (WebSocket),
Anker-Konfiguration via `triangulation_anchors`. Status-Chip `📶` in HUD-Zone 1:
Modus `FULL` (RTT) / `DEGRADED` (BLE/FP) / `MINIMAL` + `±Genauigkeit`.

---

## 6. HUD-Element-Spezifikation

| Element | Position | Inhalt | Datenquelle | Update |
|---------|----------|--------|-------------|--------|
| Status-Chips | Zone 1 oben | Verbindung, EKF-Modus, Tunnel, Akku, Temperatur | WS-State, `EkfFusion`, `IqTunnelReceiver.stats()` | 1 Hz |
| Kompass | oben links (44 dp) | N-Markierung, dreht mit Kamera-Yaw | Kamera-Controller | Render |
| Zoom-Indikator | rechts, neben +/− | aktuelle Kameradistanz (m) | Kamera-Controller | 4 Hz, debounced |
| Koordinaten-Fußzeile | unten links | Kamera-Ziel x/y/z | Kamera-Controller | 4 Hz |
| Maßstabsleiste | unten links (Karte) | dynamische Balkenlänge + m | Zoom | Render |
| FPS (Web) | Zone E | Mittelwert 1 s | Renderer | 1 Hz |
| Alert-Badge | Zone E / Statusleiste | Anzahl offener Alerts (rot, 16 dp Kreis) | `Gatekeeper.alerts` | Event |
| Röntgenblick-Visier | Bildschirmmitte (nur Modus) | Fadenkreuz + Entfernung zur „Wand-Ebene" | `GeoPoseMapper` | Render |

---

## 7. Panels & Dialoge

### 7.1 Inspektions-Panel (Bottom Sheet)

Kopf: Typ-Icon, Titel („RTI-Voxel" / „Tag AA:BB" / …), ✕.
Inhalt: 2 Spalten Schlüssel/Wert (je Typ §4.3), Primäraktion („Markieren", „Verfolgen"), Sekundäraktion („Weitere Peaks", „Trail"). Keine Scroll-Sperre der Kamera dahinter.

### 7.2 Gatekeeper-Alert-Dialog

Kopf: Severity-Farbe + Icon, Zeitstempel.
Inhalt: Kategorie, Frequenz (falls RF), Quelle (IP/Port falls Netz), Klassifikation (Modulation, Band), **Empfehlungstext** (aus `message`), Aktionszeile: [➕ Whitelist] [🔒 Blockieren] [✔ Quittieren].
Historie-Zugang: „Alle Alerts" → Aura-Tab.

### 7.3 Layer-Manager (Web Zone B / Android Bottom Sheet)

Checkbox-Liste (§5.1) + Slider (§5.3). Speicherung der Konfiguration in `SharedPreferences`/`localStorage` (Persistenz über Sessions).

### 7.4 Spektrum-Panel (Aura-Tab)

Wasserfall + Band-Spinner + Pause/Vollbild (§3.3). Im Vollbild: 2-Finger-Zoom in Frequenzachse.

### 7.5 Export-Dialog

Format-Radio (glTF/OBJ/PLY/IFC/JSON/PNG), Checkboxen: „Nur markierte Objekte", „Mit RF-Layern (Textur)", „Draco-Kompression (empfohlen)". Fortschrittsbalken bei > 5 MB. Ziel-Auswahl: Speicherort (Android SAF) / Download (Web).

### 7.6 WireGuard-/Tunnel-Dialog

INI-Blueprint beider Peers (anzeigen + Kopieren), QR-Code des Scanner-Endpoints (alternativer Kanal für die Zweit-App), Status-Zeile (Handshake, letzte Aktivität).

### 7.7 Einstellungen

Gruppen: Sensoren (Kalibrierung, Rausch-Schwellen), Netzwerk (Agent-URL, Tunnel-Port, MTU), Darstellung (HUD, Farbmodus, Handschuh-Modus, Farbenblind-Modus), Speicher (Retention, Export-Pfad), Rechtliches (Hinweis AURA.md §8.2).

---

## 8. Farb- & Legendensystem

### 8.1 RF-Signalstärke (Heatmap/Zellen) — Jet-Skala

`HSL 0.66 → 0` (blau → cyan → grün → gelb → rot), Sättigung 0.9, Helligkeit 0.5.

| dBm (Standardnormierung) | Farbe | Hex-Beispiel |
|--------------------------|-------|--------------|
| ≤ −90 | Blau | `#0055FF` |
| −75 | Cyan | `#00D7FF` |
| −60 | Grün | `#00FF88` |
| −45 | Gelb | `#FFDD00` |
| ≥ −30 | Rot | `#FF2200` |

### 8.2 RTI-Dämpfung (Voxel)

Gleiche Skala, Normierung **relativ je Rekonstruktion** (min/max des aktuellen Felds) — absolute dB-Werte zusätzlich im Tooltip. Alpha: 0,45 (Web) / 0,35 (Android).

### 8.3 Tag-Geschwindigkeit (Vektoren/Trails)

| Geschwindigkeit | Farbe |
|-----------------|-------|
| 0–1 m/s | Blau `#4488FF` |
| 1–3 m/s | Grün `#44FF88` |
| 3–6 m/s | Orange `#FF8800` |
| > 6 m/s | Rot `#FF3333` |

### 8.4 Semantik (bestehend aus `colors.xml`)

`person #FF3333`, `wall #4488FF`, `floor #44FF88`, `furniture #AA8844`,
`unknown #FFFFFF`, `marked #FFCC00`, `moving #FF8800` — unverändert.

### 8.5 Status-Farben

`success #44FF88` · `warning #FF8800` · `error #FF3333` · `primary #0066FF` · `secondary #00C8A0`.

### 8.6 Legenden-Komponente (Web Zone B, Android Layer-Sheet)

Vertikaler Verlauf + Min/Max-Beschriftung + interaktive Handles (A-21). Zusätzlich:
- Semantik-Legende (7 Farbchips),
- Geschwindigkeits-Legende (4 Stufen),
- Transparenz-Hinweis („Opacity = Signalqualität" bei DAB+-Türmen).

---

## 9. Datenbindung (Flow → UI)

| Quelle (Kotlin) | UI-Element | Thread-Wechsel | Rate |
|-----------------|------------|----------------|------|
| `AuraIntegrator.rtiVoxels` | Voxel-Layer + Peak-Liste | → `Dispatchers.Main` | je Rekonstruktion |
| `AuraIntegrator.heatmapCells` | Heatmap-Layer | Main | je Rebuild (16 Chunks) |
| `AuraIntegrator.alerts` / `Gatekeeper.alerts` | Banner, Badge, Alert-Liste | Main | Event |
| `IqTunnelReceiver.chunks` | Spektrum-Wasserfall | Default (FFT) → Main | je Chunk (≈ 300 Hz → decimiert auf 1 Hz Zeilen) |
| `TagVelocityTracker.snapshots()` | Tag-Layer, RC-Panel | Main | 10 Hz |
| `EkfFusion.getState()` | FOLLOW-Kamera, Koordinaten-HUD | Main | 20 Hz |
| `ImuManager.imuUpdates` | RÖNTGENBLICK-Pose | Main | Sensordelay |
| `LiveSensorPipeline.frameStream` | Szenario-Kennzahlen | Main | 20 Hz |
| WebSocket `aura_voxels`/`aura_heatmap` (Web) | `applyAuraVoxels`/`applyAuraHeatmap` | — | Event |

**Regel:** Jeder Flow-Konsument nutzt `distinctUntilChanged` (bei Werttypen) bzw. Debouncing ≥ 100 ms für Textanzeigen. Kein UI-Update aus `Dispatchers.Default` ohne `withContext(Main)`.

---

## 10. UI-Zustandsmaschine

```text
                    ┌──────────┐
        App-Start ─▶│   BOOT   │─── Berechtigungen/Init ──▶┌──────────┐
                    └──────────┘                          │   IDLE   │◀─┐
                                                          └────┬─────┘  │
              ┌───────────────┬──────────────┬────────────┬────┴───────┴─┐
              ▼               ▼              ▼            ▼               │
        ┌───────────┐  ┌────────────┐  ┌───────────┐  ┌────────────┐      │
        │ SCANNING  │  │  INSPECT   │  │  MEASURE  │  │SCENARIO_RUN│      │
        │ (Scanner- │  │ (Objekt    │  │ (Punkte   │  │ (Live-Lauf)│      │
        │  Knoten)  │  │  gewählt)  │  │  setzen)  │  └─────┬──────┘      │
        └─────┬─────┘  └─────┬──────┘  └─────┬─────┘        │            │
              │              │               │              │            │
              └──────────────┴───────────────┴──────────────┘            │
                                    │  Esc/✕/Fertig                      │
                                    ▼                                     │
                              ┌──────────┐    Alert (CRITICAL)    ┌───────┴───┐
                              │  IDLE    │◀───────────────────────│ ALERT_ACT │
                              └──────────┘                        │ (Banner/  │
                                                                  │  Panel)   │
                                                                  └───────────┘
```

**Transitionen & Invarianten:**

| Von → Nach | Bedingung | UI-Wirkung |
|-----------|-----------|------------|
| IDLE → SCANNING | Tunnel-Handshake + SDR-Quelle aktiv | Status-Chip „⛓ OK", Wasserfall startet |
| IDLE → INSPECT | Doppeltipp mit Treffer | Bottom-Sheet, Halo, Kamera schwenkt |
| IDLE → MEASURE | 📏-Button | Fadenkreuz-Cursor, Layer-Leiste pausiert |
| IDLE → SCENARIO_RUN | Start-Button + Parameter valide | Kennzahlen-Panel, Start-Button → „Läuft…" |
| * → ALERT_ACT | `CRITICAL`-Alert | Banner persistent, Vibration (2× 100 ms) |
| ALERT_ACT → IDLE | Quittieren | Banner aus, Badge −1 |
| MEASURE → IDLE | `Esc`/✕ | Messlinien bleiben sichtbar (Layer „Messungen") |

**Invarianten:** (1) Modal-Ebene blockiert Kamera-Gesten nie; (2) in RÖNTGENBLICK sind Kamera-Gesten deaktiviert; (3) CRITICAL-Alerts sind nicht auto-dismissbar; (4) Tab-Wechsel beendet INSPECT/MEASURE sauber (Zustand bleibt als Layer erhalten).

---

## 11. Performance- & LOD-Regeln (UI-seitig)

| Regel | Wert |
|-------|------|
| Android: max. Punkte im Renderer | 150 000 (darüber Dezimierung 1:2, 1:4) |
| Android: max. Voxel | 4 000 (Schwelle + Top-k durch `locatePeaks`) |
| Web: max. Instanzen je RF-Layer | 6 000 (existiert, `MAX_RF_INSTANCES`) |
| Heatmap-Rebuild | alle 16 Chunks (existiert, `HEATMAP_REBUILD_EVERY`) |
| HUD-Text-Updates | debounced ≥ 100 ms, nie im Render-Thread |
| Wasserfall | 1 Zeile/s, Ringpuffer 60 Zeilen, Canvas-Reuse |
| Kamera-Easing | 400–500 ms, `smoothstep` |
| GC-Vermeidung | keine `List`-Allokationen im Render-Loop; `FloatArray`-Puffer wiederverwenden |

---

## 12. Ergonomie & Barrierefreiheit

| Anforderung | Umsetzung |
|-------------|-----------|
| Handschuh-Modus (A-38) | Targets 56 dp, Haptik +30 ms, Doppeltipp-Erkennungszone 1,5×, Button „An/Aus" in Statusleiste |
| Kontrast | Text ≥ 4,5:1 auf `#0D0D1A`/`#1A1A2E` (weiß/`#88ddff`/`#ddaaff` geprüft) |
| Farbenblind-Modus (A-39) | RF-Layer zusätzlich Schraffur (Checker bei hoher Leistung), Tag-Vektoren mit Pfeilspitzen statt nur Farbe |
| TalkBack | Canvas bekommt `contentDescription`-Aggregat („3D-Ansicht: 12 400 Punkte, 3 Alerts"); jedes Panel-Element hat Rolle + Aktion |
| Schriftgrößen | `sp`-Einheiten, HUD-Chips ≥ 12 sp, Titel 18 sp |
| Einhand | primäre Aktionen rechts unten; Back-Navigation = 3-Finger-Wisch + System-Back |
| Nachtsicht | optionaler Rot-Filter-Modus (Einstellungen) |

---

## 13. Umsetzungsplan (Phasen mit Datei-Mapping)

### Phase 0 — Web-Visualizer-Fertigstellung (sichtbar & testbar, 1–2 Tage)

| # | Aufgabe | Dateien |
|---|---------|---------|
| 0.1 | Layer-Manager-Panel (Checkboxen + Slider) mit `localStorage`-Persistenz | `web-visualizer/public/index.html`, `main.js`, `styles.css` |
| 0.2 | RF-Palette mit dragbaren Min/Max-Handles (A-21) | `main.js`, `styles.css` |
| 0.3 | Messmodus (A-15/A-16) + Screenshot (A-34) | `main.js` |
| 0.4 | Alert-Badge + Alert-Liste (A-26/A-27) | `main.js`, `index.html` |
| 0.5 | Tastatur-Shortcuts (§4.4) + HUD (Kompass/FPS/Koordinaten) | `main.js` |
| **Test** | `node --check`, manuelle Checkliste §14 |

### Phase 1 — Android-Navigation & Layouts (1–2 Tage)

| # | Aufgabe | Dateien |
|---|---------|---------|
| 1.1 | Bottom-Nav auf 5 Tabs (Aura, Analyse) + Icons | `menu/bottom_nav_menu.xml`, `strings.xml` |
| 1.2 | `AuraFragment` (Layout §3.3: Tunnel-Karte, Wasserfall, Senderliste, Alerts) | neu `ui/aura/AuraFragment.kt`, `res/layout/fragment_aura.xml` |
| 1.3 | `AnalysisFragment` (§3.5) + `SettingsFragment` (§7.7) | neu `ui/analysis/`, `ui/settings/`, Layouts |
| 1.4 | Statusleisten-Chips + Alert-Banner in Live-View | `fragment_live.xml`, `LiveViewFragment.kt` |
| 1.5 | Layer-Leiste (ChipGroup) in Live-View | `fragment_live.xml`, `LiveViewFragment.kt` |
| **Test** | Navigation, States IDLE/BOOT, Layout-Inspektion |

### Phase 2 — Interaktionssystem (2–3 Tage)

| # | Aufgabe | Dateien |
|---|---------|---------|
| 2.1 | `CameraController` (Modi FREE/FOLLOW/RÖNTGENBLICK/TOP-DOWN/INSPECT, Easing) | neu `ui/live/CameraController.kt` |
| 2.2 | Gesten-Recognizer (Pinch/2-Finger/Rotation/3-Finger, Tap-Doppeltipp-Diskriminierung, Long-Press) | neu `ui/gestures/GestureController.kt` |
| 2.3 | Pick/Raycast + Halo + Bottom-Sheet (7.1) | `PointCloudRenderer.kt` (+Raycast), neu `ui/live/InspectSheet.kt` |
| 2.4 | RÖNTGENBLICK: `GeoPoseMapper` → Kamera + Visier + Exit-Geste | `CameraController.kt`, `LiveViewFragment.kt` |
| 2.5 | Messmodus (Punkte/Linien/Labels) | neu `ui/live/MeasurementLayer.kt` |
| **Test** | Gesten-Matrix §14, Röntgenblick-Tilt-Exit |

### Phase 3 — Aura-Features im UI (2 Tage)

| # | Aufgabe | Dateien |
|---|---------|---------|
| 3.1 | Wasserfall-View (Canvas, Ringpuffer, Band-Spinner, Pause/Vollbild) | neu `ui/aura/SpectrumWaterfallView.kt` |
| 3.2 | Senderliste + Whitelist-Aktion (A-28) | `AuraFragment.kt`, `Gatekeeper.kt` |
| 3.3 | Alert-Pipeline UI (Banner, Badge, Liste, Quittieren) | `AuraFragment.kt`, `LiveViewFragment.kt` |
| 3.4 | Tunnel-Dialog mit INI + QR (7.6) | neu `ui/aura/TunnelDialog.kt` |
| 3.5 | RF-Layer im 3D-View (Heatmap + Voxel + Peaks) | `PointCloudRenderer.kt` (Instancing) |
| **Test** | Demo-Feeder (`aura_demo.py`) als Datenquelle |

### Phase 4 — Renderer-Erweiterungen & Karte (2 Tage)

| # | Aufgabe | Dateien |
|---|---------|---------|
| 4.1 | Farbmodi (Semantik/Höhe/Signal) + Layer-Toggles im Renderer | `PointCloudRenderer.kt` |
| 4.2 | Tag-Trails + Geschwindigkeitsvektoren (A-31/A-32) | `PointCloudRenderer.kt`, `TagVelocityTracker.kt` |
| 4.3 | Karten-Overlays (Heatmap-Fußabdruck, Token, Pulsringe) + Maßstab | `MapRenderer.kt`, `MapFragment.kt` |
| 4.4 | RC-Leistungsanalyse-Panel (A-33) | `AuraFragment.kt` |
| 4.5 | DAB+-Turm-Layer (A-09, wenn Decoder verfügbar) | `PointCloudRenderer.kt` |
| **Test** | Layer-Kombinationen, 60-FPS-Check mit 6 000 Instanzen |

### Phase 5 — Härtung & Feldtest (1–2 Tage)

| # | Aufgabe | Dateien |
|---|---------|---------|
| 5.1 | Handschuh-Modus + Farbenblind-Modus + Rot-Filter | `SettingsFragment.kt`, `styles` |
| 5.2 | TalkBack-Rollen + Kontrast-Audit | alle Layouts |
| 5.3 | Persistenz (Layer-Konfiguration, Sender-Whitelist) | `SharedPreferences`, `AppDatabase` |
| 5.4 | UI-Unit-Tests (State-Machine, Gesten-Controller, Pick-Priorität) | `app/src/test/.../ui/` |
| 5.5 | Feldtest-Checkliste mit echten SDR-Daten | `docs/CHECKLIST.md` (Aura-Abschnitt) |

**Abhängigkeiten:** 0 → 1 → 2 → 3/4 parallel → 5. Jede Phase endet mit Definition-of-Done-Prüfung §14.

---

## 14. Akzeptanzkriterien (Definition of Done)

1. **Alle 41 Aktionen (§5)** sind über Button *oder* Geste erreichbar; jede hat sichtbares Feedback (visuell/haptisch) ≤ 100 ms.
2. **Layer-Kombinatorik:** jede der 7 Layer-Kombinationen rendert ohne Fehler; RF-Layer erreichen 6 000 Instanzen bei ≥ 30 FPS auf Referenzhardware (CT45P, Qualcomm QCM4290).
3. **Gesten-Matrix:** Pinch, 2-Finger-Drag, Rotation, 3-Finger-Tab, Tap, Doppeltipp, Long-Press funktionieren im Feldversuch mit Handschuhen (5/5 Testpersonen).
4. **Röntgenblick:** Heading/Tilt/Roll folgen der Gerätepose (Fehler < 5° im Vergleichstest); Exit-Geste funktioniert in < 1,5 s.
5. **Alert-Kette:** CRITICAL-Alert → Banner + Vibration in < 1 s; Quittierung reduziert Badge; Whitelist unterdrückt Folge-Alerts (Unit-Test in `GatekeeperTest` vorhanden).
6. **Export:** glTF < 5 MB mit Draco (AURA.md §2), IFC/JSON valide (Schema-Check).
7. **Barrierefreiheit:** Kontrast-Audit bestanden, TalkBack-Fokus-Reihenfolge logisch, Farbenblind-Modus aktivierbar.
8. **Kein Regression:** bestehende 33 Edge-Agent-Tests + 51 JVM-Tests grün, `node --check` sauber, kein UI-Code im Render-Thread.
9. **Offline:** Web-Visualizer lädt und interagiert ohne Agent (Layer-Manager, Kamera, Messen), Android-App voll funktional im Offline-Modus (`offline/`-Paket).
10. **Doku:** dieses Dokument ist bei Abweichungen aktualisiert; UX.md verlinkt auf die neuen Tabs.

---

## Anhang A — Änderungen gegenüber UX.md (v3.2.0)

| Alt (UX.md) | Neu |
|-------------|-----|
| 3 Tabs (3D/Karte/Szenario) | 5 Tabs (+Aura, +Analyse), Einstellungen in Toolbar |
| Nur 1-Finger-Drag + Pinch | vollständige Gesten-Matrix §4.2 (2/3-Finger, Rotation, Long-Press) |
| 5 Hauptansichten ohne Aura | Aura-Tab (Spektrum, Gatekeeper, Tunnel) als 6. Ansicht |
| Semantische Farben nur | + RF-/Dämpfungs-/Geschwindigkeits-Skalen §8 |
| Keine Kamera-Modi | 5 Modi inkl. RÖNTGENBLICK (§4.1) |

## Anhang B — Offene Punkte

| Punkt | Entscheidung nötig |
|-------|--------------------|
| Karten-Rendering: `SurfaceView`-2D vs. Google Maps 3D SDK | Abhängig von Preview-Lizenz (AURA.md §8, Status ⏳) |
| DAB+-Decoder-UI (Türme) | erst nach libwelle/libdab-Portierung |
| VpnService-UI (Systemdialog „VPN-Verbindung erlauben") | Android-Systemdialog, kann nicht angepasst werden |
