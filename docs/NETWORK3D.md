# 🌐 3D Network Virtualization & Spatial Topology Engine

> **Version:** v1.0 · **Datum:** 14. August 2026 ·
> **Eingabe:** „3dxAgent – 3D Network Virtualization" (v7.0.0-Network3D, inkl. eigener OSS-Recherche)
>
> Machbarkeitsprüfung, Übernahme der Empfehlungen der beiliegenden
> Projekt-Recherche und Integration der umsetzbaren Kernlogik
> (Topologie-Graph, What-If-Failover, Time Machine, Visualizer-Layer).

---

## 1. Machbarkeitsbefund

### 1.1 Architektur-Einordnung

Die v7.0.0-Spezifikation beschreibt die Engine in **TypeScript/React Three
Fiber** (`NetworkGraphEngine.ts`, `react-three-fiber`, `graphology`,
`ForceGraph3D`). Das bestehende Repository rendert den Web-Visualizer jedoch
**vanilla Three.js** (kein React, keine npm-Runtime-Abhängigkeiten) und
verarbeitet Netzwerkdaten nativ auf dem CT45P (`WifiRttTriangulator`,
`BleBeaconTriangulator`, `Gatekeeper`, `NetworkDataCollector`) sowie im
Edge-Agent (FastAPI/WebSocket-Hub).

