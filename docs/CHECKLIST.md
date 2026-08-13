# ✅ 3dxAgent — Step-by-Step Build & Verification Checklist

**Version:** 2.0.0-DataPipeline · **Datum:** 13. August 2026
**Repository:** `dang88bang-pixel/88P3dKart.-Art` · **Branch:** `arena/019ff93c-88p3dkart-art`

**Legende:** ✅ = erledigt & verifiziert · 🧩 = geliefert (benötigt externe Hardware/SDK) ·
⚠️ = offen

---

## 🗂️ Schritt 0 — Monorepo-Grundstruktur

| # | Aufgabe | Status |
|---|---------|--------|
| 0.1 | Verzeichnisstruktur (`edge-agent/`, `web-visualizer/`, `android-app/`, `ble-token-firmware/`, `docs/`) | ✅ |
| 0.2 | `.gitignore` (venv, node_modules, __pycache__, build, *.db, .env) | ✅ |
| 0.3 | `README.md` mit Ein-Befehl-Start und Architekturübersicht | ✅ |

## 🐍 Schritt 1 — Edge-Agent (Python, lauffähig)

| # | Aufgabe | Status |
|---|---------|--------|
| 1.1 | `config.py` | ✅ |
| 1.2 | `models.py` | ✅ |
| 1.3 | `ekf_fusion.py` | ✅ |
| 1.4 | `uwb_processor.py` | ✅ |
| 1.5 | `pointcloud_compressor.py` | ✅ |
| 1.6 | `database.py` | ✅ |
| 1.7 | `icp_merger.py` | ✅ |
| 1.8 | `pipeline.py` (**NEU v2.0**) | ✅ |
| 1.9 | `mqtt_bridge.py` | ✅ |
| 1.10 | `agent.py` | ✅ |
| 1.11 | `requirements.txt`, `Dockerfile`, `openapi.yaml` | ✅ |
| 1.12 | **Test**: `pip install` aller Abhängigkeiten | ✅ |
| 1.13 | **Test**: Unit-Tests grün (18/18) | ✅ |
| 1.14 | **Test**: Server + REST-Endpunkte (health, state, pipeline, merge, history) | ✅ |
| 1.15 | **Test**: WebSocket Binär-/JSON-Stream + Telemetrie + Persistenz | ✅ |

## 🌐 Schritt 2 — Web-Visualizer (Node/Three.js, lauffähig)

| # | Aufgabe | Status |
|---|---------|--------|
| 2.1 | `server.js` (Express + WS-Proxy) | ✅ |
| 2.2 | `public/index.html`, `styles.css`, `main.js` | ✅ |
| 2.3 | `package.json`, `Dockerfile` | ✅ |
| 2.4 | **Test**: `npm install` + Server liefert Assets | ✅ |
| 2.5 | **Test**: WebSocket-Proxy-Chain (Browser→Viz→Agent, Binary) | ✅ |

## 🐳 Schritt 3 — Orchestrierung

| # | Aufgabe | Status |
|---|---------|--------|
| 3.1 | `docker-compose.yml` (edge-agent, web-visualizer, mosquitto, nginx) | ✅ |
| 3.2 | `mosquitto/config/mosquitto.conf` | ✅ |
| 3.3 | `nginx/nginx.conf` | ✅ |
| 3.4 | **Test**: YAML syntaktisch validiert | ✅ |
| 3.5 | **Test**: `docker compose up` | ⚠️ Docker in dieser Sandbox nicht verfügbar (Dateien validiert) |

## 📱 Schritt 4 — Android-App (Kotlin, Scaffolding)

| # | Aufgabe | Status |
|---|---------|--------|
| 4.1 | Gradle + Manifest | ✅ |
| 4.2 | Sensoren (Serial, BLE, EKF, UWB, IMU) | ✅ |
| 4.3 | Storage (Room WAL) | ✅ |
| 4.4 | Netzwerk (WS-Client, REST, Models) | ✅ |
| 4.5 | UI (3D, Map, Scenario) | ✅ |
| 4.6 | **NEU v2.0** Pipeline (6 Stufen) | ✅ |
| 4.7 | `MainActivity` | ✅ |
| 4.8 | **Build**: mit Android Studio/CT45P-SDK | 🧩 benötigt Android SDK |

## 📡 Schritt 5 — BLE-Token-Firmware (nRF52 Zephyr)

| # | Aufgabe | Status |
|---|---------|--------|
| 5.1 | `prj.conf`, `CMakeLists.txt`, `main.c` | ✅ |
| 5.2 | **Build**: mit Zephyr-Toolchain | 🧩 benötigt nRF52-Toolchain |

## 🚀 Schritt 6 — GitHub-Publikation

| # | Aufgabe | Status |
|---|---------|--------|
| 6.1 | Alle Dateien committen | ✅ |
| 6.2 | Push auf `arena/019ff93c-88p3dkart-art` | ✅ |
| 6.3 | Pull Request öffnen | ✅ |
