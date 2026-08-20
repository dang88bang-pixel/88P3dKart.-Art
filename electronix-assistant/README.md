# ELECTRONIX-ASSISTANT (EXA)

Lokale Desktop-/Web-App für Bauteilerkennung, Schaltplan-Export, Thermik-Abschätzung
und **universellen G-Code (Marlin / GRBL)**.

**Nicht enthalten:** ESP32-Flash, Snapmaker-APIs, Cloud-Zwang.

## Start

```bash
cd electronix-assistant
python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8088
```

UI: `/` · Health: `/api/health`

## Grenzen des MVP

- Vision ist ein Hash-/Heuristik-Platzhalter (YOLOv8/Llava lokal nachrüstbar).
- FEM ist ein 1D-Wärmemodell, kein CalculiX.
- Keine WiGle-/Google-Geolocation-Tracking-APIs (Datenschutz).
