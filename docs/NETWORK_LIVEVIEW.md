# 🌐 Aktive Netzwerkvisualisierung im LiveView — Machbarkeitsprüfung & Integration

> **Version:** v1.0 · **Datum:** 14. August 2026 ·
> **Eingabe:** „3dxAgent – Aktive Netzwerkvisualisierung im LiveView"
> (v14.1.0-NetworkLiveView)
>
> Einordnung, Fehlerkatalog des Spec-Codes und Integration der testbaren
> Kerne (Traffic-Simulator, zentrale Farbcodierung, Aktivitäts-/Heatmap-
> Aggregation, Live-Traffic-Stream) in Python + Kotlin + Web-Visualizer.

---

## 1. Machbarkeitsbefund

| Spec-Komponente | Befund | Umsetzung |
| :--- | :--- | :--- |
| NetworkTrafficSimulator | ✅ reiner Kern — wurde in der Spec **referenziert, aber nie implementiert** | **neu:** `edge-agent/network_traffic.py` + `network/NetworkTraffic.kt` (seeded, Bursts, Latenz-Kopplung) |
| LiveTrafficParticles / ConnectionLineManager | ✅ weitgehend vorhanden | Topologie-Layer (`main.js`) um **Live-Traffic-Steuerung** erweitert (Partikelzahl/-speed/-farbe je Bandbreite, Linienfarbe je Bandbreite/Latenz) |
| ActivityIndicator | ✅ machbar | Knoten pulsieren bei aktiven Flüssen (sanfter Puls); Latenz-Alarm färbt Knoten rot |
| BandwidthHeatmap | ✅ machbar | 3D-Säulen unter den Knoten (Durchsatz ∝ Höhe, relative Normalisierung) |
| WebSocket Data Stream | ✅ vorhanden — Spec nutzte fiktive URL (`wss://network-monitor.3dxagent.com/ws`) | bestehender Edge-Agent-Hub: WS-Typ `network_traffic` + REST `/api/v1/network/traffic*` |
| NetworkLiveViewFragment / NetworkDeviceDiscoveryService (Android) | 🟡 UI-Glue | referenziert nicht existierende Klassen — als Roadmap dokumentiert (bestehende Fragmente + `DeviceRegistry` decken die Datenlage) |

## 2. Fehlerkatalog (Spec-Code, korrigiert)

| # | Spec-Code | Problem | Korrektur |
| :--- | :--- | :--- | :--- |
| 1 | `Math.sin(time * 3 + p.id)` | Mesh hat kein Feld `id` → `undefined` → **NaN-Opacity** (Rendering bricht) | Puls über Array-Index |
| 2 | `line.material.linewidth = 2/3` | `LineBasicMaterial.linewidth` wird auf den meisten Plattformen **ignoriert** (WebGL-Limit 1 px) | Opazitäts-/Farbcodierung statt Linienbreite |
| 3 | Farbcodierung dreifach & inkonsistent (Partikel: Rot > 100; Linien: Rot > 100, Orange > 50, Gelb > 20 …) | gleiche Daten, verschiedene Farben je Modul | **zentrales Mapping** `traffic_color`/`severity` (Kotlin + Python + JS identisch) |
| 4 | `findActiveConnections` nutzt Typen `ROUTER/SWITCH/SERVER` | existieren im `DeviceType`-Enum nicht | an das reale Device-/Topologie-Modell angepasst (Kanten = Topologie-Edges/Flüsse) |
| 5 | Latenz „Nächste Schritte" | Spec-Liste nennt Latenz-Farbcodierung als offen | bereits enthalten: Latenz > 100 ms → Rot (dominiert), > 40 ms → Orange |
| 6 | `NetworkDataStreamer` mit externer URL | fiktiver Endpoint + Service-Worker-Missverständnis (s. docs/SERVICE_WORKER.md) | Edge-Agent-WebSocket (`/ws/agent/events`) |
| 7 | `getBandwidthColor` vs. `traffic_color`-Schwellen widersprüchlich dokumentiert | Kommentare vs. Werte | einheitliche Schwellen-Tabelle (§3) |

