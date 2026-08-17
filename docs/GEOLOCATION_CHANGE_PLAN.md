# 🗺️ Änderungsplan — Geolokalisierung in 3dxAgent

**Bezug:** [`GEOLOCATION_PROVIDERS.md`](GEOLOCATION_PROVIDERS.md) (Prüfbericht) · **Ziel-Release:** v4.5.0-Geo
**Basis:** 3dxAgent v4.4.0-ClientRules, Commit `e503fb3`

Dieser Plan setzt die Befunde des Prüfberichts in konkrete, datei-genaue Änderungen um.
Er ist in 6 Phasen gegliedert; jede Phase ist eigenständig lauffähig und testbar.

---

## Umsetzungsstand (Stand 15.08.2026)

| Bereich | Status | Belege / Dateien |
|---|---|---|
| Phase 1 — Datenmodell, Config, Env | ✅ umgesetzt | `models.py` (`ExternalEntity`, `GeoFix`, `GeoAnchor`), `config.py` (`GEO_*`, `EXT_*`), `.env.example`, `docker-compose.yml` |
| Phase 2 — Provider-Layer | ✅ umgesetzt | `geo/{projection,base,ichnaea,offline_cell,resolver}.py`, 9 REST-Endpunkte in `agent.py` |
| Phase 2 — Persistenz & Retention | ✅ umgesetzt | `database.py` (`geo_fixes`, `expires_at`, `purge_expired_geo`), Retention-Task in `lifespan()` |
| Stufe 1 — GTFS-Realtime | ✅ umgesetzt | `external/{protobuf_lite,base,gtfs_rt,manager}.py`, WS-Event + MQTT-Topic `3dxagent/external/entities` |
| Stufe 2 — Kontextring im Visualizer | ✅ umgesetzt | `web-visualizer/public/context-ring.js`, Einbindung in `main.js`, REST-Proxy in `server.js` |
| Phase 3 — Android-Client | ⬜ offen | Permissions in `AndroidManifest.xml` fehlen weiterhin |
| Phase 4 — Offline-Datenbestände | 🟡 teilweise | `scripts/fetch_geo_data.py` vorhanden, **noch nie ausgeführt** — es existiert keine `opencellid.sqlite` |
| Phase 5 — Compliance-Artefakte | ✅ umgesetzt | `docs/LICENSES.md`, Attributionsanzeige im Visualizer, Audit-Trail `GET /api/v1/geo/audit` |
| Phase 6 — Backlog | ⬜ offen | unverändert |

**Testabdeckung:** 83 Tests grün (`pytest tests/ -q` im Verzeichnis `edge-agent/`).

**Bewusste Abweichungen vom ursprünglichen Plan**

1. `GEO_MIN_QUALITY` steht auf **0.35** statt 0.5. Der 150-m-Genauigkeitsboden
   der Zellortung (`offline_cell.py`) ergibt rechnerisch nie mehr als ~0.47;
   bei 0.5 hätte die gesamte Offline-Kette keinen einzigen Fix geliefert.
2. Kein `protobuf`-Paket. `external/protobuf_lite.py` dekodiert das
   GTFS-RT-Wire-Format direkt — spart eine native ARM-Abhängigkeit auf dem CT45P.
3. Externe Entitäten werden **nicht massstäblich** gezeichnet. Bei Grid 40 m und
   Kamera-Far 500 wäre alles jenseits ~250 m unsichtbar; der Kontextring hält
   stattdessen Peilung und Distanz fest.

---

## 0. Grundsatzentscheidungen (vor dem ersten Commit zu bestätigen)

| # | Entscheidung | Empfehlung | Auswirkung |
|---|--------------|------------|------------|
| E1 | Fließt eine Netzwerkposition in den EKF? | **Nein.** Separate `GeoAnchor`-Struktur. Ausnahme: Wi-Fi RTT und RTK. | Kein Eingriff in `ekf_fusion.py` / `EkfFusion.kt` in Phase 1–3 |
| E2 | Default-Betriebsmodus? | **`GEO_OFFLINE_ONLY=true`** — alle Cloud-Provider deaktiviert | Keine ungewollte Datenabgabe; DSGVO-konform ab Werk |
| E3 | Gemeinsames Protokoll? | **Ichnaea `/v1/geolocate`** (Apache-2.0) | Providerwechsel = Config, nicht Code |
| E4 | Google Geolocation aufnehmen? | **Ja, aber hinter Flag + hartem 30-Tage-TTL** | Zusätzliche Retention-Logik in `database.py` |
| E5 | WiGLE aufnehmen? | **Nein** | Entfällt aus Backlog |
| E6 | Geocoding-Backend? | **Selbst gehostetes Photon** (`docker-compose.yml`), Public Nominatim nur als Notfall-Fallback mit 1 req/s | Neuer Service + Rate-Limiter |

