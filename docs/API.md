# 🔗 API-Spezifikation

Die vollständige OpenAPI-3.0-Spezifikation liegt unter
[`edge-agent/openapi.yaml`](../edge-agent/openapi.yaml).

## REST (Port 8080)

| Methode | Endpunkt | Zweck |
|---------|----------|-------|
| GET | `/api/v1/health` | Health-Check (Status, Modus) |
| GET | `/api/v1/agent/state` | Aktueller 6-DOF-EKF-Zustand |
| POST | `/api/v1/agent/config` | Szenario starten/konfigurieren |
| GET | `/api/v1/agent/history` | 3D-Transformations-Historie |
| POST | `/api/v1/agent/merge` | ICP-Map-Merging mehrerer CT45P |
| POST | `/api/v1/pipeline/run` | v2.0-Datenpipeline ausführen |

### Beispiel: Zustand

```bash
curl http://localhost:8080/api/v1/agent/state
```

```json
{
  "x": 0.32, "y": 0.37, "z": 0.56,
  "vx": 0.0, "vy": 0.0, "vz": 0.0,
  "covariance": [[1.0, ...], ...],
  "kalman_gain_lidar": 0.91,
  "mode": "DEGRADED"
}
```

### Beispiel: Map-Merge

```bash
curl -X POST http://localhost:8080/api/v1/agent/merge \
  -H "Content-Type: application/json" \
  -d '{"device_ids":["CT45P-01","CT45P-02"]}'
```

### Beispiel: Datenpipeline (v2.0)

```bash
curl -X POST http://localhost:8080/api/v1/pipeline/run \
  -H "Content-Type: application/json" \
  -d '{"device_id":"CT45P-01","points":[0,0,0,1,0,0,1,1,0,0,1,0,0.5,0.5,2.5]}'
```

## WebSocket (`/ws/agent/events`)

Nachrichtentypen (JSON `{type, payload}`):

| type | Richtung | Beschreibung |
|------|----------|--------------|
| `handshake` | → | `device_id` setzen |
| `lidar` | → | LiDAR-Punktwolke (→ Binary-Broadcast) |
| `mmwave` | → | mmWave-Targets |
| `ble` | → | BLE-Token-Updates |
| `uwb_phase` | → | UWB-Phase für Micro-Doppler |
| `telemetry` | → | Batterie/Temperatur/Scattering |

Binary-Ausgabe: Punktwolke `[N (uint32 LE), N*3 float32]`.

## MQTT (Port 1883)

Topic `ble/tokens/<device_id>` — JSON-Payload mit `mac`, `rssi`, `accel_*`, `battery`.
