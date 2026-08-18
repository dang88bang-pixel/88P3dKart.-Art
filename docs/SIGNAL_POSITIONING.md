# 📡 Signalverarbeitung, Positionierung, Mesh-Sync & Datenschutz-Schicht

**Status:** aktiv implementiert und getestet (edge-agent, Python) — die
mathematischen Kerne der Plattform-Spezifikation mit echten Endpunkten.

## 1. Signalverarbeitung & Rauschunterdrückung (`signal_processing.py`)

| Filter | Mechanismus | Verifikation |
|---|---|---|
| **KalmanRssiFilter** | 2-Zustands-Kalman [rssi, rate] mit adaptivem Messrauschen R (Varianz der letzten 10 Messungen → stärkere Glättung bei Streuung) | Rausch-Std-Reduktion auf **~31 %** des Rohwerts (Tests), Konvergenz gegen den wahren RSSI |
| **MedianMovingAverageFilter** | Median (Ausreißer, Fenster 5) → gleitender Mittelwert (Fenster 10) | Spike-Dämpfung < 8 dBm Restfehler (Tests) |
| **HampelFilter** | \|x − median\| > 3·MAD → Ersetzung durch Median | Ausreißer werden exakt ersetzt (Tests) |

**Integration:** Flotten-BLE-Distanzschätzung nutzt den Kalman-geglätteten RSSI
(`fleet.py` — `rssi_smoothed` wird separat ausgewiesen, Rohwert bleibt erhalten).
Kalman-Initialisierung am ersten Messwert: die erste Distanzschätzung ist
identisch zum Rohwert, danach Glättung.

**Endpunkt:** `POST /api/v1/signal/smooth` — `{values, method: kalman|median|hampel}` → geglättete Serie.

## 2. Positionsbestimmung (`positioning.py`)

| Methode | Formel / Mechanismus | Einsatz |
|---|---|---|
| **Pfadverlust** | d = d₀ · 10^((Tx−RSSI)/(10·n)); Kalibrierung A = −61,92 dBm @ 1 m, n = 1,64 | Distanz aus RSSI |
| **Trilateration** | bestehende robuste Implementierung (`trilateration.py`: Levenberg–Marquardt, LTS-Ausreißerbehandlung, Unsicherheits-Gewichtung) | ≥ 3 Anker (3D: ≥ 4) |
| **Weighted Centroid** | Position = Σ(wᵢ·Pᵢ)/Σwᵢ, wᵢ = 1/(dᵢ+ε) | < 3 Anker / hohes Rauschen |
| **Fingerprinting** | k-NN & weighted k-NN, euklidisch über RSSI-Vektoren (fehlende Beacons = −100 dBm) | Indoor-Referenzpunkte |

**Endpunkte:**
- `POST /api/v1/positioning/estimate` — automatische Methodenwahl (Trilateration → WCL-Fallback), einheitliche Antwort
- `POST /api/v1/positioning/fingerprint` / `POST /api/v1/positioning/fingerprint/locate` — Fingerprint-DB (max. 5000, In-Memory)

Wi-Fi-RTT (802.11mc) und UWB-TDoA/AoA bleiben der Hardware-Ebene vorbehalten;
die bestehende Triangulations-API (`/api/v1/triangulation/solve`) und der
`uwb_processor` sind die Produktionspfade der CT45P-App.

## 3. Mesh-Synchronisation (`mesh_sync.py`)

**Random-Broadcast-Consensus** als symmetrisches Pairwise-Averaging:
`tᵢ, tⱼ ← tᵢ + α(tⱼ−tᵢ), tⱼ − α(tⱼ−tᵢ)` — der **Mittelwert bleibt exakt
erhalten** (Invariante, getestet) und die Knotenzeiten konvergieren gegen ihn.

**Endpunkt:** `POST /api/v1/mesh/sync` — `{times, tolerance, alpha, max_rounds}` → Konvergenzbericht (Runden, Restdiskrepanz, Mittelwert).

## 4. Datenschutz-Schicht (`privacy.py`) — erzwingend

Setzt die Regeln der Farbkodierungs-Spezifikation serverseitig durch
(„Personen/Tiere NIE speichern"):

| Regel | Umsetzung | Test |
|---|---|---|
| **Live-Only-Typen** (person/animal/moving_person) | `PersistenceFilter.filter_objects` entfernt sie vollständig aus Persistenz-/Export-Payloads | Unit + Integration (Pipeline → Filter → DB enthält keine person-Daten) |
| **Geräte-Anonymisierung** | ID/MAC → SHA-256-Hash (deterministisch, nicht umkehrbar), Metadaten-Strip (mac/uuid/user_id/…) | deterministisch, kollisionsfrei |
| **Positions-Granularisierung** | 0,1 m (Datensparsamkeit) | Rounding-Tests |
| **Audit** | `audit()` zählt entfernte Live-Only-Objekte **ohne** deren Daten preiszugeben | Zähl-Tests |

**Endpunkt:** `POST /api/v1/privacy/filter` — nimmt `{objects, devices, metadata}` und
liefert die gefilterte, anonymisierte Form + Audit-Zähler. Die App ruft diesen
Filter **vor** jeder Persistenz/Export auf.

**Visualizer:** `web-visualizer/public/colorcoding.js` stellt die verbindliche
Palette bereit (Grau=Struktur, Blau=Geräte, Grün=Live-View, Rot=Ausgänge,
Gelb=Markierungen); `LIVE_ONLY_TYPES` ist 1:1 mit `privacy.py` synchron.

## 5. Persistenz & Crash-Recovery (`database.py`)

| Mechanismus | Umsetzung |
|---|---|
| **Checkpoints** | Tabelle `checkpoints`: Punktzahl, letzter Datensatz, SHA-256 über die Datentabellen (ohne die selbst-referenzielle Checkpoint-Tabelle); max. 10 Checkpoints |
| **Integrität** | `PRAGMA integrity_check` + Checksummen-Abgleich mit dem letzten Checkpoint |
| **Retention** | bestehend: Alters-/Anzahl-Limit (`enforce_retention`, WAL-Modus) |

**Endpunkte:** `POST /api/v1/checkpoints` (Admin) · `GET /api/v1/checkpoints` · `POST /api/v1/checkpoints/verify`

## Tests

```bash
cd edge-agent
pytest tests/test_signal_processing.py tests/test_positioning.py \
       tests/test_algorithms.py tests/test_api_algorithms.py -q
# → 23 + 7 Endpunkt-Tests; Gesamtsuite: 298 Tests grün
```