---

## Phase 1 — Datenmodell & Konfiguration (Fundament)

### 1.1 `edge-agent/models.py` — neue Pydantic-Modelle

Ergänzend zu den bestehenden Modellen (`EkfState`, `Transform3D`, …):

```python
class WifiAccessPoint(BaseModel):
    """Ichnaea-kompatibler WLAN-Scan-Eintrag."""
    macAddress: str = Field(..., pattern=r"^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
    signalStrength: int | None = Field(None, ge=-120, le=0)   # dBm
    age: int | None = Field(None, ge=0)                        # ms
    channel: int | None = None
    signalToNoiseRatio: int | None = None

class CellTower(BaseModel):
    radioType: Literal["gsm", "wcdma", "lte", "nr"] | None = None
    mobileCountryCode: int | None = Field(None, ge=0, le=999)
    mobileNetworkCode: int | None = Field(None, ge=0, le=999)
    locationAreaCode: int | None = None
    cellId: int | None = None
    signalStrength: int | None = None
    age: int | None = None

class BluetoothBeacon(BaseModel):
    macAddress: str
    name: str | None = None
    signalStrength: int | None = None
    age: int | None = None

class GeolocateRequest(BaseModel):
    """Ichnaea /v1/geolocate Request-Body."""
    considerIp: bool = False          # Default False → keine stille IP-Ortung
    homeMobileCountryCode: int | None = None
    homeMobileNetworkCode: int | None = None
    radioType: str | None = None
    carrier: str | None = None
    wifiAccessPoints: list[WifiAccessPoint] = Field(default_factory=list)
    cellTowers: list[CellTower] = Field(default_factory=list)
    bluetoothBeacons: list[BluetoothBeacon] = Field(default_factory=list)
    fallbacks: dict[str, bool] | None = None

class GeoFix(BaseModel):
    """Normalisiertes Ergebnis eines beliebigen Providers."""
    lat: float = Field(..., ge=-90, le=90)
    lon: float = Field(..., ge=-180, le=180)
    accuracy_m: float = Field(..., gt=0)
    altitude_m: float | None = None
    source: str                       # 'offline_cell' | 'beacondb' | 'combain' | 'google' | ...
    license: str                      # 'CC-BY-SA-4.0' | 'proprietary-google' | ...
    attribution: str | None = None    # anzuzeigender Attributionstext
    ttl_days: int | None = None       # None = unbegrenzt speicherbar; 30 bei Google
    timestamp: float
    quality: float = Field(..., ge=0.0, le=1.0)   # nach CLIENT_RULES-Formel

class GeoAnchor(BaseModel):
    """Verknüpfung lokaler metrischer Frame <-> WGS84. Ergänzt Transform3D."""
    fix: GeoFix
    local_origin: tuple[float, float, float] = (0.0, 0.0, 0.0)
    heading_deg: float | None = None
    frame_id: str = "map"
```

**Wichtig:** `GeoFix.quality` folgt der bestehenden Formel aus `docs/CLIENT_RULES.md`
(`Q_total = 0.4·Q_snr + 0.3·Q_conf + 0.2·Q_latency + 0.1·Q_dup`, Verwerfen bei `Q < 0.5`).
`Q_conf` wird aus dem Accuracy-Radius abgeleitet:

```python
Q_conf = clamp(1.0 - log10(max(accuracy_m, 1.0)) / 4.0, 0.0, 1.0)
# 1 m → 1.00 | 10 m → 0.75 | 100 m → 0.50 | 1 km → 0.25 | 10 km → 0.00
```

Damit werden IP-Fixes (> 5 km) durch die bestehende Qualitätsschwelle **automatisch
verworfen** — kein Sonderfall nötig.

### 1.2 `edge-agent/config.py` — neue ENV-Keys

