# 🛰️ Geolokalisierungs-Zugänge — Prüfbericht & korrigierte Referenzmatrix

**Status:** Prüfung abgeschlossen · **Stand:** 2026-08-14 · **Bezug:** 3dxAgent v4.4.0-ClientRules
**Zugehöriger Umsetzungsplan:** [`GEOLOCATION_CHANGE_PLAN.md`](GEOLOCATION_CHANGE_PLAN.md)

Dieses Dokument prüft die eingereichte Aufstellung externer Ortungsdienste, korrigiert
sachliche und lizenzrechtliche Fehler und bewertet jeden Dienst **spezifisch für die
3dxAgent-Plattform** (Honeywell CT45P, Indoor-Kartierung, BOS-/Industrieeinsatz,
Offline-Betrieb als Leitprinzip).

---

## 0. Executive Summary der Prüfung

| Ergebnis | Anzahl | Kommentar |
|----------|--------|-----------|
| ✅ Korrekt übernommen | 12 | Endpunkte, Technologiezuordnung und Genauigkeitsklassen überwiegend plausibel |
| ⚠️ Korrekturbedürftig | 9 | v. a. **Lizenz- und Kostenangaben** (WiGLE, MaxMind, ipstack, MLS, Google) |
| ❌ Sachlich falsch | 3 | MLS-Dumps, MaxMind-Lizenz, ipstack-Kontingent |
| ➕ Fehlend / Lücke | 8 | A-GNSS/SUPL, PSDS, NTRIP/RTK, Wi-Fi RTT (802.11mc), GNSS-Rohdaten, Mobility Database, microG-Backends, DB-IP |

**Kernbefund für die App:** Netzwerkbasierte Ortung liefert 10–1000 m. Die vorhandene
Sensorfusion (LiDAR/UWB/mmWave/BLE) liefert 0,01–0,5 m. **Netzwerkortung darf daher
niemals als Positionsmessung in den EKF einfließen** — sie ist ausschließlich für
*Georeferenzierung*, *Bootstrapping* und *Kontext* geeignet. Details siehe Abschnitt 6.

**Kritischster Compliance-Befund:** Die aktuelle App persistiert Positionen dauerhaft in
`spatial_memory` (SQLite, Retention 7 Tage / 100k Records). Mehrere der gelisteten
Dienste (Google, WiGLE) **verbieten genau das** vertraglich. Siehe Abschnitt 5.

---

## 1. Korrekturen zur eingereichten Liste

### 1.1 Kategorie 1 — Drahtlose & hybride Netzwerk-APIs