## 3. Zentrale Schwellen & Farben (einheitlich über alle Sprachen)

| Bedingung | Farbe | Severity |
| :--- | :--- | :--- |
| Latenz > 100 ms **oder** Bandbreite > 100 Mbit/s | Rot `0xFF3333` | critical |
| Bandbreite > 50 Mbit/s **oder** Latenz > 40 ms | Orange `0xFF8800` | warning |
| Bandbreite > 20 Mbit/s | Gelb `0xFFFF00` | warning |
| Bandbreite > 10 Mbit/s | Grün `0x44FF88` | normal |
| sonst | Blau `0x4488FF` | idle |

Partikel: Anzahl = min(5, max(1, bw/10)), Geschwindigkeit = 0,2 + bw/1000,
Größe = 0,03 + bw/5000 (Spec-Formeln).

## 4. Übernommene Module

### 4.1 Python (`edge-agent/network_traffic.py`)

- `TrafficFlow` (source/target, Bandbreite, Latenz, Paketverlust),
- `traffic_color`/`severity` (zentrales Mapping, Latenz dominiert),
- `particle_count/speed/size`,
- `aggregate_activity` → `NodeActivity` (Gesamtdurchsatz, Flusszahl, max. Latenz),
  `top_nodes` (Ranking), `heatmap_columns` (relative Säulenhöhen),
- `NetworkTrafficSimulator` (seeded, Bursts ×3, Latenz-Auslastungs-Kopplung,
  Paketverlust aus Latenz; Zeitreihen via `simulate_steps`).

### 4.2 Kotlin (`network/NetworkTraffic.kt`)

Identische Numerik — für die CT45P-App (LiveView-Berechnung on-device).

### 4.3 Edge-Agent

| Endpunkt | Funktion |
| :--- | :--- |
| `POST /api/v1/network/traffic` | Live-Traffic-Ingest (SNMP/NetFlow-Adapter oder App) → Broadcast |
| `POST /api/v1/network/traffic/simulate` | deterministische Flusssimulation auf den Topologie-Kanten (Demo/Last-Test) |

WS-Typen: `network_traffic` (Ingest) → Broadcast `network_traffic_update`
(Flüsse + Aktivität + Heatmap) an alle Visualizer.

### 4.4 Web-Visualizer (Topologie-Layer-Upgrade)

- Flüsse steuern je Kante: Linienfarbe (zentrales Mapping), Opazität,
  **Partikelanzahl/-geschwindigkeit/-farbe**,
- Knoten-Aktivität: sanfter Puls bei aktivem Datenfluss, **Rot bei
  Latenz-Alarm** (ActivityIndicator),
- **Bandbreiten-Heatmap:** halbtransparente Säulen unter den Knoten,
  Höhe ∝ relativer Durchsatz, Farbe = Severity,
- HUD: Flussanzahl + Summen-Durchsatz.

## 5. Verifikation

- **Python: 11 neue Tests** (Schwellen, Latenz-Dominanz, Severity-Kohärenz,
  Partikel-Formeln, Aggregation, Ranking, Heatmap-Normalisierung,
  Determinismus, Bursts, Zeitreihen, Roundtrip) — Gesamt **122/122 grün**.
- **Kotlin: 9 neue JVM-Tests** (gespiegelt) — Gesamt **157**.
- Live-Smoke (REST + WS-Broadcast) nach Commit-Integration.

## 6. Roadmap

| Phase | Inhalt | Status |
| :--- | :--- | :--- |
| **NLV 1.0** | Simulator, zentrales Mapping, Aggregation/Heatmap, REST/WS, Visualizer-Upgrade | ✅ |
| NLV 1.1 | SNMP/NetFlow-Adapter hinter `POST /api/v1/network/traffic` | ⏳ |
| NLV 1.2 | Latenz-/Durchsatz-Zeitreihen-Diagramme (Canvas) für ausgewählte Verbindungen | ⏳ |
| NLV 1.3 | Android LiveView-Fragment (3D-View mit Traffic-Layer, UI_UX_PLAN) | ⏳ |