```python
    # --- Geolokalisierung ---
    GEO_ENABLED: bool = os.getenv("GEO_ENABLED", "true").lower() == "true"
    GEO_OFFLINE_ONLY: bool = os.getenv("GEO_OFFLINE_ONLY", "true").lower() == "true"
    GEO_PROVIDER_CHAIN: str = os.getenv(
        "GEO_PROVIDER_CHAIN", "offline_cell,local_ichnaea,beacondb"
    )
    GEO_TIMEOUT_S: float = float(os.getenv("GEO_TIMEOUT_S", "4.0"))
    GEO_CACHE_TTL_S: int = int(os.getenv("GEO_CACHE_TTL_S", "300"))
    GEO_MIN_QUALITY: float = float(os.getenv("GEO_MIN_QUALITY", "0.5"))

    # Provider-Endpunkte (alle überschreibbar → Nominatim-Policy „ohne Update umschaltbar")
    GEO_BEACONDB_URL: str = os.getenv("GEO_BEACONDB_URL", "https://api.beacondb.net/v1/geolocate")
    GEO_LOCAL_ICHNAEA_URL: str = os.getenv("GEO_LOCAL_ICHNAEA_URL", "")
    GEO_COMBAIN_URL: str = os.getenv("GEO_COMBAIN_URL", "")
    GEO_COMBAIN_KEY: str = os.getenv("GEO_COMBAIN_KEY", "")
    GEO_UNWIRED_URL: str = os.getenv("GEO_UNWIRED_URL", "")
    GEO_UNWIRED_KEY: str = os.getenv("GEO_UNWIRED_KEY", "")
    GEO_GOOGLE_KEY: str = os.getenv("GEO_GOOGLE_KEY", "")
    GEO_GOOGLE_TTL_DAYS: int = int(os.getenv("GEO_GOOGLE_TTL_DAYS", "30"))  # ToS-Pflicht

    # Offline-Datenbestände
    GEO_OFFLINE_CELL_DB: str = os.getenv("GEO_OFFLINE_CELL_DB", "./data/opencellid.sqlite")
    GEO_IP_MMDB: str = os.getenv("GEO_IP_MMDB", "./data/ipinfo-lite.mmdb")

    # Geocoding
    GEO_GEOCODER_URL: str = os.getenv("GEO_GEOCODER_URL", "http://photon:2322")
    GEO_GEOCODER_KIND: str = os.getenv("GEO_GEOCODER_KIND", "photon")  # photon|nominatim
    GEO_GEOCODER_RPS: float = float(os.getenv("GEO_GEOCODER_RPS", "1.0"))  # Nominatim-Limit
    GEO_USER_AGENT: str = os.getenv("GEO_USER_AGENT", "3dxAgent/4.5 (+kontakt@example.org)")
```

### 1.3 `edge-agent/requirements.txt`

```diff
+httpx==0.27.0            # bislang nur Test-Dependency → wird Runtime-Dependency
+maxminddb==2.6.2         # MMDB-Reader (nur Reader, keine MaxMind-Daten!)
```

`geoip2` wird **bewusst nicht** verwendet — `maxminddb` reicht als Reader und vermeidet
den Eindruck einer MaxMind-Datenbindung (siehe Befund C6).

### 1.4 `.env.example` (neu) + `docker-compose.yml`

Alle `GEO_*`-Keys mit leeren Defaults dokumentieren. `.gitignore` deckt `.env` bereits ab ✅.
Im `edge-agent`-Service die `GEO_*`-Variablen durchreichen; neues Volume `./data:/data`.

**Deliverables Phase 1:** `models.py`, `config.py`, `requirements.txt`, `.env.example`, `docker-compose.yml`
**Tests:** `tests/test_geo_models.py` — Validierung MAC-Pattern, Ranges, Quality-Mapping.

---

## Phase 2 — Provider-Layer im Edge-Agent

### 2.1 Neues Paket `edge-agent/geo/`

```
edge-agent/geo/
├── __init__.py
├── base.py           # abstrakte Klasse GeoProvider
├── offline_cell.py   # OpenCellID-SQLite-Lookup (Tier 0)
├── offline_ip.py     # IPinfo-Lite-MMDB-Lookup (Tier 0, nur Land/ASN)
├── ichnaea.py        # generischer Ichnaea-Client (beaconDB, lokale Instanz, Combain)
├── unwired.py        # Unwired-Labs-Adapter (abweichendes Request-Format)
├── google.py         # Google-Geolocation-Adapter (+ ttl_days=30 erzwungen)
├── resolver.py       # Kaskade, Cache, Policy-Gate, Quality-Filter
└── geocoder.py       # Reverse-Geocoding (Photon/Nominatim) mit Token-Bucket
```

