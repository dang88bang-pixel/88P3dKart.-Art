# 🏭 Betriebsgelände-Betrieb, EDM-Lebenszyklus & Offline-Sync

**Kontext (verbindlich):** Die Plattform läuft ausschließlich auf dem eigenen,
**EDM-/MDM-verwalteten Betriebsgelände** — eigene Honeywell-CT45P-Geräte,
eigene Flotte, eigene Infrastruktur. Nutzung durch Dritte ist nicht möglich:
Sie setzt das EDM-Gerät **und** die Betriebskenntnis voraus (Provisioning nur
durch die eigene Administration). Diese Einordnung definiert, welche der
honeyKart-Mechanismen umgesetzt sind.

## 1. Betriebsgelände-Sicherheit (passiv) — `premises_security.py`

| Stufe | Umsetzung | Status |
|---|---|---|
| **Stufe 1: Bekannt vs. Unbekannt** | Jede Sichtung (Flotte, BLE-Zubehör, Netzwerk) wird gegen die eigenen Register klassifiziert: `own` (gebundene eigene Geräte) · `infra` (bekannte Infrastruktur/OUI) · `unknown` (Fremdgerät möglich). Neue Unbekannte lösen `premises_alert` (WS) aus. | ✅ aktiv |
| **Stufe 2: Passiv-Sensorik** | Magnetfeld-/IR-/RF-Berichte **eigener** Geräte werden entgegengenommen und im Überblick ausgewiesen (Hardware-Auswertung bleibt App-Ebene). | ✅ Datenvertrag |
| **Anomalie (passiv)** | Plötzlicher Schwund eigener Geräte (≥ 3, ≥ 50 %) wird als Warnung gemeldet — **Detektion**, keine Gegenmaßnahmen. | ✅ aktiv |

**Endpunkte:** `POST /api/v1/premises/sensor-report` · `GET /api/v1/premises/overview` · `GET /api/v1/premises/unknown`

**Bewusst NICHT enthalten:** aktive Stör-/Angriffswerkzeuge (Deauth, Beacon-Spam,
Handshake-Capture, Rogue-AP, Reconnect-Flooding) — unabhängig vom Einsatzort.

## 2. EDM-Geräte-Lebenszyklus — `edm.py`

Für **eigene**, EDM-verwaltete Geräte (legitimer Ersatz für „FRP-Bypass"):

```
ENROLLED → PROVISIONED → QUARANTINED → RESET_PENDING → RESET
     ▲                                            │
     └────────────────────────────────────────────┘
```

- **Reset-Auftrag** (`request_reset`): erzeugt `RESET_PENDING` mit Audit-Grund.
  Die Durchführung erfolgt ausschließlich über den **Honeywell Provisioning
  Mode / OEMConfig** durch die eigene EDM-Administration — **kein
  Google-FRP-Bypass für Fremdgeräte**.
- **Jede Zustandsänderung** wird audit-logiert (Akteur, Zeit, von→nach, Grund).
- Nur die Admin-Rolle darf Zustände ändern (403 sonst); illegale Übergänge
  (z. B. Direkt-Reset aus ENROLLED) werden abgelehnt.

**Endpunkte:** `POST/GET /api/v1/edm/devices` · `POST /api/v1/edm/devices/{id}/state` · `POST /api/v1/edm/devices/{id}/reset` · `GET /api/v1/edm/audit`

## 3. Offline-Sync-Queue (Service Worker ↔ Edge-Agent)

- **Backend:** `POST /api/v1/sync/queue` (Payload ablegen, Geräte-Scope),
  `GET /api/v1/sync/next?device_id=` (Peek), `POST /api/v1/sync/{id}/ack`
  (bestätigen). Pro Gerät max. 200 Einträge.
- **Service Worker** (`sw.js`): `periodicsync`/`sync`-Handler stellen
  zwischengespeicherte Beobachtungen in die Queue ein (Best-Effort —
  primärer Pfad bleibt Android WorkManager / Edge-Agent, wie in
  docs/SERVICE_WORKER.md beschrieben).
- **Client-Helfer** (`fleet.js`): `queueMeshObservation(kind, payload)` +
  Registrierung von `periodicsync` (Fallback: einmaliger `sync`).

## 4. Flotten-Gruppen & Positionshistorie

- `POST/GET/DELETE /api/v1/fleet/groups` — Multi-Device-Organisation
  (Gruppen mit Mitgliedern, nur existierende Flotten-Geräte).
- `GET /api/v1/fleet/{id}/history` — Positionshistorie aus `spatial_memory`.
- **Interaktionsfeld (Accordion)** im OSM-Dashboard: Fähigkeiten je
  Geräteklasse, Gruppe/Quelle/Akku, Positionshistorie, Sichtbarkeit —
  `fleet.html`/`fleet.js`.

## Tests

```bash
cd edge-agent
pytest tests/test_premises.py tests/test_edm.py tests/test_premises_api.py -q
# → 15 Tests; Gesamtsuite weiterhin vollständig grün
```

## Abgrenzung (unverändert)

Nicht enthalten — unabhängig vom Betriebsgelände-Kontext — bleiben:
aktive Angriffswerkzeuge, Atemfrequenz-/Thermal-Biometrie, taktisches
Vital-/Combat-Monitoring Dritter, Personensuche, FRP-Bypass für Fremdgeräte.
