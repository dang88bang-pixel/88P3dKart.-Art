# 📡 Offline-Gerätedatenbank für Drahtlostechnologien — Machbarkeitsprüfung & Integration

> **Version:** v1.0 · **Datum:** 15. August 2026 ·
> **Eingabe:** „3dxAgent – Umfassende Offline-Gerätedatenbank für
> Drahtlostechnologien (EU)" (v16.0.0-DeviceDatabase)
>
> Prüfung der Quellen (mit Live-Verifikation), Fehlerkatalog des
> Spec-Codes und Integration des **testbaren Kerns**: OUI-Lookup,
> GATT-Standard-Services, Tracker-Profile, DeviceDatabase-Kern
> (Python + Kotlin identisch), Konsolidierungs-Builder und
> REST-Endpunkte.

---

## 1. Quellen-Verifikation (15.08.2026)

| Quelle (Spec) | Verifiziert? | Anmerkung |
| :--- | :--- | :--- |
| Zigbee2MQTT-Geräteliste | ✅ **live verifiziert** — aktuell **5.592 Geräte / 582 Hersteller** (Spec „5.521/583" ≈ korrekt, Stand veraltet) | docgen-JSON auf GitHub (Koenkk/zigbee2mqtt.io) |
| blakadder/zigbee (ZHA/Templates) | ✅ existiert | Templates je Hersteller |
| Nordic Bluetooth Numbers DB | ✅ (NordicSemiconductor/bluetooth-numbers-database) | 16-Bit-Service-/Characteristic-UUIDs |
| Bluetooth SIG Assigned Numbers | ✅ offiziell | Company-IDs + 16-Bit-UUID-Zuweisungen |
| IEEE-OUI / mac-address-vendor-database | ✅ (~50k Einträge, wöchentlich) | MA-L/MA-M/MA-S-Präfixe |
| Community-Repos (m365py, Navee/ZIMO-Hacks) | ⚠️ Reverse-Engineering | Frames **nicht zertifiziert** — nur mit `verified=false` + Referenz |
| FiRa/Qorvo/NXP-Doku (UWB) | ✅ Hersteller-Doku | Chips/Module (DWM3000, SR150/250) |

## 2. Fehlerkatalog (Spec-Code, korrigiert)

| # | Spec-Angabe | Befund | Korrektur |
| :--- | :--- | :--- | :--- |
| 1 | Tile-UUIDs `0xFEAA`, `0xFED5` | **`0xFEAA` ist Eddystone (Google)**; Tile sind laut Bluetooth SIG `0xFEEC`/`0xFEED` zugewiesen | Tracker-Profil korrigiert; Hinweis im Modell |
| 2 | Tile-Company-ID `0x0055` | nicht bestätigt | `company_id = null` (unverifiziert), Erkennung über die UUIDs |
| 3 | Xiaomi M365 `light_on`/`light_off` identische Frames | Copy-Paste-Fehler | Frame-Kommandos generell nur als `verified=false` + Referenz; prozedurale Resets als `verified=true` |
| 4 | „Galaxy S21–S24" / „Pixel 6 Pro–9" mit UWB | nur **+ / Ultra / Pro**-Modelle haben UWB (Basismodelle nicht) | als Ungenauigkeit dokumentiert |
| 5 | „Alle in der EU zugelassenen Geräte offline" | nicht erreichbar — Quellen ändern sich täglich (OUI), Zigbee-Liste wächst | realistische Zielsetzung: **konsolidierte Snapshot-DB** + Builder für Updates |
| 6 | DB-Größen (~50 MB JSON) | Schätzwerte ohne Messung | als Richtwerte gekennzeichnet |
| 7 | Service Worker mit `cdn.3dxagent.com/db/*` | fiktive URL + SW-Missverständnis (s. docs/SERVICE_WORKER.md) | DB liegt als Asset/`data/`-Datei; Updates via Builder/App-Download |

## 3. Übernommene Module

### 3.1 Python (`edge-agent/device_db.py`)

- **GATT-Standard-Services** (8 Services, 0x1800–0x183A, mit Characteristics —
  Bluetooth-SIG-verifiziert),
- **UUID-/MAC-Normalisierung** (16/128-Bit, Separator-tolerant),
- **Company-IDs** (Apple 0x004C, Samsung 0x0075, Google 0x00E0 — verifiziert)
  + **Vendor-Service-UUIDs** (inkl. korrigierter Tile-Zuordnung),
- **Tracker-Profile** (Apple/Samsung/Tile/Google; Erkennung + prozeduraler Reset),
- **OuiDatabase** (24/28/36-Bit-Präfixe, längere gewinnen),
- **DeviceDatabase** (Upsert, by_mac/by_service/search/categories,
  JSON-Roundtrip, Duplikat-/Pflichtfeld-Validierung, kuratierter Seed).

### 3.2 Builder (`edge-agent/device_db_builder.py`)

Konsolidiert die öffentlichen Quellen zu `data/device_db.json`:
Zigbee2MQTT-docgen → Records; Bluetooth-Numbers-DB → GATT-Services;
mac-address-vendor-database → OUI. URLs konstant/überschreibbar;
**Parser sind dateibasiert und unit-testbar** (Fixtures); der Live-Abruf
muss auf einer Maschine mit Netzwerkzugang laufen (Sandbox blockt HTTPS).

### 3.3 Kotlin (`devicedb/DeviceDatabase.kt`)

Identische Semantik (GATT, Korrekturen, Tracker, OUI, Seed-Datenbank) —
Persistenz in der App über die bestehende Room-Schicht (Roadmap).

### 3.4 REST-Endpunkte (OpenAPI 3.6.0)

| Endpunkt | Funktion |
| :--- | :--- |
| `GET /api/v1/devicedb/status` | Quelle (gebaut/Seed), Größen, Kategorien |
| `GET /api/v1/devicedb/lookup/mac/{mac}` | MAC → OUI-Hersteller + Geräte |
| `GET /api/v1/devicedb/lookup/service/{uuid}` | UUID → GATT-Service, Tracker, Geräte |
| `GET /api/v1/devicedb/search?q=&category=` | Volltext-/Kategorie-Suche |
| `GET /api/v1/devicedb/categories` | Kategorie-Statistik |

Der Agent lädt `data/device_db.json`, falls vorhanden, sonst den
kuratierten Seed (8 Einträge).

## 4. Verifikation

- **Python: 15 neue Tests** (Normalisierung, GATT-Tabelle, OUI-Präfixlogik,
  Tracker-Korrekturen, Datenbank-Queries/Roundtrip/Validierung,
  Builder-Parser mit Fixtures) — Gesamt **137/137 grün**.
- **Kotlin: 10 neue JVM-Tests** (gespiegelt) — Gesamt **167**.

## 5. Roadmap

| Phase | Inhalt | Status |
| :--- | :--- | :--- |
| **DDB 1.0** | Kern (OUI/GATT/Tracker/DeviceDatabase), Builder, REST, Kotlin-Spiegelung | ✅ |
| DDB 1.1 | Builder-Lauf mit Netzwerkzugang → `data/device_db.json` ins Repo/Asset | ⏳ |
| DDB 1.2 | App-Integration: Erkennung in `DeviceSourceMapper`/DeviceRegistry, Room-Persistenz, UI-Suche (UI_UX_PLAN) | ⏳ |
| DDB 1.3 | Update-Mechanismus (periodischer Abgleich mit den Quellen, Delta-Sync) | ⏳ |

## 6. Rechtlicher Hinweis

- **Reset-/Konfigurationsbefehle** dürfen ausschließlich auf **eigenen**
  oder ausdrücklich freigegebenen Geräten ausgeführt werden; Community-
  Frames (Scooter/E-Bikes) sind Reverse-Engineering ohne Hersteller-
  Freigabe — im Modell als `verified=false` gekennzeichnet.
- Erfasste **MAC-Adressen/BLE-Identitäten** der Umgebung sind
  personenbeziehbare Daten (DSGVO) — Verarbeitung nur lokal/offline,
  keine Cloud-Speicherung (vgl. TRIANGULATION.md §10).
- „Alle EU-zugelassenen Geräte" ist als **Vollständigkeitsversprechen
  nicht erreichbar** (täglich wachsende Quellen); die Datenbank ist ein
  Snapshot mit dokumentierter Aktualität je Quelle.