**`base.py` — das zentrale Interface**

```python
class GeoProvider(ABC):
    name: str
    tier: int              # 0=offline, 1=lokales Netz, 2=cloud, 3=kontext
    license: str
    attribution: str | None
    ttl_days: int | None

    @abstractmethod
    async def locate(self, req: GeolocateRequest) -> GeoFix | None: ...

    def available(self) -> bool: ...
```

**`resolver.py` — Kaskadenlogik**

```
1. Policy-Gate:  GEO_ENABLED? sonst None
2. Cache-Lookup: Hash über (sortierte BSSIDs, CellIDs) → Treffer < GEO_CACHE_TTL_S? → zurück
3. Kette durchlaufen (GEO_PROVIDER_CHAIN, in Reihenfolge):
      - tier >= 2 UND GEO_OFFLINE_ONLY  → überspringen
      - provider.available() == False   → überspringen
      - locate() mit GEO_TIMEOUT_S; Fehler/Timeout → protokollieren, nächster
      - GeoFix.quality < GEO_MIN_QUALITY → verwerfen, nächster
4. Erster gültiger Fix gewinnt; Cache schreiben (Cache-TTL = min(TTL, ttl_days))
5. Audit-Log: {provider, latency_ms, accuracy_m, quality, accepted}
```

**Wichtig:** `considerIp` wird **standardmäßig auf `False`** gesetzt, damit kein Provider
still auf IP-Ortung zurückfällt und ein 20-km-Fix als „Position" ausgibt.

### 2.2 `edge-agent/agent.py` — neue Endpunkte

```
POST /api/v1/geolocate          → GeolocateRequest  → GeoFix
GET  /api/v1/geo/providers      → Liste {name, tier, available, license, attribution}
POST /api/v1/geo/anchor         → GeoAnchor setzen/aktualisieren
GET  /api/v1/geo/anchor         → aktueller GeoAnchor
POST /api/v1/geo/reverse        → {lat, lon} → {display_name, address, attribution}
```

Zusätzlich: WS-Event `geo_fix` auf dem bestehenden `/ws/agent/events`-Kanal, damit
`web-visualizer` und `ui/map` den Anker live bekommen.

### 2.3 `edge-agent/database.py` — Schema-Erweiterung

```sql
CREATE TABLE IF NOT EXISTS geo_fixes (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    timestamp   REAL    NOT NULL,
    lat         REAL    NOT NULL,
    lon         REAL    NOT NULL,
    accuracy_m  REAL    NOT NULL,
    source      TEXT    NOT NULL,
    license     TEXT    NOT NULL,
    quality     REAL    NOT NULL,
    expires_at  REAL,            -- NULL = unbegrenzt; gesetzt bei Google (30 Tage)
    scan_id     TEXT
);
CREATE INDEX IF NOT EXISTS idx_geo_expires ON geo_fixes(expires_at);
```

Der bestehende Retention-Job wird erweitert:

```python
def purge_expired_geo(self) -> int:
    """Löscht lizenzpflichtig ablaufende Fixes (Google-ToS: max. 30 Tage)."""
    now = time.time()
    cur = self._conn.execute(
        "DELETE FROM geo_fixes WHERE expires_at IS NOT NULL AND expires_at < ?", (now,)
    )
    return cur.rowcount
```

**Zusätzlich (Befund C2):** `merged_maps` hat bislang **keine** Retention. Ergänzen:
Spalte `created_at REAL` + Aufnahme in den Retention-Lauf, damit georeferenzierte
Merge-Ergebnisse nicht unbegrenzt lizenzpflichtige Koordinaten konservieren.

**Deliverables Phase 2:** `geo/`-Paket, `agent.py`, `database.py`, `openapi.yaml`
**Tests:** `tests/test_geo_resolver.py` (Kaskade, Offline-Gate, Quality-Filter, Cache),
`tests/test_geo_retention.py` (TTL-Löschung), Provider-Tests mit `httpx.MockTransport`.

---

