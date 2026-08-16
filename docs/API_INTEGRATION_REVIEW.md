# 🔍 Prüfbericht — Konzept „Integration öffentlicher Tracking-APIs"

**Geprüft am:** 2026-08-14 · **Basis:** 3dxAgent v4.4.0-ClientRules, Commit `e503fb3`
**Verwandt:** [`GEOLOCATION_PROVIDERS.md`](GEOLOCATION_PROVIDERS.md) · [`GEOLOCATION_CHANGE_PLAN.md`](GEOLOCATION_CHANGE_PLAN.md)

---

## 0. Gesamturteil

Das Konzept ist **strukturell gut geschrieben und in der API-Auswahl überwiegend sachkundig** —
die Wahl von GTFS-RT, SIRI, GBFS, HFP, Entur und aisstream deckt die tatsächlich relevanten
Standards ab, und der Event-Driven-Ansatz ist die richtige Entscheidung.

Es hat aber **drei Fehlerklassen**, von denen die erste blockierend ist:

| Klasse | Befund | Schwere |
|---|---|---|
| **A — Blocker** | Das Konzept setzt eine **Georeferenzierung voraus, die es im Repo nicht gibt.** 3dxAgent arbeitet in einem lokalen metrischen Frame ohne jeden WGS84-Bezug. Ohne `GeoAnchor` gibt es keine Rechenvorschrift, um `lat/lon` in die Szene zu setzen. | 🔴 |
| **B — Phantom-Referenzen** | Das Konzept referenziert **11 Komponenten, die im Repo nicht existieren** (Mesa-ABM, `bim/`, IFC-Export, DVC, MLflow, Snakemake, InfluxDB, CSI, Gerät „A14", Google AI Studio, `web-visualizer/src/`). Es beschreibt Erweiterungen eines Systems, das so nicht gebaut ist. | 🟠 |
| **C — API-Detailfehler** | 6 sachliche Fehler in Limits/Protokollen, 2 nicht lauffähige Codebeispiele, 1 Modellierungsfehler (IFC), mehrere fehlende Lizenzschranken. | 🟡 |

Dazu ein **konzeptioneller Grundwiderspruch**: Das Konzept steht im Zielkonflikt mit dem
Offline-First-Prinzip der Plattform. Jede genannte API benötigt Internet. In Tiefgarage,
Tunnel, Bunker oder Katastrophenlage mit ausgefallener Infrastruktur — also **exakt den
Szenarien, für die 3dxAgent gebaut ist** — liefern alle elf Quellen exakt nichts.

**Empfehlung:** Kernidee behalten, Umfang auf **~25 %** reduzieren. Details in Abschnitt 6.

---

## 1. Blocker A — Die fehlende Koordinatenbrücke

Das Konzept springt in Abschnitt 3.2 direkt von „normalisierte Daten" zu „3D-Objekte werden
anhand der `hardware_id` erstellt". Der entscheidende Schritt fehlt vollständig:

```
GTFS-RT liefert:      lat 52.5200, lon 13.4050   (WGS84, Grad)
Die Szene erwartet:   x 3.4, y 0.0, z -7.1       (lokaler Frame, Meter)
                      ↑
                      Für diese Umrechnung existiert im Repo NICHTS.
```

Belege aus dem Bestand:

- `edge-agent/models.py` — `Transform3D`, `EkfState`: rein lokale Metrik, kein Geo-Feld.
- `edge-agent/pipeline.py` — `SensorDataPoint{timestamp, source, x, y, z, quality}`: dito.
- `web-visualizer/public/main.js` — `GridHelper(40, 20)`, Kamera `far: 500`: die Szene ist
  **40 m groß**.
- `grep -ri "gtfs\|siri\|gbfs\|opensky\|ais\|datex" .` → **0 Treffer** im gesamten Repo.

**Konsequenz:** Das Tracking-API-Konzept ist **nicht die nächste Ausbaustufe**, sondern eine
**Folgestufe** des Geolokalisierungs-Themas. Ohne `GeoAnchor` (WGS84 ↔ lokaler Frame,
inkl. Heading) und eine ENU-Projektion ist keine einzige der elf APIs darstellbar.

Fehlend und zwingend zu ergänzen:

```python
# WGS84 → ENU relativ zum GeoAnchor → lokaler Frame
def wgs84_to_local(lat, lon, alt, anchor: GeoAnchor) -> tuple[float, float, float]:
    e, n, u = geodetic_to_enu(lat, lon, alt, anchor.fix.lat, anchor.fix.lon, ...)
    return rotate_by_heading(e, n, u, anchor.heading_deg) + anchor.local_origin
```

**Zweiter, unterschätzter Aspekt: der Maßstabsbruch.**
Die Szene ist 40 m groß. Ein Bus 800 m entfernt liegt 20 Szenenbreiten außerhalb.
Ein Flugzeug in FL300 liegt 9.000 m über dem Grid. „Blaue Marker in die 3D-Szene einblenden"
funktioniert geometrisch schlicht nicht. Was gebraucht wird, ist eine **zweite
Darstellungsebene** (Mini-Map / Kontextring am Szenenrand mit Peilung+Distanz), nicht
dieselbe Szene. Das Konzept adressiert das an keiner Stelle.

---

## 2. Blocker B — Referenzierte, aber nicht existierende Komponenten

`grep` über das gesamte Repo (ohne `.git`):

| Im Konzept behauptet | Treffer | Realität |
|---|---|---|
| Mesa ABM („Evakuierungssimulation") | **0** | Existiert nicht. Auch kein anderes ABM. |
| `bim/`-Modul, IFC-Export | 0 Code | IFC ist in `README.md`/`docs/UX.md` als **Ziel** genannt, nicht implementiert. |
| DVC / MLflow / Snakemake | **0** | Keine Versionierungs-/Workflow-Infrastruktur. |
| InfluxDB | **0** | Persistenz ist **SQLite-WAL** (`database.py`). |
| Redis / NATS | **0** | Es gibt bereits **Mosquitto MQTT** in `docker-compose.yml`. |
| CSI-Personendetektion | **0** | Detektion läuft über mmWave/UWB/BLE, nicht Wi-Fi-CSI. |
| Gerät „A14" | **0** | Zielhardware ist **Honeywell CT45P**. |
| Google AI Studio / KI-Assistent | **0** | Keine LLM-Anbindung im Repo. |
| `web-visualizer/src/api/entityManager.js` | **0** | Es gibt kein `src/`. Struktur ist `public/main.js` (**189 Zeilen, ein File, keine Module**) + `server.js` (74 Z.). |
| TinyML | 1 | Nur ein **Kommentar** in `build.gradle.kts` („benötigt Modell-Asset"). |
| Parquet | 1 | Nur in meinem eigenen Geolocation-Dokument. |

**Bewertung:** Etwa die Hälfte der im Konzept beschriebenen „Erweiterungen" sind in Wahrheit
**Neuentwicklungen von Grund auf**. Die Aufwandsschätzung fehlt im Konzept ganz — realistisch
liegt sie bei einem Vielfachen dessen, was „Integrationsschicht" suggeriert.

**Konkretes Beispiel Szenario 2:** „Das ABM (Mesa) nutzt diese Daten als Input für
Agentenentscheidungen" — es gibt kein ABM. Die Aufgabe ist also nicht „GTFS-RT anbinden",
sondern „Multi-Agenten-Evakuierungssimulation bauen und *danach* GTFS-RT anbinden".

---

## 3. Fehlerklasse C — API-Fakten und Codebeispiele

### 3.1 Sachliche Fehler

| # | Behauptung | Korrektur |
|---|---|---|
| **C1** | OpenSky: „10 Anfragen/10 s für anonyme Nutzer, 400/10 s für registrierte" | ❌ **Falsch.** OpenSky nutzt seit 2025 ein **Credit-System pro Tag**, nicht Requests/10 s: Anonym **400 Credits/Tag**, Standard-User **4.000/Tag**, aktiver Feeder **8.000/Tag**, lizenziert 14.400/Stunde. Credits sind **flächenabhängig**: ≤25 sq° = 1, 25–100 = 2, 100–400 = 3, global = 4. Getrennte Buckets für `/states`, `/tracks`, `/flights`. Bei Erschöpfung **429** + `X-Rate-Limit-Retry-After-Seconds`. Historie nur **1 h** rückwärts. Auth ist inzwischen **OAuth2 Client-Credentials** (Token ~30 min), nicht mehr Basic Auth. |
| **C2** | OpenSky ohne Lizenzvorbehalt gelistet | ⚠️ **Fehlend:** OpenSky ist **nur für Forschung und nicht-kommerzielle Nutzung** frei; kommerzieller Einsatz erfordert eine separate Lizenz. Damit fällt es für Szenario 1 (BOS/Behörden, gewerbliches Produkt) in **dieselbe Kategorie wie WiGLE** — im Konzept aber als taktisches Kernelement geführt. Direkter Widerspruch. |
| **C3** | TfL: „`vehicleId` (oft obfuskiert)", keine Limits genannt | ⚠️ **Unvollständig und teils falsch.** Limits: **50 req/min anonym, 500 req/min mit App-Key** (api-portal.tfl.gov.uk). Wichtiger: Die Unified API bietet **keinen Live-Fahrzeugpositions-Feed** — nur Ankunftsprognosen (`/StopPoint/{id}/Arrivals`, `/Vehicle/{ids}/Arrivals`). „Tracking" ist dort nur indirekt über Prognosen möglich. Die `vehicleId` bei Bussen ist die Flotten-/Kennzeichen-Nummer, also gerade **nicht** obfuskiert. |
| **C4** | GBFS: `bike_id`/`vehicle_id` als „Hardware-ID" | ⚠️ **Widerspricht der Spezifikation.** GBFS schreibt seit v2.0 vor: `vehicle_id` **MUST NOT be persistent** zwischen Mietvorgängen (Datenschutz). Ein rotierender Zufallsstring ist per Definition **keine Hardware-ID**. Die Konzept-Tabelle in Abschnitt 2 führt ihn dennoch in der Spalte „Hardware-ID". Immerhin: Abschnitt 5.4 erkennt das Problem korrekt — die beiden Stellen widersprechen sich. Ebenfalls korrekt erkannt ✅: `vehicle_status.json` (v3.0-Umbenennung von `free_bike_status.json`). |
| **C5** | GTFS-RT: `VehicleDescriptor.id` als stabile Hardware-ID | ⚠️ Die Spezifikation bezeichnet das Feld ausdrücklich als **agenturintern**; Stabilität und Eindeutigkeit sind **nicht garantiert**. Viele Verkehrsunternehmen lassen es leer oder rotieren es. Ein Objekt-Pool, der allein darauf schlüsselt, erzeugt Karteileichen und Sprünge. |
| **C6** | DATEX II als „API" mit „Sensor-IDs" | ⚠️ DATEX II ist ein **CEN-Standard/Datenmodell**, kein Dienst. Jedes Land betreibt einen eigenen Knoten — in Deutschland der **Mobilitäts Daten Marktplatz (MDM)** der BASt, mit Registrierung und Nutzungsvereinbarung je Datengeber. Kein einheitlicher Endpunkt, keine einheitliche Lizenz. Aufwand deutlich höher als im Konzept angedeutet. |
| **C7** | Chicago Plow Tracker | ➖ Sachlich existent (ArcGIS REST), aber für ein deutsches BOS-/Industrieprodukt **ohne Nutzwert**. Demo-Charakter. Aus der Zielarchitektur streichen. |
| **C8** | aisstream.io ohne Vorbehalt | ✅ Endpunkt, `BoundingBoxes`, `FiltersShipMMSI` **korrekt**. ⚠️ Fehlend: Dienst ist **Beta ohne Verfügbarkeitszusage**, `BoundingBoxes` ist **Pflichtfeld**, max. **50 MMSI** je Filter, Subscription muss **binnen 3 s** nach Connect gesendet werden, bis **~300 msg/s** bei globalem Abo, **keine Browser-Verbindungen**. Für ein Lagebild mit Verlässlichkeitsanspruch relevant. |
| **C9** | Entur/HSL ohne Lizenzhinweis | ⚠️ Entur steht unter **NLOD** und verlangt zwingend den Header `ET-Client-Name` im Format `"firma-anwendung"`; nicht identifizierte Konsumenten werden gedrosselt oder blockiert. Im Codebeispiel steht `"3dxagent"` — **formatverletzend**, korrekt wäre z. B. `"88p3dkart-3dxagent"`. |

### 3.2 Die beiden Codebeispiele sind nicht lauffähig

**HSL-MQTT-Adapter (Abschnitt 5.1) — vier Fehler:**

```python
async with aiomqtt.Client("mqtt.hsl.fi", port=8883) as client:   # ❌ 1
    ...
    parts = message.topic.split("/")                              # ❌ 2, 3
    vehicle_id = parts[-1]                                        # ❌ 3
    "lat": payload["lat"],                                        # ❌ 4
```

1. **Port 8883 ist `mqtts` — TLS ist obligatorisch.** Ohne `tls_params=aiomqtt.TLSParameters()`
   scheitert der Handshake. (Klartext wäre 1883, die Digitransit-Doku rät ausdrücklich davon
   ab: „Prefer the port 8883 to respect the locational privacy of your users.")
2. In aiomqtt ist `message.topic` ein **`Topic`-Objekt**, kein `str` → `.split()` wirft
   `AttributeError`. Korrekt: `message.topic.value.split("/")`.
3. **`parts[-1]` ist nicht die `vehicle_id`.** Ein reales HFP-Topic endet mit
   Geohash-Segmenten:
   ```
   /hfp/v2/journey/ongoing/vp/bus/0055/01216/1069/1/Malmi/07:20/1130106/2/60;24/19/73/44
                                 └op─┘└veh─┘                                └── Geohash ──┘
   ```
   `parts[-1]` liefert also `"44"`. Das Fahrzeug ist eindeutig erst als Paar
   *(operator, vehicle)* = `parts[6], parts[7]` — oder schlicht aus dem Payload:
   `payload["VP"]["oper"]` / `payload["VP"]["veh"]`.
4. Das Payload ist unter dem Schlüssel **`"VP"`** verschachtelt, nicht flach —
   `payload["lat"]` wirft `KeyError`. Und: `lat`/`long` sind **regelmäßig `null`**
   (z. B. bei `"loc":"ODO"`, Odometer-Fallback ohne GNSS-Fix). Ohne Null-Prüfung
   landen `None`-Koordinaten in der Szene. Realer Beispieldatensatz:
   ```json
   {"VP":{"desi":"40","oper":22,"veh":1281,"spd":null,"lat":null,"long":null,"loc":"ODO",...}}
   ```

**Entur-WebSocket-Adapter (Abschnitt 5.1) — zwei Fehler:**

```python
async with websockets.connect(uri, extra_headers={...}) as ws:      # ❌ 1
    await ws.send(json.dumps({"type": "start", "id": "1", ...}))    # ❌ 2
```

1. `extra_headers` wurde in `websockets` ≥ 14 zu **`additional_headers`** umbenannt.
2. **Falsches Subprotokoll.** `{"type": "start"}` gehört zum **abgekündigten**
   `subscriptions-transport-ws`. Entur nutzt laut eigener Doku **`graphql-ws`**, dessen
   Handshake lautet:
   ```
   → {"type":"connection_init","payload":{...}}
   ← {"type":"connection_ack"}
   → {"type":"subscribe","id":"1","payload":{"query":"subscription {...}"}}
   ← {"type":"next","id":"1","payload":{"data":{...}}}
   ```
   Das Subprotokoll muss zusätzlich beim Connect ausgehandelt werden
   (`subprotocols=["graphql-transport-ws"]`). Das Beispiel würde die Verbindung
   aufbauen, aber **nie Daten empfangen**.

Zudem fehlt in der Query der Pflichtfilter — `vehicles` ohne `codespaceId` streamt
**ganz Norwegen**, was dem Konzept-Anspruch „geografisch gefiltert" widerspricht.

### 3.3 Modellierungsfehler (Szenario 3)

> „Haltestellen, Linienverläufe und Gleisgeometrien können als IFC-Elemente
> (`IfcTransportElement`) übernommen werden."

❌ **`IfcTransportElement` ist falsch.** Die Entität modelliert *gebäudetechnische*
Transportanlagen: Aufzüge, Rolltreppen, Fahrsteige. Für Verkehrsinfrastruktur ist
**IFC 4.3** (seit 2023 ISO 16739-1) zuständig, mit `IfcRailway`, `IfcRoad`,
`IfcAlignment`, `IfcTransportationDevice`. Für Haltestellen käme eher `IfcSpatialZone`
oder `IfcBuiltElement` in Frage. `IfcSensor` ✅ ist korrekt (IFC4).

**Wichtiger als die Entitätswahl ist aber ein Lizenzproblem, das das Konzept nicht sieht:**
GTFS-Statikdaten und OSM-Geometrien stehen unter **ODbL bzw. feed-spezifischen Lizenzen**.
Werden sie als Geometrie in ein IFC-Modell übernommen, das an einen Auftraggeber
ausgeliefert wird, entsteht potenziell ein **abgeleitetes Werk mit Share-Alike-Pflicht**.
Für ein Architekturbüro, das ein Bestandsmodell als Werkleistung abliefert, ist das ein
reales Vertragsrisiko. → Externe Geometrie strikt als **separater, gekennzeichneter Layer**
mit Lizenzvermerk, niemals in den Bestandsdatensatz eingerechnet.

---

## 4. Widersprüche zum bestehenden Code

| # | Konzept | Repo-Realität |
|---|---|---|
| **W1** | „Statische Umgebung bleibt **grau**, interne Sensorik **grün**" | `main.js:44` — die **Punktwolke ist grün** (`0x00ff88`), es gibt kein Grau. `AVATAR_COLORS` enthält bereits **Blau** (`0x33aaff`) *und* Grün (`0x33ff33`) für interne Avatare. Die vorgeschlagene Codierung kollidiert also an **zwei** Stellen mit dem Bestand. Ein Farbkonzept muss zuerst den Bestand ändern. |
| **W2** | „`web-visualizer` erhält Modul `src/api/entityManager.js`" | Kein `src/`. `public/main.js` = **189 Zeilen ohne Modulstruktur**, ein globales `scene`, `avatars` als flaches Array mit fest 5 Elementen, `updateAvatars()` mappt stur über den Index. Ein `ApiEntityManager` erfordert vorher ein **Refactoring auf Module + ID-basierte Objektverwaltung**. |
| **W3** | „WebSocket `/ws/api/entities`" | `server.js` proxied genau **einen** Pfad `/ws` zum Edge-Agent. Ein zweiter WS-Pfad erfordert Änderungen in `server.js` **und** `nginx/nginx.conf`. |
| **W4** | „Level-of-Detail-System für mehrere hundert Objekte" | `updateLOD()` verändert aktuell nur die **Punktgröße** (`material.size`) anhand der Kameradistanz. Ein echtes LOD-/Instancing-System existiert nicht. |
| **W5** | „Event-Bus (Redis Pub/Sub oder NATS)" | **Mosquitto MQTT läuft bereits** (`docker-compose.yml`, Ports 1883/9001) und `edge-agent/mqtt_bridge.py` ist implementiert. Redis/NATS wäre ein **dritter** Message-Layer neben MQTT und WebSocket. Unnötig. |
| **W6** | „InfluxDB als Zeitreihen-DB" | Persistenz ist **SQLite-WAL** mit Retention (7 Tage / 100k Records). Eine zweite DB-Engine für ein Edge-Gerät ist schwer begründbar; SQLite oder direkt Parquet-Dateien reichen. |
| **W7** | „läuft als Teil des `edge-agent` (Python) **oder** als eigenständiger Microservice" | Diese Entscheidung wird offengelassen — sie ist aber die wichtigste des ganzen Dokuments (Deployment, Ressourcen auf dem CT45P, Fehlerdomänen). Muss vor Umsetzung fallen. |

---

## 5. Fehlende Aspekte

Das Konzept behandelt Datenbeschaffung ausführlich, aber die schwierigeren Teile gar nicht:

| Lücke | Warum kritisch |
|---|---|
| **Koordinatentransformation** | Siehe Blocker A. Der eigentliche Kern der Aufgabe. |
| **Zweckbindung der Feeds** | Fast alle Feeds sind für **Fahrgastinformation** lizenziert. Nutzung in einem polizeilich/behördlich-taktischen Lagebild ist eine **Zweckänderung**, die die Lizenzen nicht abdecken. Das ist der gravierendste rechtliche Punkt — und im Konzept nicht erwähnt. |
| **Keine SLA / Datenqualität** | Keiner der elf Dienste garantiert Verfügbarkeit (aisstream explizit „Beta"). Ein taktisches Lagebild, das schweigende Feeds als „keine Fahrzeuge" darstellt, ist **gefährlicher als gar keine Anzeige**. Nötig: Staleness-Anzeige, Ausfall-Kennzeichnung, Alterungs-Fade. |
| **Latenz- und Uhren-Drift** | GTFS-RT wird typ. alle 15–30 s aktualisiert; ein Bus bei 50 km/h ist dann bis zu **400 m versetzt**. Ohne Dead-Reckoning und sichtbaren Unsicherheitsradius ist die Darstellung irreführend präzise. |
| **Deduplizierung** | Dasselbe Fahrzeug erscheint oft in GTFS-RT **und** SIRI **und** einer regionalen API. Das Konzept normalisiert Formate, aber führt keine Entitäts-Auflösung. |
| **Integration in die Qualitätsformel** | `docs/CLIENT_RULES.md` definiert `Q_total = 0.4·Q_snr + 0.3·Q_conf + 0.2·Q_latency + 0.1·Q_dup` mit Verwerfen bei `Q < 0.5`. Externe Entitäten müssten dort eingehängt werden — das Konzept ignoriert das bestehende Regelwerk vollständig. Bemerkenswert: `Q_latency` und `Q_dup` adressieren exakt die beiden vorstehenden Lücken. Das Rad ist im Repo schon erfunden. |
| **Ressourcenbudget CT45P** | Ein Handheld mit LiDAR-Verarbeitung bei 20 Hz. Persistente MQTT-/WS-Verbindungen plus 300 msg/s AIS sind ein Akku- und Thermalproblem (`THERMAL_CRITICAL_C=75`). Die Streams gehören auf den Edge-Agent, nie aufs Gerät. |
| **Rückkanal-Risiko** | Abschnitt 5.4 sagt korrekt, dass interne Daten nicht geteilt werden ✅. Nicht erwähnt: eine **Bounding-Box-Anfrage verrät den Einsatzort** an den Betreiber. Bei einem verdeckten Einsatz ist das eine Aufklärungslücke. Mitigation: großzügige Box oder Vorab-Cache. |

---

## 6. Gegenvorschlag — reduzierter Zuschnitt

Was tatsächlich Nutzen bringt, ist eine **vierstufige Reihenfolge**, nicht ein
Elf-API-Gateway auf einmal:

### Stufe 0 — Voraussetzung (erst das, sonst nichts)
`GeoAnchor` + WGS84↔ENU-Transformation aus dem Geolocation-Änderungsplan.
**Ohne diese Stufe ist alles Weitere nicht implementierbar.**

### Stufe 1 — Ein Adapter, generisch, offline-tauglich
**Nur GTFS-Realtime.** Begründung: offener Standard (Protobuf), keine Anbieterbindung,
in DE flächendeckend (DELFI/MobiData BW), rein HTTP-Polling — kein zusätzlicher
Verbindungsstack. Deckt Szenario 2 und 4 ab. Zwei Dateien statt eines Moduls:

```
edge-agent/external/
├── base.py        # ExternalEntitySource-Interface, analog zu geo/base.py
└── gtfs_rt.py     # Poller + Protobuf-Parser + Normalisierung
```

Wiederverwendung statt Neubau: Publikation über den **vorhandenen** MQTT-Broker
(`mqtt_bridge.py`), Topic `3dxagent/external/entities`. Kein Redis, kein NATS,
kein InfluxDB, kein `api-gateway/`-Microservice.

### Stufe 2 — Darstellung als eigene Ebene
Nicht „blaue Marker in die Szene", sondern ein **Kontextring/Mini-Map** mit Peilung
und Distanz — löst den Maßstabsbruch aus Blocker A. Voraussetzung: Refactoring von
`main.js` auf Module und ID-basierte Objektverwaltung (W2).

### Stufe 3 — Optionale weitere Quellen, je nach Bedarf
GBFS (statisches JSON, trivial) → aisstream (nur bei maritimer Relevanz) →
OpenSky (**nur wenn nicht-kommerzieller Einsatz rechtlich zutrifft**) →
DATEX II/MDM (hoher Vertragsaufwand, zuletzt).

### Streichliste
| Element | Grund |
|---|---|
| Chicago Plow Tracker | Kein Nutzwert außerhalb Chicagos |
| TfL Unified API | Kein Live-Positions-Feed; nur London |
| 511.org | Nur Bay Area; SIRI ist über den generischen Adapter abgedeckt |
| InfluxDB | SQLite/Parquet genügt |
| Redis / NATS | MQTT ist vorhanden |
| Eigenständiges `api-gateway/` | Als Paket im Edge-Agent ausreichend |
| DVC / MLflow / Snakemake | Nicht vorhanden; separates Thema |
| IFC-Anreicherung (Szenario 3) | Lizenzrisiko > Nutzen; zurückstellen |

---

## 7. Bewertung je Szenario

| Szenario | Konzept-Anspruch | Realistische Bewertung |
|---|---|---|
| **1 — Taktisch** | Flugzeuge, Schiffe, ÖPNV als taktische Ebene | 🔴 **Schwächster Teil.** Flugzeuge in FL300 sind für eine Gebäudelage irrelevantes Rauschen; OpenSky ist zudem nicht-kommerziell lizenziert. Nur der ÖPNV-Anteil (Sperrung/Fluchtweg) trägt — und der auch nur mit Staleness-Anzeige. |
| **2 — Evakuierung** | GTFS-RT + GBFS + DATEX II speisen das ABM | 🟡 **Plausibel, aber Voraussetzung fehlt** (kein ABM im Repo). Zudem: `occupancy_status` in GTFS-RT ist **optional** und in DE-Feeds selten befüllt — die „Kapazitätsmodellierung" hat oft keine Datengrundlage. |
| **3 — Architektur/BIM** | GTFS-Statik + OSM als IFC-Referenz | 🔴 **Nicht empfohlen.** Falsche IFC-Entität, ODbL-Share-Alike-Risiko im Kundendeliverable, kein IFC-Export im Repo. Für dieses Szenario ist **NTRIP/RTK** (1–3 cm Georeferenzierung) um Größenordnungen wertvoller. |
| **4 — Temporäre Szenarien** | Fusion BLE-Token + externe Fahrzeuge | 🟢 **Stärkster Anwendungsfall.** Shuttle-/Einsatzfahrzeug-Tracking bei Großveranstaltungen ist echter Mehrwert und maßstäblich passend (Stadtteilebene). Hier zuerst umsetzen. |
| **5 — Forschung** | Standardisierte, versionierbare Datensätze | 🟢 **Konzeptionell richtig**, technisch am einfachsten (Aufzeichnung nach Parquet). Aber DVC/Snakemake/MLflow existieren nicht — als eigenes Vorhaben planen. Zudem: mehrere Feeds erlauben **Redistribution der Rohdaten nicht** — Datensätze sind also nicht ohne Weiteres publizierbar. |

---

## 8. Fazit

Die Grundidee — externe dynamische Entitäten in das 3D-Lagebild einzublenden — ist **richtig
und für Szenario 4 klar wertstiftend**. Die API-Auswahl zeigt Sachkenntnis, und einzelne
Details (GBFS-`vehicle_status`, ID-Rotation, aisstream-Filternamen, Entur-Endpunkt) sind
präzise recherchiert.

Das Konzept ist in der vorliegenden Form aber **nicht umsetzbar**, weil es die
Koordinatenbrücke voraussetzt statt sie zu bauen, und weil es auf einem Systembild
aufsetzt, das mit dem Repo nur teilweise übereinstimmt.

**Drei Sätze zum Mitnehmen:**

1. **Erst `GeoAnchor`, dann Tracking-APIs** — ohne WGS84↔lokal-Transformation ist keine
   einzige Zeile dieses Konzepts implementierbar.
2. **Ein Adapter (GTFS-RT) auf vorhandener Infrastruktur (MQTT/SQLite)** schlägt elf
   Adapter auf drei neuen Infrastrukturkomponenten.
3. **Die Lizenz-Zweckbindung ist der eigentliche Showstopper** — nicht die Technik.
   Fahrgastinformations-Feeds in einem behördlich-taktischen Produkt sind rechtlich
   zu klären, bevor Code entsteht.

---

## 9. Quellen (Stand 14.08.2026)

- OpenSky REST API, Credits & Limits — <https://openskynetwork.github.io/opensky-api/rest.html>
- Digitransit HFP (MQTT-Endpunkte, Topic-Struktur) — <https://digitransit.fi/en/developers/apis/5-realtime-api/vehicle-positions/high-frequency-positioning/>
- Entur Vehicle Positions (graphql-ws, ET-Client-Name, NLOD) — <https://developer.entur.org/pages-real-time-vehicle/>
- GBFS-Spezifikation, `vehicle_status.json` & ID-Rotation — <https://gbfs.mobilitydata.org/specification/reference/>
- GBFS v3.0 Upgrade-Guide (Lizenzpflicht, ID-Rotation) — <https://mobilitydata.org/how-to-upgrade-to-gbfs-v3-0/>
- aisstream.io API-Referenz — <https://aisstream.io/documentation>
- TfL Unified API Products & FAQ (50/500 req/min) — <https://api-portal.tfl.gov.uk/products>
- IFC 4.3 / ISO 16739-1 (Infrastruktur-Entitäten) — <https://standards.buildingsmart.org/IFC/RELEASE/IFC4_3/>
