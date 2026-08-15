# 🧭 3dxAgent — Autonomes 3D-Kartierungs- & Lageerkennungssystem

**Version:** 4.5.0-BT-Accessories · **Zielplattform:** Honeywell CT45P + Multi-Sensor Edge-Netzwerk + Bluetooth-Zubehör Ökosystem

Die **3dxAgent-Plattform** verwandelt das Industrie-Smartphone **Honeywell CT45P**
in ein hochpräzises, autonomes 3D-Kartierungs- und Lageerkennungssystem. Durch die
Fusion von **LiDAR, mmWave-Radar, UWB-Micro-Doppler, BLE-Token-Triangulation** und
**IMU** mit einem **adaptiven 6-DOF Extended Kalman Filter (EKF)** entsteht ein
digitaler Zwilling der Umgebung — inklusive Detektion von Personen und Objekten
hinter Mauern.

Die **v2.0.0-DataPipeline** ergänzt eine vollständige
**Sensor-/Netzwerkdaten-Evaluierungspipeline**:

```
Sensor-/Netzwerkdaten → Analyse → Mesh → 3D-Umgebung → Exakte Abbildung → Evaluierungsagent
```

Die Erweiterungen **v3.x–v4.5.0** ergänzen zudem:
- **Offline-Betrieb** — UWB-DFT, ICP/Kabsch, Madgwick-IMU, Trilateration und ein
  lokaler REST/WebSocket-Server direkt in Kotlin auf dem CT45P (Package `offline/`)
- **Smart Mesh Integrator** — adaptiver Octree, semantische Klassifikation
  (Person/Gegenstand/Wand/Boden), Bewegungsdetektion
- **Client-Regelwerk** — Anbindung beliebiger externer Geräte (Token, Relay,
  Sensor, Gateway, Wearable) mit Authentifizierung, Signalauswertung und Health-Check
- **Bluetooth-Zubehör Ökosystem (v4.5.0)** — 12+ Typen (Token Pro, Sensor-Tag BME280, Wearable HRM, Asset-Tag iBeacon/Eddystone, Remote, Gateway, Headset, HID, Classic SPP) mit GATT, Classic, adaptivem Scan, SOS, Bonding, Health-Monitor und universeller nRF52840 Firmware

---

## 📁 Monorepo-Struktur

```text
88P3dKart.-Art/
├── edge-agent/                # Python 3.11 + FastAPI + NumPy/SciPy (lauffähig) inkl. bluetooth_accessories.py
├── web-visualizer/            # Node.js + Three.js (lauffähig) + BT Zubehör Panel
├── android-app/               # Kotlin CT45P-App – bluetooth/ package (12 Gerätetypen, GATT, Classic, Health)
├── ble-token-firmware/        # nRF52 Zephyr – Universal Firmware (Token/Sensor/Wearable/Asset/Remote/Gateway)
├── mosquitto/                 # MQTT-Broker-Konfiguration (erweitert für bt topics)
├── nginx/                     # Reverse-Proxy (optional, HTTPS)
├── docker-compose.yml         # Orchestrierung (4 Services)
└── docs/                      # Architektur, API, Roadmap, Checkliste, BLUETOOTH_ACCESSORIES.md
```

---

## 🚀 Schnellstart

```bash
# 1) Edge-Agent lokal starten (Python)
cd edge-agent
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
uvicorn agent:app --host 0.0.0.0 --port 8080

# 2) Web-Visualizer starten (Node)
cd ../web-visualizer
npm install
node server.js            # → http://localhost:3000

# 3) Oder alles als Docker-Stack
docker compose up -d
```

```bash
# Status prüfen
curl http://localhost:8080/api/v1/agent/state

# Karten von 2 CT45P zusammenführen
curl -X POST http://localhost:8080/api/v1/agent/merge \
  -H "Content-Type: application/json" \
  -d '{"device_ids":["CT45P-01","CT45P-02"]}'

# Datenpipeline (v2.0) ausführen
curl -X POST http://localhost:8080/api/v1/pipeline/run \
  -H "Content-Type: application/json" \
  -d '{"device_id":"CT45P-01","points":[...]}'
```

---

## ✅ Tests

```bash
cd edge-agent
source .venv/bin/activate
python -m pytest tests/ -v
```

---

## 📊 Die 5 Einsatzszenarien

1. **Taktische Einsatzbesprechung** (Behörden/BOS) — 3D-Scan ohne Baupläne, Avatare, GLTF-Export
2. **Gefahren- & Evakuierungssimulation** — Rauchausbreitung, ABM, UWB-Atemdetektion
3. **Architektur & Bestandsanalyse** — LiDAR-SLAM, IFC-Export für BIM
4. **Temporäre Szenarien** — BLE-Token-Personenströme, ICP-Map-Merging
5. **Forschung & Lehre** — versionierte, wiederholbare 3D-Datensätze

Weitere Details: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md),
[`docs/EXECUTIVE_SUMMARY.md`](docs/EXECUTIVE_SUMMARY.md),
[`docs/API.md`](docs/API.md),
[`docs/ROADMAP.md`](docs/ROADMAP.md),
[`docs/CHECKLIST.md`](docs/CHECKLIST.md).

---

## 🔗 Schnittstellen

| Protokoll | Endpunkt | Zweck |
|-----------|----------|-------|
| REST (HTTPS) | `:8080/api/v1/...` | Konfiguration, Historie, Szenarien, Map-Merge, Pipeline, Bluetooth Zubehör |
| WebSocket | `:8080/ws/agent/events` | Binärer Punktwolken-Stream + JSON-Status + BT Zubehör Live |
| MQTT | `:1883` | BLE-Token + `bluetooth/accessories/#`, `sensors/#`, `wearables/#`, `events/#` |
| USB-Serial | `/dev/ttyUSB0`, `/dev/ttyACM0` | LiDAR, mmWave (CT45P Host-Modus) |
| BLE GATT | `8d81e7c0-b7c8-...` | Custom 3dx Service – Data Notify, Config Write, Command Write |
| BLE Standard | `0x180F, 0x180A, 0x181A, 0x180D` | Battery, Device Info, Env Sensing, Heart Rate |
| BT Classic SPP | `00001101-...` | RFCOMM für HC-05, Headset, HID Remote |

### 🆕 Bluetooth-Zubehör REST

```bash
curl http://localhost:8080/api/v1/bluetooth/accessories | jq
curl http://localhost:8080/api/v1/bluetooth/stats | jq
curl http://localhost:8080/api/v1/bluetooth/health | jq
curl http://localhost:8080/api/v1/bluetooth/accessories/aa:bb:cc:dd:ee:01 | jq
```

Details: [`docs/BLUETOOTH_ACCESSORIES.md`](docs/BLUETOOTH_ACCESSORIES.md)