## Phase 3 — Android-Client

### 3.1 `AndroidManifest.xml` — fehlende Berechtigungen

```xml
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.READ_PHONE_STATE" />
<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES"
    android:usesPermissionFlags="neverForLocation" tools:targetApi="33" />

<uses-feature android:name="android.hardware.wifi.rtt" android:required="false" />
<uses-feature android:name="android.hardware.location.gps" android:required="false" />
```

> Hinweis: `neverForLocation` gilt hier **nicht** — die Scans werden explizit zur Ortung
> genutzt. Das Flag ist deshalb bei `NEARBY_WIFI_DEVICES` zu **entfernen**, sobald die
> Scan-Ergebnisse in `GeolocateRequest` fließen, sonst liefert Android leere BSSIDs.

### 3.2 `sensors/NetworkDataCollector.kt` — vollständige Neufassung

**Ist-Zustand:** liefert nur `connectionInfo.ssid`/`rssi` und `dataNetworkType`/Operator.
Das ist für Triangulation **nutzlos** — es fehlt die Umgebungsliste.
`hasLocationPermission()` ist definiert, wird aber **nirgends aufgerufen**.

**Soll:**

| Neu | Beschreibung |
|-----|--------------|
| `scanWifi(): List<WifiAccessPoint>` | `wifiManager.scanResults` → BSSID, `level`, `frequency`, `timestamp` → `age`. Filter: `_nomap`-Suffix im SSID **ausschließen** (Opt-out-Konvention), Mobile-Hotspots heuristisch verwerfen |
| `scanCells(): List<CellTower>` | `telephonyManager.allCellInfo` → `CellInfoLte/Nr/Gsm/Wcdma` → MCC/MNC/LAC/CID + `signalStrength` |
| `scanBle(): List<BluetoothBeacon>` | Anbindung an vorhandenen `BleTokenManager` — kein zweiter Scanner |
| Scan-Throttling | Android 9+: max. 4 Scans / 2 min → interner Token-Bucket + `Duration`-basierter Cache (Befund C8) |
| Permission-Gate | `hasLocationPermission()` **tatsächlich aufrufen**; bei Verweigerung leere Liste statt Exception |
| MAC-Normalisierung | Lowercase, `:`-getrennt — Ichnaea-Schema erwartet genau das |

### 3.3 Neues Paket `sensors/geo/`

```
sensors/geo/
├── GeoFix.kt              # data class, spiegelt das Python-Modell
├── GeoProvider.kt         # interface { suspend fun locate(req): GeoFix? }
├── OfflineCellProvider.kt # Room-basierte lokale OpenCellID-Tabelle
├── IchnaeaProvider.kt     # OkHttp-Client, konfigurierbare URL
├── EdgeAgentProvider.kt   # delegiert an POST /api/v1/geolocate des Edge-Agents ← Default
├── WifiRttProvider.kt     # WifiRttManager (802.11mc) — 1–2 m, offline
└── GeoResolver.kt         # Kaskade analog resolver.py
```

**Designentscheidung:** Der Android-Client ruft **primär den Edge-Agent** (`EdgeAgentProvider`)
auf und nicht direkt Cloud-Provider. Vorteile: API-Keys verlassen das Gerät nie, Attribution
und Retention werden zentral durchgesetzt, Provider-Wechsel ohne App-Update (erfüllt
Nominatim-Policy-Anforderung C5). Direkte Provider sind nur der Standalone-Fallback.

### 3.4 `offline/LocalApiServer.kt` — Blocker beheben

**Blocker:** Der Server (JDK-`ServerSocket`, Port 8081) **parst den Request-Body nicht** —
`registerRoute(path) { JSONObject -> JSONObject }` übergibt dem Handler stets ein *leeres*
`JSONObject`. Ein `/api/v1/geolocate`-Endpunkt ist damit **unmöglich**, weil der
Scan-Payload nicht ankommt.

Notwendige Vorarbeit:
1. `Content-Length` auslesen, Body vollständig lesen, als `JSONObject` parsen.
2. Fehlerpfade: 400 bei ungültigem JSON, 413 bei zu großem Body, 415 bei falschem Content-Type.
3. **CORS `*` einschränken** — passt nicht zu `docs/CLIENT_RULES.md` (TLS 1.2+, API-Key/JWT/mTLS).
4. Danach `registerRoute("/api/v1/geolocate")` ergänzen (Offline-Provider only).

