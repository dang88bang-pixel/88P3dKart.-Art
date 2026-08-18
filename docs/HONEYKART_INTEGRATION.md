# 📦 honeyKart-Integration — Status (bis Abschnitt „Geplannt:")

Dieses Dokument ordnet die honeyKart.88-Spezifikation (bis zum Abschnitt
„Geplannt:", Zeile 5949) sowie die Spezifikation „Erkennung & Unterscheidung
von Wänden und Menschen" dem Implementierungsstand im Repository zu.

## ✅ Aktiv integriert (implementiert + getestet)

| honeyKart-Abschnitt | Umsetzung | Ort |
|---|---|---|
| **BLE-Token-Tracking & Positionsbestimmung** (Triangulation, Kalman-RSSI, Pfadverlust) | bereits in früheren PRs: Trilateration (robust, LTS), Kalman-Filter (adaptiv, ~69 % Rausch-Reduktion), Pfadverlustmodell (A=−61,92 dBm, n=1,64), Fingerprinting (k-NN/wk-NN) | `edge-agent/trilateration.py`, `signal_processing.py`, `positioning.py` |
| **Live-Dashboard auf OpenStreetMap** (Echtzeit-Positionen, Aktionen pro Gerät) | Flotten-Registry + `/api/v1/fleet*` + `fleet.html` (Leaflet lokal gebündelt, Genauigkeitskreise, Aktionsleiste, Umkreissuche) | `edge-agent/fleet.py`, `web-visualizer/public/fleet.*` |
| **Plug-and-Play-Mesh** (Discovery, Store-and-Forward, Konsens-Sync) | Konsens-Synchronisation (mittelwert-erhaltend), Nearby-Suche (eigene Flotte + BLE-Zubehör), Mesh-Aktionen capability-geprüft | `edge-agent/mesh_sync.py`, `fleet.py` |
| **Farbkodierung & Live-View** (Grau/Blau/Grün/Rot/Gelb; Personen/Tiere NIE speichern) | verbindliche Palette + **erzwingender** PersistenceFilter (Live-Only-Typen werden vor Persistenz/Export entfernt, Geräte anonymisiert) | `edge-agent/privacy.py`, `web-visualizer/public/colorcoding.js` |
| **Datenpersistenz & Crash-Recovery** (SQLite WAL, Checkpoints, Retention) | Checkpoints (SHA-256-Snapshots), `PRAGMA integrity_check`, Retention | `edge-agent/database.py` |
| **QR-Code-Token-Anbindung** (JSON-Format token_id/mac/name/pairing_code/company_id/battery_type/firmware_version) | **NEU:** `bind_token_from_qr` + Endpunkt `POST /api/v1/fleet/bind-qr`; Re-Bind aktualisiert Metadaten, MAC als stabile ID | `edge-agent/fleet.py` |
| **Wand-/Mensch-Unterscheidung** (3-Stufen: Voxel 0,05 m → Höhenfilter 0,5–2,5 m → Euklidisches Clustering 0,2 m/10 Pkt. → PCA-Planarität → RANSAC-Ebenen → Zylinder-Validierung r=0,35 m) | **NEU:** `WallPersonClassifier`; Pipeline-`DataInterpreter` nutzt ihn für das Mittelband; Endpunkt `POST /api/v1/semantic/classify` | `edge-agent/wall_person_classifier.py`, `pipeline.py` |
| **Service Worker / Offline-Fähigkeit** | vorhandener `sw.js` (Cache-First/Network-First) | `web-visualizer/public/sw.js` |
| **CT45P-Hardware-Referenz** (Wi-Fi 6, 2nd BLE, OEMConfig, Provisioning) | Dokumentation, keine Code-Änderung nötig | `docs/` |

### Anmerkungen zur Wand-/Mensch-Klassifikation

- **Planaritätsmaß:** Statt der Spezifikationsformel (λ₂−λ₃)/λ₁ wird das
  **elongationsrobuste Maß (λ₂−λ₃)/λ₂** verwendet. Begründung: ein
  langgestrecktes Wandstück (z. B. 6 m × 1,8 m) hätte nach der
  Originalformel λ₁ ≫ λ₂ und würde fälschlich als Linie (nicht planar)
  verworfen — das widerspräche dem Ziel „Wand = planar → speichern".
  Die Schwellen (0,60 nah / 0,53 weit) bleiben unverändert.
- **Ausgabe-Semantik:** `wall` = statisch, persistierbar; `dynamic` =
  volumetrischer Dynamik-Kandidat, **nie persistierbar** (Live-Only, wird
  zusätzlich vom PersistenceFilter erzwungen entfernt).
- **„dynamic" ist Teil von `LIVE_ONLY_TYPES`** (privacy.py) — die
  Nicht-Persistenz ist damit technisch erzwungen und per Integrationstest
  belegt (Pipeline → Filter → DB ohne Dynamik-Daten).

## ⛔ Bewusst NICHT integriert (mit Begründung)

| honeyKart-Abschnitt | Grund |
|---|---|
| **„Mach kein Auge" / Erkennung & AKTIVE Unterbindung von Überwachungsgeräten** (Reconnect-Flooding zur Störung fremder Geräte) | Aktive Beeinträchtigung fremder Geräte/Netze — rechtlich unzulässig (Störung von Telekommunikationsanlagen), nicht umsetzbar |
| **Erweiterte Aktive Gegenmaßnahmen** (Deauthentication-Attack, Beacon-Spam, Handshake-Capture + Dictionary-Angriff, Rogue Access Point) | Angriffe auf fremde Netze — Straftatbestände, nicht umsetzbar |
| **Atemfrequenz-/Doppler-Biometrie** (0,15–0,4-Hz-Atemerkennung zur Mensch-Validierung, Thermalsignatur, TinyML-Person/Tier-Modell) | Biometrische Personenerkennung — die Klassifikation arbeitet rein geometrisch |
| **Taktisches Vital-/Combat-Monitoring Dritter** | Gesundheitsdaten-Überwachung ohne erkennbare Einwilligungsarchitektur |
| **Personensuche / FRP-Bypass** (aus der älteren OpenAPI-Doku) | Überwachungs-/Umgehungswerkzeuge |

Die jeweiligen honeyKart-Abschnitte verbleiben als reine Dokumentation im
Dokument; im Rahmen dieser Integration wurde **keinerlei Code** für diese
Funktionen geschrieben. (Hinweis: Im Repository existieren aus früheren
Ständen Module wie `tactical.py` — diese wurden im Rahmen der Integration
weder implementiert noch verdrahtet noch getestet.)

## Tests

```bash
cd edge-agent
pytest tests/test_wall_person.py tests/test_honeykart.py -q   # 13 neue Tests
pytest tests/ -q --ignore=tests/test_tactical.py \
       --ignore=tests/test_radar_processing.py --ignore=tests/test_uwb.py
# → 312 Tests grün
```
