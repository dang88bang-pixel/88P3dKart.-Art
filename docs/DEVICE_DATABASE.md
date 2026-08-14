# 📡 Offline-Gerätedatenbank für Drahtlostechnologien — Machbarkeitsprüfung & Integration

> **Version:** v1.1 · **Datum:** 15. August 2026 ·
> **Eingabe:** „3dxAgent – Umfassende Offline-Gerätedatenbank für
> Drahtlostechnologien (EU)" (v16.0.0-DeviceDatabase) +
> „Erweiterte Gerätedatenbank (Weitere Kategorien)" (v17.x)
>
> Prüfung der Quellen (mit Live-Verifikation), Fehlerkatalog des
> Spec-Codes und Integration des **testbaren Kerns**: OUI-Lookup,
> GATT-Standard-Services, Tracker-Profile, Company-ID-Registry,
> DeviceDatabase-Kern (Python + Kotlin identisch) mit den erweiterten
> Kategorien Thread/Matter, LoRaWAN, Wireless M-Bus, ISM 433 und
> Medizin-BLE, Konsolidierungs-Builder und REST-Endpunkte.

---

## 1. Quellen-Verifikation (15.08.2026)

### 1.1 v16-Quellen (Gerätedatenbank-Kern)

| Quelle (Spec) | Verifiziert? | Anmerkung |
| :--- | :--- | :--- |
| Zigbee2MQTT-Geräteliste | ✅ **live verifiziert** — aktuell **5.592 Geräte / 582 Hersteller** (Spec „5.521/583" ≈ korrekt, Stand veraltet) | docgen-JSON auf GitHub (Koenkk/zigbee2mqtt.io) |
| blakadder/zigbee (ZHA/Templates) | ✅ existiert (Spec „2.693 Geräte" nicht nachgemessen) | Templates je Hersteller |
| Nordic Bluetooth Numbers DB | ✅ (NordicSemiconductor/bluetooth-numbers-database) | 16-Bit-Service-UUIDs **und Company-IDs** (JSON) |
| Bluetooth SIG Assigned Numbers | ✅ offiziell | Company-IDs + 16-Bit-UUID-Zuweisungen |
| IEEE-OUI / mac-address-vendor-database | ✅ (~50k Einträge, wöchentlich) | MA-L/MA-M/MA-S-Präfixe |
| Community-Repos (m365py, Navee/ZIMO-Hacks) | ⚠️ Reverse-Engineering | Frames **nicht zertifiziert** — nur mit `verified=false` + Referenz |
| FiRa/Qorvo/NXP-Doku (UWB) | ✅ Hersteller-Doku | Chips/Module (DWM3000, SR150/250) |

### 1.2 v17-Quellen (erweiterte Kategorien)

| Quelle (Spec) | Spec-Angabe | Verifiziert? |
| :--- | :--- | :--- |
| Bluetooth SIG Company IDs | „600+ Hersteller" | ✅ **live verifiziert** (Nordic `company_ids.json`, master): mehrere Tausend Einträge, Codes 0x0000–0x10F4 + 0xFFFF-Reserved — „600+" ist eine **Untergrenze**; die 18 gelisteten IDs einzeln geprüft (s. §2) |
| Thread-zertifizierte Geräte | „1.000+ Geräte", „Thread 14, September 2024" | ✅ **live verifiziert**: Thread Group meldete **November 2025** den Meilenstein **1.000+ zertifizierte Produkte/Komponenten** (Verzehnfachung in 2 Jahren, 230+ Mitglieder); „Thread 14" ist **Thread 1.4**, veröffentlicht **September 2024**; seit 01.01.2026 nur noch 1.4-Zertifizierung für Border-Router. „52 % der Thread-Geräte unterstützen Matter" ⚠️ **nicht verifizierbar** → nicht übernommen |
| Matter-zertifizierte Geräte | „300+ Geräte" | ✅ **live verifiziert** (CSA): **>1.000 zertifizierte Geräte** („300+" deutlich veraltet); MatterCatalog zählt 750+ verifizierte Produkte (Okt 2025); Quelle `matter-smarthome.de` existiert ✅ |
| LoRaWAN Device Repository (TTN) | „1.099 Geräte / 146 Hersteller" | ✅ **live verifiziert** (offizielle Seite): **1.104 Geräte / 149 Hersteller** (Spec veraltet) |
| Wireless M-Bus (OMS, 868 MHz) | „50+ Hersteller" | ✅ Technologie bestätigt (OMS/EN 13757, 868 MHz; auch 169/434 MHz); ZENNER **EDC B.One**-Modul live verifiziert; „50+ Hersteller" als Größenordnung plausibel, nicht nachgemessen |
| ISM 433 MHz (SRD 433,05–434,79 MHz) | Frequenzangaben | ✅ (ERC/ETSI-Regularien, Region 1; 915 MHz US-ISM) |
| Medizinische BLE-Geräte | Dexcom/Abbott/Oura/WHOOP/BioBeat/Empatica/Phonak/Signia/ReSound | ✅ Herstellerdoku; **Company-IDs live verifiziert**: Dexcom `0x00D0`, Abbott `0x03BB`; Oura-Company-ID der Spec (`0xFDB0`) existiert **nicht** |

## 2. Fehlerkatalog (Spec-Code, korrigiert)

### 2.1 v16

| # | Spec-Angabe | Befund | Korrektur |
| :--- | :--- | :--- | :--- |
| 1 | Tile-UUIDs `0xFEAA`, `0xFED5` | **`0xFEAA` ist Eddystone (Google)**; Tile sind laut Bluetooth SIG `0xFEEC`/`0xFEED` zugewiesen | Tracker-Profil korrigiert; Hinweis im Modell |
| 2 | Tile-Company-ID `0x0055` | nicht bestätigt | `company_id = null` (unverifiziert), Erkennung über die UUIDs |
| 3 | Xiaomi M365 `light_on`/`light_off` identische Frames | Copy-Paste-Fehler | Frame-Kommandos generell nur als `verified=false` + Referenz; prozedurale Resets als `verified=true` |
| 4 | „Galaxy S21–S24" / „Pixel 6 Pro–9" mit UWB | nur **+ / Ultra / Pro**-Modelle haben UWB (Basismodelle nicht) | als Ungenauigkeit dokumentiert |
| 5 | „Alle in der EU zugelassenen Geräte offline" | nicht erreichbar — Quellen ändern sich täglich (OUI), Zigbee-Liste wächst | realistische Zielsetzung: **konsolidierte Snapshot-DB** + Builder für Updates |
| 6 | DB-Größen (~50 MB JSON) | Schätzwerte ohne Messung | als Richtwerte gekennzeichnet |
| 7 | Service Worker mit `cdn.3dxagent.com/db/*` | fiktive URL + SW-Missverständnis (s. docs/SERVICE_WORKER.md) | DB liegt als Asset/`data/`-Datei; Updates via Builder/App-Download |

### 2.2 v17 (Company-IDs — alle gegen Nordic/SIG `company_ids.json` geprüft)

| # | Spec-Angabe | Befund | Korrektur |
| :--- | :--- | :--- | :--- |
| 8 | `0x0000` = „Ericsson Technology Licensing" | Eintrag heißt **„Ericsson AB"** | korrigiert |
| 9 | `0x017A` = „Telemontior, Inc." | Tippfehler — Eintrag heißt **„Telemonitor, Inc."** | korrigiert |
| 10 | **Xiaomi = `0xFDAB`** | `0xFDAB` existiert nicht — Xiaomi ist **`0x038F`** | korrigiert |
| 11 | **HP = `0xFDB4`** | `0xFDB4` existiert nicht — HP ist **`0x0065`** | korrigiert |
| 12 | Oura = `0xFDB0`, ECSG = `0xFDB5` | **beide existieren nicht** (Liste endet bei `0x10F4`, danach nur `0xFFFF`-Reserved) — Verwechslung von Company-IDs mit 16-Bit-Service-UUIDs | nicht übernommen; Oura-Record ohne Company-ID |
| 13 | `0x0093` Universal Electronics, `0x00C4` LG, `0x017B` taskit, `0x017E` BluDotz, `0x03D5` Wyzelink, `0x0520` Target, `0x0544` OrthoSensor, `0x0568` Bodyport, `0x0739` Jiangsu Qinheng, `0x0A53` KKM, `0x0A54` SQL | alle **bestätigt** ✅ | übernommen |

### 2.3 v17 (Kategorien-Zahlen & Produkte)

| # | Spec-Angabe | Befund | Korrektur |
| :--- | :--- | :--- | :--- |
| 14 | „7 neue IKEA-Matter-Produkte (2025)" | Es sind **5 Sensoren** (MYGGSPRAY, MYGGBETT, KLIPPBOK, TIMMERFLOTTE, ALPSTUGA) im Rahmen von **21** neuen Smart-Home-Produkten; Ankündigung **Nov 2025**, Marktstart **Jan 2026** („7" falsch; „Ersetzt Vallhorn/Badring/Parasoll" plausibel, nicht explizit bestätigt) | 5 Sensoren übernommen, Nachfolger-Hinweise als Plausibilität |
| 15 | Matter „300+ zertifiziert" | CSA: **>1.000 zertifiziert** | dokumentiert |
| 16 | TTN „1.099 Geräte / 146 Hersteller" | **1.104 / 149** | dokumentiert |
| 17 | Minew `LSG01/LSD01/LTB01-G`, Netvox `R718N37`, WEPTECH `Myna/Munia`, Solvimus `MBUS-GEWB/GE5B`, ZENNER „IoT Gateway Outdoor 16", Shelly „Plug S MTR Gen3" | Modellnamen nicht einzeln verifizierbar (kein JSON-Index im TTN-Repo; keine Produktdoku gefunden) | Records mit `verified=false` + Herkunft „Spec-Angabe"; verifizierte Pendants (ZENNER EDC B.One, Zähler, WILSEN.node, Elsist-EM300-Serie, Dragino/RAK/MultiTech) als `verified=true` |

## 3. Übernommene Module

### 3.1 Python (`edge-agent/device_db.py`)

- **GATT-Standard-Services** (8 Services, 0x1800–0x183A, mit Characteristics — Bluetooth-SIG-verifiziert),
- **UUID-/MAC-Normalisierung** (16/128-Bit, Separator-tolerant),
- **Company-ID-Registry**: 34 kuratierte, SIG-verifizierte Einträge (inkl. aller v17-Korrekturen) + `normalize_company_id()` (dezimal/`0x`-Hex) + `lookup_company()`,
- **Vendor-Service-UUIDs** (inkl. korrigierter Tile-Zuordnung),
- **Tracker-Profile** (Apple/Samsung/Tile/Google; Erkennung + prozeduraler Reset),
- **OuiDatabase** (24/28/36-Bit-Präfixe, längere gewinnen),
- **DeviceDatabase** (Upsert, by_mac/by_service/search mit **Technologie-Filter**, categories/**technologies**-Statistik, JSON-Roundtrip, Validierung),
- **DeviceRecord** mit `frequency_bands` (EU868, 868 MHz, 433,05–434,79 MHz),
- **Kuratierter Seed (71 Records)**: SmartHome/Zigbee + Thread/Matter (Eve, Nanoleaf, Aqara M3, DIRIGERA, HomePod mini, Apple TV 4K, Google TV Streamer/Nest Hub, Echo 4. Gen, eero, Hue Bridge, SONOFF ZB Bridge-U, Shelly, Tapo P110, Tado X, WiZ, Yale, Wemo Stage, Level Lock+, 5 IKEA-2025-Sensoren) + LoRaWAN/EU868 (Dragino, RAKwireless, Minew, Elsist, IMST, WILSEN.node, Netvox, MultiTech, M5Stack, ZENNER) + Wireless M-Bus (ZENNER, Elvaco, WEPTECH, Solvimus, Stackforce) + ISM-433-Generikklassen (6) + Medizin-BLE (Dexcom, Abbott, Oura, WHOOP, BioBeat, Empatica, Hörgeräte-Klasse).

### 3.2 Builder (`edge-agent/device_db_builder.py`)

Konsolidiert die öffentlichen Quellen zu `data/device_db.json`:
Zigbee2MQTT-docgen → Records; Bluetooth-Numbers-DB → GATT-Services **und
Company-IDs** (`company_ids.json`, Parser `parse_company_ids` mit
Grenzwert-Prüfung 0x0000–0xFFFF); mac-address-vendor-database → OUI.
URLs konstant/überschreibbar; **Parser sind dateibasiert und unit-testbar**
(Fixtures); der Live-Abruf muss auf einer Maschine mit Netzwerkzugang
laufen (Sandbox blockt HTTPS).

**Nicht automatisch importiert:** TTN-LoRaWAN-Repository (liegt als
`vendor/<hersteller>/<gerät>.yaml` ohne JSON-Index vor → bräuchte eine
YAML-Abhängigkeit; EU868-Auswahl ist kuratiert im Seed, ⏳ Roadmap) sowie
Thread-/Matter-Zertifizierungslisten (kein maschinenlesbarer Dump der
CSA/Thread Group → kuratierter Seed, ⏳ Roadmap).

### 3.3 Kotlin (`devicedb/DeviceDatabase.kt`)

Identische Semantik (GATT, Korrekturen, Tracker, OUI, **Company-IDs
inkl. v17-Korrekturen**, Seed mit allen 71 Records, Technologie-Filter,
`frequencyBands`) — Persistenz in der App über die bestehende
Room-Schicht (Roadmap).

### 3.4 REST-Endpunkte (OpenAPI 3.6.0, 29 Pfade)

| Endpunkt | Funktion |
| :--- | :--- |
| `GET /api/v1/devicedb/status` | Quelle (gebaut/Seed), Größen, Company-IDs, Kategorien, Technologien |
| `GET /api/v1/devicedb/lookup/mac/{mac}` | MAC → OUI-Hersteller + Geräte |
| `GET /api/v1/devicedb/lookup/service/{uuid}` | UUID → GATT-Service, Tracker, Geräte |
| `GET /api/v1/devicedb/lookup/company/{company_id}` | Company-ID (hex/dezimal) → SIG-Herstellername |
| `GET /api/v1/devicedb/search?q=&category=&technology=` | Volltext-/Kategorie-/Technologie-Suche |
| `GET /api/v1/devicedb/categories` | Kategorie-Statistik |

Der Agent lädt `data/device_db.json`, falls vorhanden, sonst den
kuratierten Seed (71 Einträge); gebaute Company-ID-Listen werden dabei
über den kuratierten Seed gelegt. Der Web-Visualizer erreicht die
Endpunkte über den neuen **REST-Proxy** in `server.js` (Panel „🗃️
Geräte-DB": Company-Lookup, Technologie-/Kategorie-Filter, Suche).
Offline-Verfügbarkeit übernimmt der Service Worker (Network-First mit
Cache-Fallback, s. docs/SERVICE_WORKER.md).

## 4. Verifikation

- **Python: 22 Tests für die Gerätedatenbank** (Normalisierung, GATT-Tabelle,
  OUI-Präfixlogik, Tracker-Korrekturen, Company-ID-Korrekturen/-Lookup,
  Datenbank-Queries/Roundtrip/Validierung, erweiterte Kategorien,
  Technologie-Filter, Frequenzbänder, Builder-Parser inkl.
  `parse_company_ids`) — Gesamt **144/144 grün** (7 neue).
- **Kotlin: 15 JVM-Tests für die Gerätedatenbank** (gespiegelt) —
  Gesamt **172** (5 neue).

## 5. Roadmap

| Phase | Inhalt | Status |
| :--- | :--- | :--- |
| **DDB 1.0** | Kern (OUI/GATT/Tracker/DeviceDatabase), Builder, REST, Kotlin-Spiegelung | ✅ |
| **DDB 1.1** | Erweiterte Kategorien v17 (Company-IDs, Thread/Matter, LoRaWAN, wM-Bus, ISM 433, Medizin-BLE), Technologie-Filter, Company-Lookup, Visualizer-Panel | ✅ |
| DDB 1.2 | Builder-Lauf mit Netzwerkzugang → `data/device_db.json` (inkl. voller Company-ID-Liste) ins Repo/Asset | ⏳ |
| DDB 1.3 | TTN-Repository-Import (YAML-Parser, einmaliger Snapshot) + CSA/Thread-Zertifizierungs-Dump | ⏳ |
| DDB 1.4 | App-Integration: Erkennung in `DeviceSourceMapper`/DeviceRegistry, Room-Persistenz, UI-Suche (UI_UX_PLAN) | ⏳ |
| DDB 1.5 | Update-Mechanismus (periodischer Abgleich mit den Quellen, Delta-Sync) | ⏳ |

## 6. Rechtlicher Hinweis

- **Reset-/Konfigurationsbefehle** dürfen ausschließlich auf **eigenen**
  oder ausdrücklich freigegebenen Geräten ausgeführt werden; Community-
  Frames (Scooter/E-Bikes) sind Reverse-Engineering ohne Hersteller-
  Freigabe — im Modell als `verified=false` gekennzeichnet.
- Erfasste **MAC-Adressen/BLE-Identitäten** der Umgebung sind
  personenbeziehbare Daten (DSGVO) — Verarbeitung nur lokal/offline,
  keine Cloud-Speicherung (vgl. TRIANGULATION.md §10).
- **Medizinische Geräte (CGM, Wearables):** die Datenbank enthält nur
  Hersteller-/Modell-Metadaten zur **Erkennung**, keine medizinische
  Interpretation; keine Claims zu Zulassung/Klassifizierung.
- „Alle EU-zugelassenen Geräte" ist als **Vollständigkeitsversprechen
  nicht erreichbar** (täglich wachsende Quellen); die Datenbank ist ein
  Snapshot mit dokumentierter Aktualität je Quelle.