> Dieser Punkt ist ein eigenständiger Bugfix und sollte als separater Commit laufen —
> er behebt eine Lücke, die alle künftigen POST-Routen betrifft, nicht nur Geolokalisierung.

### 3.5 Pipeline-Integration

`pipeline/DataAcquisitionService.kt` und `edge-agent/pipeline.py` teilen das Schema
`SensorDataPoint{timestamp, source, x, y, z, quality}` (Buffer 10.000).

**Integration ohne Schemabruch:** Ein `GeoFix` wird **nicht** als `SensorDataPoint`
eingespeist (er hat keine lokalen Metrik-Koordinaten und ist zu ungenau). Stattdessen:

- `GeoAnchor` wird **einmalig pro Scan** in Stufe 4 („Exakte Abbildung") als
  Frame-Metadatum gesetzt, **nach** der lokalen Rekonstruktion.
- `icp_merger.py` erhält einen optionalen Parameter `initial_guess_from_anchor` —
  zwei Karten mit GeoAnchor werden vor dem ICP über ihre WGS84→ENU-Differenz
  grob vorausgerichtet (Anwendungsfall C aus dem Prüfbericht).
- Nur `WifiRttProvider` und ein künftiger RTK-Provider dürfen als echter
  `SensorDataPoint` (`source="wifi_rtt"` / `"rtk"`) in den EKF.

**Deliverables Phase 3:** Manifest, `NetworkDataCollector.kt`, `sensors/geo/`,
`offline/LocalApiServer.kt`, `pipeline/DataAcquisitionService.kt`, `network/AgentApiClient.kt`
**Tests:** Robolectric-Tests für Scan-Mapping und Throttling; Instrumented-Test für RTT-Verfügbarkeit.

---

## Phase 4 — Offline-Datenbestände

| Artefakt | Quelle | Lizenz | Ablage | Größe |
|----------|--------|--------|--------|-------|
| Zellen-DB (regional gefiltert, z. B. MCC 262) | OpenCellID-Dump | **CC BY-SA 4.0** | `data/opencellid.sqlite` | ~50–200 MB |
| IP→Land/ASN | IPinfo Lite MMDB | kommerzielle Rechte inkl. | `data/ipinfo-lite.mmdb` | ~30 MB |
| Geocoder-Index (regional) | Photon/OSM | ODbL | Docker-Volume | 1–10 GB |

**Neues Skript `scripts/fetch_geo_data.py`:**
Lädt Dumps, filtert nach konfigurierten MCCs, baut das SQLite-Schema mit räumlichem Index
(`lat`/`lon` gerastert), schreibt eine `data/ATTRIBUTION.json` mit Quelle/Lizenz/Abrufdatum.

**Kritisch:** Diese Artefakte gehören **nicht ins Git-Repo** (Größe + Share-Alike).
`.gitignore` erweitern:

```gitignore
# Geo-Datenbestände (extern bezogen, lizenzpflichtig attribuiert)
data/*.mmdb
data/*.sqlite
data/opencellid*
```

**`docker-compose.yml`:** neuer Service `photon` (Profil `geo`), damit die
Nominatim-Public-API gar nicht erst gebraucht wird.

---

## Phase 5 — Compliance-Artefakte (nicht optional)

| Datei | Inhalt |
|-------|--------|
| `docs/LICENSES.md` **(neu)** | Vollständige Attributionsliste: OpenCelliD (CC BY-SA 4.0, sichtbarer Link), OSM/ODbL, DB-IP (via beaconDB), IPinfo Lite, Ichnaea (Apache-2.0), beaconDB (AGPL-3.0 Server) |
| `docs/PRIVACY.md` **(neu)** | Welche Daten (BSSIDs, Cell-IDs) unter welchen Umständen an wen übertragen werden; Opt-in-Mechanik; Löschfristen; Grundlage für das Verarbeitungsverzeichnis |
| `ui/` Attributions-Screen | Pflichtanzeige „OpenCelliD" + Link, OSM-Attribution auf Kartenansichten |
| `docs/CLIENT_RULES.md` | Abschnitt „Geolokalisierungs-Signale": Q-Berechnung aus `accuracy_m`, Verwerfungsschwelle, `GATEWAY`-Clients (0,1 Hz) als Geo-Quelle |
| `docs/API.md` | Die 5 neuen Endpunkte + `geo_fix`-WS-Event |
| `docs/ARCHITECTURE.md` | Tier-0..3-Kaskade in das 3-Schichten-Bild einzeichnen |
| `docs/OFFLINE.md` | Zeilen für `resolver.py ↔ GeoResolver.kt`, `ichnaea.py ↔ IchnaeaProvider.kt` in die Portierungstabelle |
| `docs/ROADMAP.md` | Neue **Phase 5 — Georeferenzierung** (die bisherigen Phasen 0–4 + v2.0 sind alle ✅) |
| `edge-agent/openapi.yaml` | Schemas `GeolocateRequest`, `GeoFix`, `GeoAnchor` |

---

## Phase 6 — Backlog (nach v4.5.0)

| Vorhaben | Aufwand | Nutzen |
|----------|---------|--------|
| **NTRIP/RTK-Client** (RTCM3 über NTRIP, SAPOS/RTK2go) + GNSS Raw Measurements | L | 🌟 1–3 cm Georeferenzierung → macht IFC/BIM-Export vermessungstauglich |
| **SUPL/PSDS-Proxy** (nach GrapheneOS-Vorbild) | M | TTFF < 5 s ohne Datenabgabe an den Netzbetreiber |
| **Overpass-Prior** für `EnvironmentReconstructor` | M | Gebäudeumriss als Randbedingung der Mesh-Rekonstruktion |
| **Eigene Ichnaea-Instanz** (Apache-2.0) mit selbst erhobenen Daten | L | Volle Datenhoheit, keine Fremdlizenz — passt zu BOS-Anforderungen |
| **Combain OnPrem** evaluieren | S (Vertrieb) | Einzige kommerzielle Offline-fähige Option |

---

## Zusammenfassung: geänderte und neue Dateien

```
NEU     docs/GEOLOCATION_PROVIDERS.md         Prüfbericht (dieses Paket)
NEU     docs/GEOLOCATION_CHANGE_PLAN.md       dieser Plan
NEU     docs/LICENSES.md                      Attributionspflichten
NEU     docs/PRIVACY.md                       DSGVO-Dokumentation
NEU     edge-agent/geo/{base,offline_cell,offline_ip,ichnaea,
                        unwired,google,resolver,geocoder}.py
NEU     edge-agent/tests/{test_geo_models,test_geo_resolver,test_geo_retention}.py
NEU     scripts/fetch_geo_data.py
NEU     .env.example
NEU     android-app/.../sensors/geo/{GeoFix,GeoProvider,OfflineCellProvider,
                                     IchnaeaProvider,EdgeAgentProvider,
                                     WifiRttProvider,GeoResolver}.kt

GEÄND.  edge-agent/models.py                  + 6 Modelle
GEÄND.  edge-agent/config.py                  + 18 ENV-Keys
GEÄND.  edge-agent/agent.py                   + 5 Endpunkte, + WS-Event geo_fix
GEÄND.  edge-agent/database.py                + geo_fixes, + TTL-Purge, + merged_maps-Retention
GEÄND.  edge-agent/icp_merger.py              + initial_guess_from_anchor
GEÄND.  edge-agent/requirements.txt           + httpx, maxminddb
GEÄND.  edge-agent/openapi.yaml               + 3 Schemas
GEÄND.  android-app/.../AndroidManifest.xml   + 6 Permissions, + 2 Features
GEÄND.  android-app/.../NetworkDataCollector.kt   Neufassung (Scanlisten statt Einzelwert)
GEÄND.  android-app/.../offline/LocalApiServer.kt Body-Parsing (Bugfix) + Route
GEÄND.  android-app/.../pipeline/DataAcquisitionService.kt   GeoAnchor in Stufe 4
GEÄND.  docker-compose.yml                    + GEO_*-ENV, + photon-Service (Profil geo)
GEÄND.  .gitignore                            + data/*.mmdb, data/*.sqlite
GEÄND.  docs/{API,ARCHITECTURE,OFFLINE,ROADMAP,CLIENT_RULES}.md
```

**Aufwandsschätzung:** Phase 1–3 ≈ 3–5 Personentage · Phase 4 ≈ 1–2 Tage ·
Phase 5 ≈ 1 Tag · Phase 6 separat planen.