**Entscheidung (entspricht der Spec-Empfehlung „Bestehende Komponenten
übernehmen, fehlende selbst entwickeln"):** `3d-force-graph`/`Reagraph`
werden als **Referenz** genutzt (Force-Directed-Layout, Partikel entlang
gerichteter Kanten, LOD/Expand-Collapse — vgl. deren Funktionsumfang), aber
**nativ in Three.js umgesetzt** statt als neue Abhängigkeit eingebunden —
kein React im Stack, volle Kontrolle über die bestehende Szene
(InstancedMesh, CSS2D-Labels, Layer-System aus `UI_UX_PLAN.md`).

### 1.2 Was ist implementierbar, was nicht

| Spec-Komponente | Befund | Umsetzung |
| :--- | :--- | :--- |
| Network Graph Engine (Nodes/Edges, Farbcodierung, Drill-Down) | ✅ machbar | `edge-agent/network_topology.py` + Visualizer-Layer |
| **What-If-Simulation** (Failover/Redundanz) | ✅ machbar — von der Spec-Recherche selbst als „Neuland" (kein OSS-Vergleichsprojekt) eingestuft | Dijkstra-basierte Failover-Simulation, REST + Broadcast |
| **Time Machine** (historisches Replay) | ✅ machbar — ebenso „Neuland" laut Recherche | Snapshot-Historie mit Replay-Fenster |
| Spatial Alerts | ✅ teilweise | Gatekeeper-Alerts existieren; kritische Nodes pulsieren im Visualizer |
| SNMP/LLDP-Ingestion (UDP 161) | 🟡 Adapter | `TopologyGraph` hat eine saubere `from_dict`-Schnittstelle; echter SNMP-Poller (pyasn1) als Roadmap-Adapter hinter dem Interface |
| Kubernetes-API-Watcher | 🟡 Adapter | Gleiches Muster: Watcher → `ingest(nodes, edges)` |
| eBPF/NetFlow-Collector | 🟡 Adapter | Flow-Daten als `flows` in die Simulation; Collector extern (Roadmap) |
| Prometheus-Query | 🟡 Adapter | HTTP-API — trivial anbindbar; als Roadmap-Adapter dokumentiert |
| React Three Fiber-Komponenten | 🔴 nicht übernommen | Kein React im Stack — native Three.js-Portierung |
| Force-Directed-Layout (FA2/d3-force-3d) | 🟡 vereinfacht | Layout kommt vom Edge-Agent (statische Koordinaten); Live-Force-Layout als Roadmap |

## 2. Übernommene Komponenten (dieses Update)

### 2.1 `edge-agent/network_topology.py` — Graph-Kern

- `TopologyNode`/`TopologyEdge` (Typen, Status, Metriken, Bandbreite/Latenz/Auslastung),
- `TopologyGraph`: Upsert, Kaskaden-Entfernung, **Dijkstra** (Latenz als Gewicht),
- `simulate_failover(failing_node, flows)` → betroffene/reroutete/unerreichbare Flows
  (temporäre Degradierung mit exakter Restauration),
- `TopologyHistory` (Time Machine): Snapshot-Fenster + `replay(index)`.

**Verifiziert:** 8 Tests — Umwege-Routing, Failover-Rerouting über Alternativpfad,
Unreachable-Markierung bei Artikulationsknoten, Kaskaden-Entfernung, History-Replay/Cap.

### 2.2 Edge-Agent-Endpunkte

| Endpunkt | Funktion |
| :--- | :--- |
| `POST /api/v1/network/topology` | Topologie-Ingest (Upsert) + WS-Broadcast `network_topology` + Snapshot |
| `GET /api/v1/network/topology` | aktuelle Topologie (Initial-Load) |
| `POST /api/v1/network/simulate` | What-If-Failover (+ Broadcast `topology_simulation`) |
| `GET /api/v1/network/history` | Time-Machine-Replay (einzelner Index oder Liste) |
| `GET /api/v1/network/devices` | Geräte des Live-Trackers |

### 2.3 Web-Visualizer — Topologie-Layer

- Nodes als Sphären (Typ-Farben: Router/Switch/Firewall/VM/Container/Cloud/Server/Sensor;
  Status-Override: critical rot, warning gelb, down grau),
- **kritische Nodes pulsieren** (Spatial Alert),
- Edges als Quadratische-Bézier-Splines mit Auslastungsfarben (grün/gelb/rot),
- **Flow-Partikel** entlang der Kanten (Geschwindigkeit ∝ Auslastung —
  3d-force-graph-Partikelmuster),
- Toggle `🌐 Topologie`, Statuszeile, What-If-Ergebnis im HUD.

## 3. Farbcodierung (aus der Spec übernommen)

| Farbe | Hex | Bedeutung |
| :--- | :--- | :--- |
| Grün | `#44FF88` | Normalbetrieb / geringe Auslastung |
| Gelb | `#FFCC00` | Warnung / mittlere Auslastung |
| Rot | `#FF3333` | Kritisch / DDoS / hohe Auslastung |
| Blau | `#4488FF` | Router/Switch |
| Violett | `#AA44FF` | VM/Container |
| Weiß | `#FFFFFF` | Cloud/extern |
| Orange | `#FF8800` | Firewall |
| Cyan | `#00FFCC` | Datenfluss (Partikel) |

## 4. Roadmap

| Phase | Inhalt | Referenz |
| :--- | :--- | :--- |
| NET 1.0 | Graph-Kern, What-If, Time Machine, Visualizer-Layer | ✅ |
| NET 1.1 | SNMP/K8s/Prometheus-Adapter hinter der `ingest`-Schnittstelle | pyasn1/kubernetes-Client |
| NET 1.2 | Live-Force-Directed-Layout (d3-force-3d-Muster) im Edge-Agent | 3d-force-graph |
| NET 1.3 | LOD: global (Cluster) → Subnet → Server-Slot (Expand/Collapse wie Reagraph) | Reagraph |
| NET 1.4 | Simulations-Modus (Verkehrsmatrix, Redundanz-Policies) auf Basis von `simulate_failover` | — |

## 5. Referenzen (aus der beiliegenden Recherche)

- **3d-force-graph / r3f-forcegraph** — Standard für 3D-Force-Directed-Graphen; Partikel & Orbit-Controls (Referenz für Partikel-Layer).
- **Reagraph** — WebGL-Graph für React; Expand/Collapse, Pfadfindung, Clustering (Referenz für LOD-Roadmap).
- **igraph-vlk** — Vulkan-Viewer für 500k+ Knoten (experimentell; Referenz für Großgraphen — für den CT45P nicht erforderlich).
- **JT-GELFLOW / traffic-visualisation** — Echtzeit-Traffic-Visualisierung (Flow-/Globus-Ansichten; Referenz für Flow-Partikel).
- **Flow Anomaly Detection** — ML-Anomalieerkennung auf NetFlow (Referenz: unsere Anomalie-Erkennung läuft über `Gatekeeper` + `AdaptiveThresholdMonitor`).
- Die Spec-Recherche stuft **Raum-Mapping-Integration** (Netzwerk + LiDAR in einer Szene) als echte Innovation ein — genau das liefert die bestehende Unified View (RF-/RTI-/Triangulations-Layer + neuer Topologie-Layer).