| Dienst | Angabe in der Vorlage | Prüfergebnis |
|--------|----------------------|--------------|
| **beaconDB** | „Public Domain", „primärer Nachfolger des MLS" | ⚠️ **Teilweise korrekt.** Endpunkt `https://api.beacondb.net/v1/geolocate` ✅, Submit `…/v2/geosubmit`, Ichnaea-Schema, **kein API-Key nötig** ✅. **Aber:** Die *Serversoftware* steht unter **AGPL-3.0**, nicht Public Domain. Public-Domain-Dumps sind bislang nur **angekündigt** („in the future, obfuscated data dumps will be published"), es gibt **noch keinen freien Datendump**. Der IP-Fallback nutzt **DB-IP Lite** → **Attributionspflicht gegenüber DB-IP**. Das Projekt bezeichnet sich selbst als **experimentell und nicht für kritische Dienste geeignet** — für BOS-Einsatzszenarien ein K.-o.-Kriterium als Primärquelle. Abdeckung in DACH lückenhaft. |
| **Positon** (positon.xyz) | „kostenlos mit API-Key, proprietäre Datenbasis" | ✅ **Korrekt.** Netzwerkbasiert, kommerzielle Backend-DB, kostenlos **für die Open-Source-Community** — kommerzielle/behördliche Nutzung ist damit **nicht automatisch abgedeckt**. Von GrapheneOS referenziert. Ichnaea-kompatibel. |
| **OpenCellID** | „CC BY-SA 4.0, Token benötigt" | ✅ **Korrekt**, mit Verschärfung: Betreiber ist **Unwired Labs**. **CC BY-SA 4.0 ist Share-Alike** — abgeleitete Datenbestände (z. B. eine im CT45P eingebettete gefilterte Zellen-DB) unterliegen derselben Lizenz und **müssen sichtbar „OpenCelliD" mit Link attribuieren**. Für ein proprietäres Behördenprodukt vorab juristisch zu klären; Ausnahmen erteilt Unwired Labs schriftlich/kommerziell. |
| **WiGLE.net** | „Kostenlos, Datenzugriff frei" | ❌ **Falsch — größte Korrektur der Liste.** WiGLE ist **proprietäre Freeware mit striktem EULA**: Nutzung **ausschließlich privat, wissenschaftlich oder edukativ, nicht-kommerziell**. **Jede Weiterverbreitung, Zwischenspeicherung zum Aufbau eigener Datenbestände und jede kommerzielle Nutzung ist untersagt** und erfordert einen separaten Lizenzvertrag. Query-Limits sind **gleitend und verhaltensabhängig** (kein fester Wert); Mehrfachaccounts führen zur Sperrung. → **Für 3dxAgent nur im Szenario „Forschung & Lehre" und nur mit Einzelfallfreigabe verwendbar.** |
| **Combain Location API** | „Free Tier vorhanden, Ichnaea-Schema" | ✅ **Korrekt.** Kostenloser API-Key, ~185 Mio. Cell-IDs, 195 Länder, Indoor inkl. Gebäude/Etage. **Besonders relevant: Combain bietet OnPrem- und Offline-Deployments** — das ist die einzige *kommerzielle* Option in der Liste, die mit dem Offline-Leitprinzip von 3dxAgent kompatibel ist. Ichnaea kennt das Schema `combain/v1`. |
| **Google Geolocation API** | „Pay-as-you-go, Free-Guthaben" | ⚠️ **Veraltet.** Das **$200-Monatsguthaben wurde zum 1. März 2025 abgeschafft**. Ersetzt durch **SKU-Freikontingente: Geolocation = Essentials = 10.000 Aufrufe/Monat frei, danach $5 / 1.000**. Billing-Account ist Pflicht. **Vertraglich kritisch:** Lat/Lng dürfen **maximal 30 aufeinanderfolgende Kalendertage** gecacht werden, danach Löschpflicht; „No Use With any Map" (außer lat/lng/place_id); Scraping/Warehousing untersagt. |
| **Unwired Labs (LocationAPI)** | „Free Tier mit API-Key, Ichnaea-kompatibel" | ✅ **Korrekt.** Endpunkt-Muster `https://<region>.unwiredlabs.com/v2/process.php`; Ichnaea kennt `unwiredlabs/v1` (Token wird als Anchor-Fragment an die URL gehängt). Betreibt auch OpenCellID. |
| **WifiDB** | „Kostenlos, Open Source" | ⚠️ **Nicht verifizierbar.** Projekt ist weitgehend inaktiv, keine belastbare öffentliche API-Dokumentation. **Empfehlung: aus der Zielarchitektur streichen.** |
| **Skyhook Wireless** | „Kommerziell" | ✅ Korrekt. Pionier der WLAN-Ortung, heute Qualcomm-Umfeld. Nur mit Vertrag; kein Free Tier. Für 3dxAgent nur als Enterprise-Option interessant. |
| **Mozilla Location Service (MLS)** | „Eingestellt, historische Dumps frei" | ❌ **Falsch im zweiten Teil.** Abschaltplan 2024: 13.03. keine neuen Keys → 27.03. keine Submits und **keine neuen Dumps** → **10.04.2024 wurden alle bisher veröffentlichten Dumps gelöscht** → 12.06. Deaktivierung aller Drittkeys → 31.07. Repo archiviert. **Es gibt also keinen offiziell freien Dump mehr**, nur inoffizielle Dritt-Mirrors mit ungeklärter Provenienz. Die **Software Ichnaea bleibt Apache-2.0** ✅ — genau das ist der verwertbare Teil (Schema + selbst hostbarer Server). |
| **Mylnikov** | „Defekt / Archiviert" | ✅ Korrekt. Nicht einplanen. |

### 1.2 Kategorie 2 — IP-Geolokalisation

| Dienst | Angabe | Prüfergebnis |
|--------|--------|--------------|
| **IP-API.com** | „45 Anfragen/Min, kein Key" | ⚠️ **Ergänzung nötig:** 45 req/min ✅, kein Key ✅, **aber HTTPS nur im Bezahltarif**. Android blockiert Klartext-HTTP seit API 28 per Default (`cleartextTrafficPermitted=false`) → **auf dem CT45P ohne Sicherheitsaufweichung unbrauchbar**. Free-Tier ist zudem **explizit nicht-kommerziell**. |
| **reallyfreegeoip** | „Geoclue-Standard-Fallback" | ⚠️ **Ungenau.** Geoclue nutzt je nach Distribution unterschiedliche Fallbacks; die aktuelle Referenzkonfiguration (Fedora, Guix) zeigt auf **beaconDB**. Für ein Android-Produkt ohnehin irrelevant. |
| **IPinfo Lite** | „unbegrenzt, kostenlos" | ✅ **Korrekt und bestätigt.** Unbegrenzte Requests, tägliche Updates, **kommerzielle Rechte inklusive**, IPv4+IPv6, Download als CSV/JSON/**MMDB**/Parquet. **Aber: nur Land + ASN**, keine Stadt. Für 3dxAgent damit nur Plausibilisierung/Länderkontext. |
| **MaxMind GeoLite2** | „Creative Commons (GeoLite2)" | ❌ **Falsch.** **Seit 30.12.2019 nicht mehr CC BY-SA**, sondern **proprietäre GeoLite2-EULA** mit Pflicht zu Account + Lizenzschlüssel, **Attributionspflicht** und **30-Tage-Aktualisierungspflicht** bei Weitergabe. **Zusätzlich seit 2025:** GeoLite2-**City** ist für bestimmte Länder (CN/HK/MO, CU, IR, KP, RU, VE) nicht mehr frei verfügbar. Redistribution der `.mmdb` im APK ist **lizenzrechtlich heikel**. Genauigkeit „68 % im 50-km-Radius" ist plausibel, aber herstellereigen. |
| **ipstack** | „Free Tier 10.000 Requests/Monat" | ❌ **Falsch.** Free Tier = **100 Requests/Monat**, **ohne HTTPS**. Für dieses Projekt irrelevant. |
| **IP2Location LITE** | „kostenlose LITE-DBs" | ✅ Korrekt, CC-BY-SA-4.0-ähnliche Attribution je nach Edition prüfen. Als lokale Offline-DB brauchbar. |
| ➕ **DB-IP Lite** | *fehlt* | **Ergänzen.** Self-hosted unbegrenzt oder 1.000 req/Tag API, Attributionspflicht. Wird bereits **indirekt über beaconDB** genutzt → Attribution ohnehin relevant. |

### 1.3 Kategorie 3 — Geocoding / Map-Data

| Dienst | Prüfergebnis |
|--------|--------------|
| **OSM Nominatim** | ✅ Korrekt, **aber die Usage Policy ist deutlich restriktiver als „Usage Policy beachten" suggeriert.** Verbindlich: **max. 1 Request/Sekunde absolut**, valider `User-Agent`/`Referer` (Standard-HTTP-Library-UA reicht **nicht**), sichtbare Attribution, ODbL-Share-Alike. **Verboten:** Autocomplete gegen die öffentliche API, systematische/Raster-Reverse-Abfragen, verteilte Skripte. **Periodische Requests aus Apps gelten als Bulk-Geocoding und sind ausdrücklich unerwünscht.** Apps müssen den Dienst **ohne Software-Update umschaltbar** halten und Ergebnisse **cachen**. Für ein Gerät, das 20 Hz Sensordaten produziert, heißt das: **Reverse-Geocoding nur ereignisgesteuert (Scanstart/Report-Export), nie im Loop.** Bei nennenswertem Volumen: **eigene Instanz betreiben** (in `docker-compose.yml` ergänzbar). Policy: <https://operations.osmfoundation.org/policies/nominatim/> |
| **Overpass API** | ✅ Korrekt. Ebenfalls harte Slot-/Timeout-Limits auf den öffentlichen Instanzen; für wiederkehrende Abfragen (z. B. „Gebäudegrundrisse im Umkreis" als Prior für die Mesh-Rekonstruktion) **eigene Instanz oder vorgezogener regionaler PBF-Import**. |
| **TransitFeed** | ❌ **Für dieses Projekt irrelevant und zudem abgekündigt.** TransitFeeds/OpenMobilityData wurde durch die **Mobility Database** (MobilityData) ersetzt; TransitFeeds-Deprecation **Dezember 2025**. ÖPNV-Fahrpläne haben keinen Bezug zu Indoor-3D-Kartierung → **streichen**. |
| **Photon** | ✅ Korrekt. **Software Apache-2.0, Daten ODbL.** Öffentliche Instanz `photon.komoot.io` wird bei hoher Last gedrosselt → **selbst hosten**. Einziger Kandidat, der Autocomplete rechtlich sauber erlaubt (Nominatim verbietet es). |
| **Pelias** | ✅ Korrekt, MIT, selbst hostbar, Datenquellen OSM/OpenAddresses/WhosOnFirst (jeweils eigene Lizenzen). Schwergewichtig (Elasticsearch) — für einen Edge-Agent auf Industriehardware **overkill**; Photon ist die passendere Wahl. |

### 1.4 Kategorie 4 & 5 — Offline und proprietäre Netze

| Punkt | Prüfergebnis |
|-------|--------------|
| **OLS (Offline Location Service)** | ✅ Konzeptionell korrekt — **und in 3dxAgent bereits zu 80 % vorhanden**: `offline/LocalApiServer.kt` (Port 8081) und `offline/LocalWebSocketServer.kt` sind exakt dieses Muster. Es fehlt nur der **NMEA-Ingest** und eine **Ichnaea-kompatible lokale `/v1/geolocate`-Route**. → siehe Änderungsplan Phase 3. |
| **Lokale MMDB-Instanzen** | ✅ Technisch korrekt (µs-Lookups). **Lizenzvorbehalt:** MaxMind-EULA erlaubt Einbettung ins APK nur unter Auflagen (30-Tage-Update, Attribution). **IPinfo Lite MMDB ist hier die lizenzfreundlichere Wahl** (kommerzielle Rechte inklusive). |
| **Apple Location Services** | ✅ Korrekt beschrieben. **Kein legaler Drittzugang** — die kursierenden „unauthenticated WPS endpoint"-Verfahren sind nicht ToS-konform und **dürfen nicht in dieses Produkt**. |
| **Google Fused Location Provider** | ✅ Korrekt. **Relevanz für CT45P:** GMS-Gerät, FLP verfügbar. **Aber:** FLP ist eine **Black Box ohne Kovarianzmatrix im EKF-Sinn** (nur `accuracy` als 68-%-Radius) und benötigt Play Services → in gehärteten/luftspaltgetrennten Deployments nicht nutzbar. Für 3dxAgent daher **optionaler Provider, nie Pflichtabhängigkeit**. |

### 1.5 Fehlende Kategorien (Lücken in der Vorlage)

Diese Verfahren fehlen und sind für **exakte Positionsbestimmung** relevanter als die Hälfte der gelisteten IP-Dienste:

| Verfahren | Genauigkeit | Warum relevant für 3dxAgent |
|-----------|-------------|------------------------------|
| **A-GNSS / SUPL** | verkürzt TTFF auf < 5 s | Ohne SUPL/PSDS braucht ein Kaltstart im Gebäudeumfeld 30–60 s. Direkt relevant für „Scan starten" im Außenbereich vor Gebäudebetreten. GrapheneOS-Proxy zeigt, dass ein **datenschutzfreundlicher SUPL-Proxy** machbar ist. |
| **PSDS (Predicted Satellite Data Service)** | – | Statische Ephemeriden-Downloads, **funktioniert danach offline** — passt exakt zum Offline-Leitprinzip. |
| **NTRIP / RTK-Korrekturdaten** (SAPOS, EUREF, RTK2go) | **1–3 cm** | **Die eigentliche Lücke.** Für „Architektur & Bestandsanalyse" (Szenario 3, IFC/BIM-Export) ist zentimetergenaue Georeferenzierung des lokalen Frames der entscheidende Mehrwert. Kein Netzwerk-WLAN-Dienst kommt in diese Größenordnung. |
| **Galileo HAS / SBAS (EGNOS)** | 0,2–2 m | Kostenlos, ohne Infrastruktur, europaweit. |
| **GNSS Raw Measurements API** (Android 7+) | Pseudoranges/Carrier-Phase | Ermöglicht eigene PPP/RTK-Lösung direkt auf dem CT45P — Rohdaten statt Black-Box-Fix, ideal für den EKF. |
| **Wi-Fi RTT / 802.11mc FTM** (`WifiRttManager`) | **1–2 m Indoor** | Echte Laufzeitmessung statt RSSI-Schätzung. Größenordnung besser als jede BSSID-Datenbank und **vollständig offline**. Erfordert FTM-fähige APs; Geräteunterstützung des CT45P ist zu verifizieren. |
| **Mobility Database** | – | Ersetzt TransitFeed (falls ÖPNV-Kontext je gebraucht wird). |
| **microG UnifiedNlp Backends** | variabel | Referenzarchitektur für austauschbare Location-Backends — als Vorbild für die geplante Provider-Abstraktion. |

---

## 2. Korrigierte Referenzmatrix (Kategorie 1, entscheidungsrelevant)

| Dienst | Endpunkt / Schema | Genauigkeit | Kosten (Stand 08/2026) | Lizenz / Rechtliches | Offline? | Verdikt für 3dxAgent |
|--------|-------------------|-------------|------------------------|----------------------|----------|----------------------|
| **beaconDB** | `api.beacondb.net/v1/geolocate` · Ichnaea | 10–100 m (wo Daten) | kostenlos, kein Key | Server AGPL-3.0; Dumps angekündigt; DB-IP-Attribution | ⏳ (Dumps folgen) | ✅ **Default-Online-Provider** (opt-in), nie alleinige Quelle |
| **Positon** | Ichnaea-kompatibel, Key | 10–50 m | kostenlos für OSS | proprietäre Basis; **Lizenz für Behörden/Kommerz klären** | ❌ | ⚠️ Nur Forschungs-/OSS-Builds |
| **Combain** | Ichnaea `combain/v1` | 10–50 m, Etage | Free Tier, dann kommerziell | proprietär | ✅ **OnPrem/Offline** | ✅ **Enterprise-Option Nr. 1** (einzige kommerzielle Offline-Variante) |
| **Unwired Labs** | `<region>.unwiredlabs.com/v2/process.php` | 10–100 m | Free Tier + Key | proprietär | ❌ | ✅ Backup-Online-Provider |
| **Google Geolocation** | `googleapis.com/geolocation/v1/geolocate` | 10–50 m | **10k/Monat frei, dann $5/1k** | **Cache max. 30 Tage, kein Warehousing** | ❌ | ⚠️ Nur mit Retention-Sonderregel — **kollidiert mit `spatial_memory`** |
| **OpenCellID** | CSV-Dump + API-Token | 100–1000 m | kostenlos | **CC BY-SA 4.0 (Share-Alike + Attribution)** | ✅ **Dump** | ✅ **Offline-Zellen-DB Nr. 1** |
| **WiGLE** | Search/Detail-API, Account | 10–50 m | gleitendes Tageslimit | **nicht-kommerziell, keine Redistribution, kein Caching-Aufbau** | ❌ | ❌ **Nicht produktiv einsetzen** (nur Szenario 5) |
| **Skyhook** | proprietär | 10–50 m | kostenpflichtig | proprietär | Vertrag | ➖ Nur bei Enterprise-Bedarf |
| **MLS** | *abgeschaltet* | – | – | Ichnaea **Apache-2.0** | – | ➖ **Nur das Schema übernehmen**, keine Daten |
| **WifiDB / Mylnikov** | – | – | – | – | – | ❌ Streichen |
| ➕ **Wi-Fi RTT (FTM)** | `WifiRttManager` (AOSP) | **1–2 m** | kostenlos | AOSP | ✅ **vollständig** | 🌟 **Höchste Priorität der Lücken** |
| ➕ **NTRIP/RTK** | RTCM3 über NTRIP | **1–3 cm** | SAPOS kostenpflichtig, RTK2go frei | je Caster | ⚠️ Link nötig | 🌟 **Höchster Nutzen für BIM-Szenario** |

---

## 3. Genauigkeits-Realitätscheck gegen die vorhandene Sensorik

```
UWB / LiDAR-SLAM (vorhanden) ▏ 0,01 – 0,10 m
Wi-Fi RTT (802.11mc)         ▏▏ 1 – 2 m           ← Lücke, offline, hoher Nutzen
NTRIP / RTK (outdoor)        ▏ 0,01 – 0,03 m      ← Lücke, für Georeferenzierung
GNSS + SBAS                  ▏▏▏ 0,2 – 3 m
BLE-Token-Trilateration (vh) ▏▏ 1 – 3 m
WLAN-BSSID-Triangulation     ▏▏▏▏▏▏ 10 – 100 m    ← die gesamte Kategorie 1
Mobilfunkzellen              ▏▏▏▏▏▏▏▏ 100 – 1000 m
IP-Geolokalisation           ▏▏▏▏▏▏▏▏▏▏ 5.000 – 50.000 m
```

**Schlussfolgerung:** Alle Dienste der Kategorien 1 und 2 sind **2 bis 6 Größenordnungen
ungenauer** als das, was 3dxAgent bereits misst. Ihr Wert liegt **nicht** in der Präzision,
sondern in **Verfügbarkeit ohne Sichtverbindung zum Himmel und ohne eigene Infrastruktur**.

---

## 4. Ableitung: Wofür 3dxAgent diese Dienste tatsächlich braucht

| # | Anwendungsfall | Anforderung | Passender Dienst-Tier |
|---|----------------|-------------|------------------------|
| **A** | **Geo-Anchor** — den lokalen metrischen Frame (`Transform3D` aus `ExactMapper`) mit WGS84 verknüpfen, damit Scans zwischen Einsätzen und Geräten global referenzierbar sind | einmalig pro Scan, 10–50 m reichen; RTK wenn BIM | Tier 1 (Netzwerk) → optional NTRIP |
| **B** | **Cold-Start-Bootstrap** — EKF-Initialposition, wenn kein GNSS-Fix (Tiefgarage, Bunker, Industriehalle) | 1× beim Scanstart, grob genügt | beaconDB / Combain / Offline-OpenCellID |
| **C** | **Grobvorausrichtung für ICP** — `icp_merger.py` / `ICPMerger.kt` konvergiert schlecht bei großen Initialversätzen zweier CT45P-Karten | 10–50 m Vorausrichtung senkt Iterationen deutlich | Tier 1, pro Gerät ein Fix |
| **D** | **Einsatzbericht / Export-Metadaten** — Adresse, Gebäude, Flurstück im GLTF-/IFC-Export und im Szenario-Protokoll | ereignisgesteuert, 1–5 Requests pro Scan | Nominatim (eigene Instanz) / Photon |
| **E** | **Geometrischer Prior** — OSM-Gebäudeumriss als Randbedingung für `EnvironmentReconstructor` | 1× pro Scan | Overpass (eigene Instanz) / vorab-PBF |
| **F** | **Plausibilisierung & Client-Trust** — im `ClientRegistry`/`ClientHealthEvaluator` prüfen, ob ein sich meldendes RELAY-Gerät geografisch überhaupt plausibel ist | Land/ASN reicht | IPinfo Lite (lokale MMDB) |
| **G** | **Indoor-Präzisionsgewinn** | 1–2 m, offline | **Wi-Fi RTT** — echter Fusionskandidat für den EKF |

> **Designregel:** Nur **G** (Wi-Fi RTT) und **RTK** sind gut genug, um als Messung in den
> EKF zu gehen. **A–F fließen nicht in den EKF**, sondern in eine separate, lose gekoppelte
> `GeoAnchor`-Struktur.

---

## 5. Compliance-Befunde (blockierend)

| # | Befund | Betroffen | Konsequenz |
|---|--------|-----------|------------|
| **C1** | **BSSIDs/MAC-Adressen sind personenbezogene Daten** (DSGVO Art. 4). Das Hochladen der WLAN-Umgebung eines Kunden- oder Einsatzobjekts an einen Drittanbieter-Cloud-Dienst ist eine Übermittlung an Dritte. | jeder Tier-1-Online-Provider | **Opt-in erforderlich**, Zweckbindung, AVV/DPA je Anbieter, Doku im Verarbeitungsverzeichnis. **Default muss `GEO_OFFLINE_ONLY=true` sein.** |
| **C2** | Google-Terms erlauben **max. 30 Tage Cache** für lat/lng. `database.py::spatial_memory` speichert Positionen mit `RETENTION_DAYS=7` ✅, aber `merged_maps` hat **keine Retention** ❌. | `edge-agent/database.py` | Retention auch für `merged_maps` + provenienzbasierte Löschung (`source='google'` → hartes 30-Tage-TTL). |
| **C3** | WiGLE-EULA verbietet Aufbau abgeleiteter Datenbestände und kommerzielle Nutzung. | jede WiGLE-Integration | **Nicht implementieren** außer hinter einem Research-Feature-Flag. |
| **C4** | OpenCellID **CC BY-SA 4.0 Share-Alike** — eine ins APK eingebettete, abgeleitete Zellen-DB muss attribuiert und ggf. share-alike verfügbar gemacht werden. | Offline-DB-Import | Attributions-Screen in der App + `docs/LICENSES.md` + Klärung vor kommerziellem Vertrieb. |
| **C5** | Nominatim: „periodische Requests aus Apps gelten als Bulk-Geocoding" + Umschaltbarkeit **ohne App-Update** gefordert. | Geocoding-Integration | Provider-URL **serverseitig konfigurierbar** (nicht hartkodiert), Ergebnis-Cache Pflicht, ereignisgesteuert statt periodisch. Bei Volumen: eigene Instanz in `docker-compose.yml`. |
| **C6** | MaxMind GeoLite2 EULA (Account, Attribution, 30-Tage-Update, Länderausschlüsse) — kein CC mehr. | lokale MMDB | **IPinfo Lite MMDB bevorzugen** (kommerzielle Rechte inklusive). |
| **C7** | IP-API.com und ipstack Free ohne HTTPS. | Android | Auf dem CT45P nicht einsetzbar, ohne `cleartextTrafficPermitted` zu öffnen → **Sicherheitsregression, verboten**. |
| **C8** | Android 9+ **Wi-Fi-Scan-Throttling**: 4 Scans / 2 min im Vordergrund. | `NetworkDataCollector` | Scan-Kadenz und Cache-Strategie müssen das berücksichtigen; kein 20-Hz-Polling. |

---

## 6. Empfohlene Zielarchitektur (Kurzfassung)

```
                    ┌──────────────────────────────────────────┐
                    │  GeoResolver (Kaskade, policy-gesteuert) │
                    └──────────────────────────────────────────┘
   Tier 0  OFFLINE   ─ Wi-Fi RTT (FTM) ─ GNSS/SBAS ─ lokale OpenCellID-DB ─ IPinfo-Lite-MMDB
   Tier 1  LOKALES NETZ ─ eigene Ichnaea-Instanz ─ eigene Nominatim/Photon-Instanz ─ NTRIP-Caster
   Tier 2  ONLINE (opt-in) ─ beaconDB ─ Combain ─ Unwired Labs ─ (Google, nur mit 30-Tage-TTL)
   Tier 3  KONTEXT      ─ Nominatim/Photon (Reverse) ─ Overpass (Prior) ─ IPinfo Lite (Plausibilität)

   Ergebnis → GeoFix{lat, lon, accuracy_m, source, license, ttl}
            → NICHT in den EKF, sondern in GeoAnchor (Erweiterung von Transform3D)
            → Ausnahme: Wi-Fi RTT & RTK gehen als echte Messung in den EKF
```

**Leitprinzipien**

1. **Offline first** — jede Online-Quelle ist optional und deaktivierbar; `GEO_OFFLINE_ONLY=true` ist Default.
2. **Ein Schema, viele Anbieter** — durchgängig das **Ichnaea-`/v1/geolocate`-Schema** (Apache-2.0). beaconDB, Combain, Unwired Labs und Google sprechen es bereits; damit ist der Anbieterwechsel eine Konfigurationsänderung, keine Codeänderung.
3. **Provenienz mitführen** — jeder Fix trägt `source` + `license` + `ttl`, damit Retention, Attribution und Export automatisch korrekt sind.
4. **Genauigkeit ehrlich propagieren** — `accuracy_m` → Kovarianz; ein 50-m-Fix darf einen 5-cm-LiDAR-Frame niemals überstimmen.
5. **Kein EULA-Bruch by default** — WiGLE/Google/Positon nur hinter expliziten Feature-Flags mit dokumentierter Freigabe.

---

## 7. Priorisierung (Nutzen vs. Aufwand)

| Rang | Maßnahme | Nutzen | Aufwand | Begründung |
|------|----------|--------|---------|------------|
| 1 | Provider-Abstraktion + `GeoFix`/`GeoAnchor` + Policy-Gate | 🟢🟢🟢 | M | Ohne sie ist jede weitere Integration technische Schuld |
| 2 | Echte Wi-Fi-/Cell-/BLE-Scan-Erfassung in `NetworkDataCollector` | 🟢🟢🟢 | M | Aktuell werden nur SSID/RSSI der *verbundenen* Zelle erfasst — für Triangulation unbrauchbar |
| 3 | Offline-Stack: OpenCellID-Dump + IPinfo-Lite-MMDB + lokale `/v1/geolocate`-Route | 🟢🟢🟢 | M | Erfüllt das Offline-Leitprinzip, keine Rechtsrisiken |
| 4 | **Wi-Fi RTT (802.11mc)** | 🟢🟢🟢 | S–M | 1–2 m offline; einziger Netzwerkdienst auf EKF-Niveau |
| 5 | beaconDB + Combain als Tier-2-Online-Provider | 🟢🟢 | S | Ichnaea-Schema, minimaler Zusatzaufwand nach Rang 1 |
| 6 | Reverse-Geocoding (eigene Photon/Nominatim-Instanz) für Reports | 🟢🟢 | S–M | Direkt sichtbarer Nutzen in Exporten |
| 7 | **NTRIP/RTK** für BIM-Szenario | 🟢🟢🟢 | L | Höchster Präzisionsgewinn, aber eigener Protokollstack |
| 8 | Overpass-Prior für Rekonstruktion | 🟢 | M | Nice-to-have |
| 9 | Google Geolocation | 🟡 | S | Bester Coverage, schlechtestes Lizenz-/Retentionsprofil |
| — | WiGLE, WifiDB, Mylnikov, TransitFeed, ipstack, IP-API | 🔴 | – | **Nicht einplanen** |

---

## 8. Quellen (Stand 14.08.2026)

- Ichnaea `/v1/geolocate`-Spezifikation — <https://ichnaea.readthedocs.io/en/latest/api/geolocate.html>
- MLS-Abschaltplan — <https://github.com/mozilla/ichnaea/issues/2065>
- beaconDB — <https://beacondb.net/> · Lizenz-/DB-IP-Diskussion: <https://codeberg.org/beacondb/beacondb/issues/5>
- beaconDB als Geoclue-Provider — <https://fedoramagazine.org/the-state-of-the-location-permission-on-fedora-linux-in-2025/>
- Positon — <https://positon.xyz/>
- OpenCelliD Lizenz/Attribution — <https://opencellid.org/downloads>
- WiGLE Query-Limits & EULA — <https://wigle.net/phpbb/viewtopic.php?t=2749>
- Combain Location API (OnPrem/Offline) — <https://combain.com/positioning-solutions/combain-location-api/>
- Google Geolocation Overview — <https://developers.google.com/maps/documentation/geolocation/overview>
- Google Maps Service Specific Terms (§6 Geolocation, 30-Tage-Cache) — <https://cloud.google.com/archive/terms/maps-platform/eea/maps-service-terms-20251001>
- MaxMind GeoLite2 Lizenzwechsel — <https://dev.maxmind.com/geoip/geolite2-free-geolocation-data>
- IPinfo Lite — <https://ipinfo.io/blog/ipinfo-lite-free-accurate-unlimited-ip-data>
- IP-API — <https://ip-api.com/>
- **Nominatim Usage Policy (verbindlich lesen)** — <https://operations.osmfoundation.org/policies/nominatim/>
- Photon — <https://github.com/komoot/photon>
- Mobility Database (ersetzt TransitFeeds) — <https://mobilitydatabase.org/faq>
- GrapheneOS Netzwerkortung / SUPL-Proxy — <https://grapheneos.org/features>
