# 88P3dKart.-Art — WebSocket-API (Ist-Stand, generiert aus `agent.py`)

**Endpunkt:** `/ws/agent/events` (wss über nginx, Port 443)
**Authentifizierung:** Header `Authorization: Bearer <JWT>` (JWT aus `POST /api/v1/session`)
**Envelope:** jede Nachricht ist `{"type": <string>, "payload": <object>}` — andere Formen werden mit `{"type":"error","code":"INVALID_MESSAGE"}` beantwortet.

## Client → Server

| type | payload (Pflichtfelder) | Wirkung |
|---|---|---|
| `handshake` | `device_id` | Bestätigung `handshake_ack`; setzt den Geräte-Scope der Verbindung |
| `lidar` | `device_id`, `timestamp`, `points` (flache x/y/z-Liste), optional `scattering_detected` | EKF-Update + binäre Punktwolke an alle autorisierten Clients (komprimiert) |
| `mmwave` | `device_id`, `timestamp`, `targets` (Liste mit x/y/z) | EKF-Update aus mmWave-Ziel |
| `ble` | `device_id`, `timestamp`, `tokens` | BLE-Token-Ingest (Logging/Zählung) |
| `uwb_phase` | `device_id`, `timestamp`, `phase` | Phasen-Feed für den UWB-Doppler-Prozessor |
| `telemetry` | `device_id`, `thermal_c`, `thermal_source`, `scattering` | adaptiver EKF-Modus (FULL/DEGRADED/MINIMAL) |
| `aura_voxels` | `device_id` (+ RTI-Voxel) | Weiterleitung an alle Visualizer |
| `aura_heatmap` | `device_id` (+ Heatmap) | Weiterleitung an alle Visualizer |
| `position_update` | `device_id`, `x`, `y`, `z`, optional `accuracy_m`, `source`, `confidence` | fusionierte Position → Broadcast + Persistenz |
| `triangulation_anchors` | `device_id` (+ Anker) | Weiterleitung an alle Visualizer |
| `network_devices_update` | `device_id`, `devices` | Change-/Anomalie-Erkennung → Broadcast |
| `annotation_update` | `device_id` (+ Annotation) | kollaborative Live-Sync |
| `devices_update` | `device_id`, `devices` | DeviceRegistry-Upsert (Merge-Semantik) → Broadcast |
| `device_action` | `device_id`, `device_id` (Ziel), `action`, `params` | Capability-geprüfte Aktion → `device_action_result` |
| `network_traffic` | `device_id`, `flows` | Live-Traffic-Ingest → Broadcast |

**Fehler/Abbruch-Codes (Close):** `4400` zu große Nachricht · `4401` Auth fehlt/ungültig · `4403` TLS/Origin/Geräte-Scope verweigert · `4429` zu viele Auth-Versuche · `1013` Auth-Service nicht verfügbar.

**Scope-Regel:** Ein Geräte-Token darf nur `payload.device_id == eigene ID` senden; Verstöße schließen die Verbindung (4403). Admin-Token: alle Geräte.

## Server → Client

| type | Inhalt |
|---|---|
| `handshake_ack` | `{device_id}` |
| (binär) | komprimierte Punktwolke nach jedem `lidar`-Frame (zlib-komprimiertes Float-Array) |
| `alarm_event` | autoritative Alarm-Events (Policy/Trigger/Acknowledge/…) — Outbox-durabel |
| `network_devices` | Änderungs-/Anomalie-Ergebnisse |
| `devices_update` | Geräte-Registry (Geräte + Layer) |
| `device_action_result` | Ergebnis einer Geräteaktion |
| `network_traffic_update` | Flüsse + Aktivitäts-Aggregation + Heatmap-Säulen |
| `error` | `{code: "INVALID_MESSAGE"}` bei ungültigem Envelope |

> Hinweis: Die früher dokumentierten Typen `subscribe`/`unsubscribe`/`ping`/`pong` existieren im Ist-Code nicht — der Server sendet ohne Abonnement alle für die Rolle sichtbaren Events (Push-Modell).
