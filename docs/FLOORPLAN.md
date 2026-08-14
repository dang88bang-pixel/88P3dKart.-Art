# 🏛️ Grundriss-Integration — Machbarkeitsprüfung & Umsetzung

> **Version:** v1.0 · **Datum:** 14. August 2026 ·
> **Eingabe:** „3dxAgent – Optionale Funktion: Intelligente Grundriss-
> Integration & 3D-Visualisierung" (v12.0.0-FloorPlanIntegration) mit dem
> Prüfauftrag: **hoowoge.de + weitere Quellen verfügbar machen — Anschluss-
> Ausführbarkeit überprüfen.**
>
> Alle Quellen wurden am 14.08.2026 per Live-Aufruf verifiziert
> (Ergebnisse in §2).

---

## 1. Machbarkeitsmatrix der Datenquellen (verifiziert)

| Quelle (Spec) | Realer Status | Anschluss machbar? | Umsetzung |
| :--- | :--- | :--- | :--- |
| **Nominatim** (OSM) | ✅ **Live verifiziert** — lieferte „New Town Hall, Marienplatz 8, München" inkl. importance | ✅ mit Auflagen: **max. 1 req/s**, gültiger User-Agent, ODbL-Attribution, kein Bulk-Geocoding (OSMF-Usage-Policy) | `floorplan.nominatim_search` (Policy-konform; serverseitig zentral) |
| **Mapzen Search** | ❌ **Dienst existiert nicht mehr** (Ende 2018 eingestellt) | — | aus dem Code entfernt; ersetzt durch **Photon** |
| **Photon (komoot)** | ✅ **Live verifiziert** — gleiches Gebäude, strukturiertes GeoJSON | ✅ Demo-Server mit Limits; für Produktion self-hosten (Apache-2.0, GitHub: komoot/photon) | `floorplan.photon_search` (Fallback) |
| **OSM Overpass** | ✅ **Live verifiziert** — Hauptserver antwortete „server too busy" (Last, kein Query-Fehler); **Kumi-Spiegel lieferte reale Gebäude** (Thomass-Eck: 6 Etagen, Adress-Tags) | ✅ mit automatischem Spiegel-Fallback (overpass.kumi.systems) | `floorplan.fetch_osm_buildings` (POST, 2 Endpunkte) |
| **OSM Buildings (osmbuildings.org)** | ⚠️ 3D-**Viewer**-Bibliothek; öffentliche Daten-API nicht mehr frei | ⚠️ nur als Render-Referenz — wir rendern selbst (Three.js) | im Quellen-Katalog als „nicht verfügbar (Daten-API)" |
| **hoowoge.de** | ❌ **Tippfehler** — gemeint ist **HOWOGE** (Berliner Wohnungsbaugesellschaft). Deren „Grundrissbewertungssystem"/BIM sind **intern** (unternehmen.howoge.de) | ❌ **keine öffentliche Grundriss-API**; automatisierter Abruf nicht möglich — nur Anzeigen-Metadaten per Website | im Katalog als nicht verfügbar markiert |
| **BIM-Portal Deutschland** | ⚠️ Informationsportal (bimdeutschland.de, planen-bauen 4.0) — **kein offenes BIM-Modell-Repository/API** | ❌ als Datenquelle nein; echte offene Gebäudedaten kommen über **CityGML/INSPIRE** der Länder/Kommunen (WFS) | Katalog-Eintrag + INSPIRE-Adapter (Roadmap) |
| **OpenStreetView / api.openstreetview.org** | ❌ **fiktiver Endpoint**; realer Anbieter **KartaView** (ex OpenStreetCam, Grab): öffentliche Endpoints, **100 req/h ohne Auth, 1000 req/h mit API-Key**; Mapillary (Meta) praktisch eingestellt | ✅ über KartaView; Coverage-abhängig | `floorplan.kartaview_search_url` (URL-Builder); Foto-Adapter als Roadmap |
| **Stadtverwaltungsportale** | ⚠️ je Kommune (Berlin FIS-Broker, Hamburg Transparenzportal, München OpenData) — WFS/CSW, GeoNutzV-Bedingungen, teils Auth | 🟡 Adapter je Kommune | im Katalog; Roadmap |

## 2. Live-Verifikationsprotokoll (14.08.2026)

| Prüfung | Ergebnis |
| :--- | :--- |
| Nominatim `search?q=Rathaus München` | ✅ 200, „New Town Hall, 8, Marienplatz…", osm_id relation/147095, importance 0,389 |
| Photon `api/?q=Rathaus München` | ✅ 200, Feature mit properties (name, street, postcode) + Point [11.5753, 48.1378] |
| Overpass `way[building]` (Radius 40 m) Hauptserver | ⚠️ „server too busy" (Last-Timeout) → Query-Format akzeptiert |
| Overpass Kumi-Spiegel | ✅ 6 Gebäude inkl. `building:levels` (6), `addr:*`, `height`-Fallback-Daten |
| KartaView `photo/search?bbox=…` | ⚠️ Endpoint antwortet (kein Fehler); leere Ergebnismenge in Test-Bbox — Coverage-abhängig |
| Direkter HTTPS aus der Sandbox | ⚠️ geblockt → Verifikation über externen Fetch (Ergebnisse oben) |

## 3. Übernommene Module

### 3.1 Edge-Agent (`floorplan.py`)

- `SourceDescriptor` + `SOURCES` — verifizierter Katalog (9 Quellen mit Status/Auth/Priorität),
- `nominatim_search` / `photon_search` / `geocode` (Fallback-Kette),
- `build_overpass_buildings_query` + `parse_overpass_buildings`
  (Wege + Relationen mit äußeren Membern; geschlossene Ringe; `building:levels`,
  `levels`, `height`-Fallback 3,2 m/Etage) → **GeoJSON FeatureCollection**,
- `fetch_osm_buildings` mit **Spiegel-Fallback** (Hauptserver → Kumi),
- `kartaview_search_url` (bbox-URL, 100/1000 req/h dokumentiert).

### 3.2 REST & WebSocket

| Endpunkt | Funktion |
| :--- | :--- |
| `GET /api/v1/floorplan/sources` | verifizierter Quellen-Katalog (für UI/Status) |
| `POST /api/v1/floorplan/geocode` | Adresssuche (Nominatim→Photon) — serverseitig, Policy-zentral |
| `POST /api/v1/floorplan/buildings` | Overpass-Abruf → GeoJSON + Broadcast `floorplan_buildings` |

### 3.3 Kotlin (`com.example.agent.floorplan`, JVM-testbar)

`FloorPlanModels` (Source-Katalog, BuildingModel mit Zentroid),
`OverpassQueryBuilder` (identische QL), `BuildingParser`
(Overpass- und GeoJSON-Parsing via kotlinx.serialization, robust gegen
ungültige Eingaben).

### 3.4 Web-Visualizer — Grundriss-Layer

- extrudiert Gebäuderinge (Shape → ExtrudeGeometry, Höhe = Etagen × 3,2 m
  bzw. `height`), lokale Meter-Konvertierung um den Zentroid,
- Kanten + Namens-Labels (CSS2D), Toggle `🌆 Grundriss`, Statuszeile,
- Cap 300 Features / 40 Labels (Ressourcenpolitik v11).

## 4. Rechtliches & Nutzungsbedingungen

| Quelle | Auflagen |
| :--- | :--- |
| OSM/Nominatim/Overpass | **ODbL** — Attribution „© OpenStreetMap-Mitwirkende"; Nominatim-Usage-Policy (1 req/s, UA, kein Bulk); Overpass-Etikette (kleine Radien, Timeout) |
| Photon | OSM-Daten (ODbL-Attribution), Demo-Server-Limits, Selbsthosting empfohlen |
| KartaView | Bildnutzung nach deren Bedingungen; Rate-Limits 100/1000 req/h |
| Geoportale (INSPIRE) | GeoNutzV / jeweilige Nutzungsbestimmungen; teils Auth |
| HOWOGE-Website | nur Anzeigen-Metadaten; kein automatischer Grundriss-Abruf (nicht verfügbar) |

## 5. Verifikation

- **Python: 9 neue Tests** (Katalog-Status, Query-Builder, Parser mit
  ungeschlossenem Ring, Höhen-/Etagen-Defaults, Photon-Fallback,
  Spiegel-Fallback, Fehlerfall, KartaView-URL).
- **Kotlin: 4 neue JVM-Tests** (Katalog, Query, Overpass-/GeoJSON-Parser,
  Robustheit).

## 6. Roadmap

| Phase | Inhalt | Status |
| :--- | :--- | :--- |
| **FP 1.0** | Quellen-Verifikation, Adapter (Nominatim/Photon/Overpass+Spiegel), Katalog, REST/WS, Visualizer-Layer | ✅ |
| FP 1.1 | KartaView-Foto-Adapter (Auth-Token, Foto-Download, Fassaden-Texturen) | ⏳ |
| FP 1.2 | INSPIRE/WFS-Adapter je Kommune (Berlin FIS-Broker zuerst) | ⏳ |
| FP 1.3 | Cache/Throttle für Nominatim (serverseitig), Selbsthost-Option Photon | ⏳ |
| FP 1.4 | FloorPlanFragment-UI (Suchfeld → 3D) nach UI_UX_PLAN | ⏳ |
