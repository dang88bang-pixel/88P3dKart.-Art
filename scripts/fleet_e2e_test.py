#!/usr/bin/env python3
"""End-to-End-Test für das Flotten-Feature: Agent + Visualizer live,
Flotten-Daten seeden, OSM-Dashboard-Assets und Aktionen über den Proxy prüfen."""
import json
import os
import subprocess
import sys
import time
import urllib.request
import urllib.error
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EDGE = ROOT / "edge-agent"
VIZ = ROOT / "web-visualizer"

ADMIN = "admin-token-0123456789abcdef0123456789"
FAILURES: list[str] = []


def check(name, condition, detail=""):
    if condition:
        print(f"  ✅ {name}")
    else:
        FAILURES.append(name)
        print(f"  ❌ {name}  {detail}")


def http(method, path, body=None, token=None, base="http://127.0.0.1:8080"):
    data = json.dumps(body).encode() if body is not None else None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(base + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            payload = resp.read()
            return resp.status, json.loads(payload) if payload else None
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read() or b"{}")
        except json.JSONDecodeError:
            return e.code, {"detail": "non-json"}


def main() -> int:
    env = {
        **os.environ,
        "AGENT_REQUIRE_TLS": "false",
        "AGENT_AUTH_SIGNING_SECRET": "fleet-signing-secret-0123456789abcdef",
        "AGENT_ADMIN_BOOTSTRAP_TOKEN": ADMIN,
        "AGENT_DB_PATH": "/tmp/fleet-agent.db",
        "AGENT_AUTH_DB_PATH": "/tmp/fleet-credentials.db",
        "AGENT_ALARM_DB_PATH": "/tmp/fleet-alarms.db",
        "AGENT_GATEWAY_ID": "fleet-gateway",
        "AGENT_LOG_DIR": "/tmp/fleet-logs",
        "API_PORT": "8080",
    }
    for f in ("/tmp/fleet-agent.db", "/tmp/fleet-credentials.db", "/tmp/fleet-alarms.db"):
        Path(f).unlink(missing_ok=True)

    agent = subprocess.Popen([sys.executable, "agent.py"], cwd=EDGE, env=env,
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    viz = None
    try:
        for _ in range(60):
            try:
                if http("GET", "/api/v1/health")[0] == 200:
                    break
            except Exception:
                pass
            time.sleep(0.5)
        else:
            check("Agent startet", False)
            return 1

        print("═══ 1. Visualizer-Token (Enrollment → Session) ═══")
        _, code = http("POST", "/api/v1/admin/enrollment-codes",
                       {"device_id": "visualizer-1", "ttl_seconds": 600}, token=ADMIN)
        _, cred = http("POST", "/api/v1/enrollment/claim",
                       {"device_id": "visualizer-1", "code": code["enrollment_code"]})
        _, session = http("POST", "/api/v1/session",
                          {"device_id": "visualizer-1", "device_secret": cred["device_secret"]})
        token = session["access_token"]
        check("Session-Token für Visualizer", bool(token))

        print("═══ 2. Flotte seeden (Anchor + GPS + BLE + Triangulation) ═══")
        _, _ = http("POST", "/api/v1/fleet/anchor",
                    {"lat": 52.5163, "lon": 13.3777, "heading_deg": 0.0}, token=ADMIN)
        _, ups = http("POST", "/api/v1/fleet/upsert", {
            "device_id": "visualizer-1", "timestamp": time.time(),
            "vehicles": [
                {"id": "eb-1", "name": "Flotten-Bike 1", "kind": "ebike",
                 "lat": 52.5200, "lon": 13.3900, "accuracy_m": 4.0, "battery": 85},
                {"id": "sc-2", "name": "Scooter 2", "kind": "escooter",
                 "lat": 52.5130, "lon": 13.3800, "accuracy_m": 6.0, "battery": 18},
                {"id": "tok-3", "name": "Werkzeug-Token", "kind": "ble_token", "rssi": -60},
                {"id": "sc-4", "name": "Scooter lokal", "kind": "escooter",
                 "local": [30.0, 0.0, 0.0], "accuracy_m": 2.0, "battery": 70},
            ],
        }, token=token)
        check("Upsert 4 Fahrzeuge", ups["updated"] == 4, str(ups)[:100])
        check("Triangulation projiziert (local→GPS)",
              any(v["id"] == "sc-4" and v["lat"] is not None for v in ups["vehicles"]))

        _, fl = http("GET", "/api/v1/fleet", token=token)
        check("GET /fleet liefert 4", fl["count"] == 4)
        check("Stats by_kind", fl["stats"]["by_kind"]["ebike"] == 1 and fl["stats"]["by_kind"]["escooter"] == 2)
        check("Niedriger Akku erkannt", fl["stats"]["low_battery"] == 1)

        _, nb = http("GET", "/api/v1/fleet/nearby?lat=52.5163&lon=13.3777&radius_m=3000", token=token)
        check("Nearby ≥ 2 Einträge", nb["count"] >= 2, str(nb)[:100])

        _, act = http("POST", "/api/v1/fleet/eb-1/action", {"action": "lock"}, token=token)
        check("Aktion lock (E-Bike)", act["success"] is True, str(act)[:100])
        code, act = http("POST", "/api/v1/fleet/tok-3/action", {"action": "lock"}, token=token)
        check("Capability-Gate: Token darf nicht sperren", code == 403, str(act)[:80])

        print("═══ 3. Web-Visualizer mit Flotten-Dashboard ═══")
        viz_env = {
            **os.environ,
            "PORT": "3001",
            "AGENT_REST_URL": "http://localhost:8080",
            "AGENT_WS_URL": "ws://localhost:8080/ws/agent/events",
            "AGENT_TOKEN": token,
        }
        viz = subprocess.Popen(["node", "server.js"], cwd=VIZ, env=viz_env,
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for _ in range(40):
            try:
                with urllib.request.urlopen("http://127.0.0.1:3001/health", timeout=2) as r:
                    if r.status == 200:
                        break
            except Exception:
                pass
            time.sleep(0.5)

        def vhttp(path):
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:3001{path}", timeout=10) as r:
                    return r.status, r.read()
            except urllib.error.HTTPError as e:
                return e.code, e.read()

        status, body = vhttp("/fleet.html")
        check("Dashboard-Seite /fleet.html", status == 200 and b"Flotten-Live-Dashboard" in body)
        status, body = vhttp("/fleet.js")
        check("fleet.js ausgeliefert", status == 200 and b"fleet_update" in body)
        status, body = vhttp("/vendor/leaflet/leaflet.js")
        check("Leaflet lokal gebündelt", status == 200 and b"Leaflet" in body)
        status, body = vhttp("/api/v1/fleet")
        check("REST-Proxy mit AGENT_TOKEN", status == 200 and b'"eb-1"' in body)
        status, body = vhttp("/api/v1/fleet/nearby?lat=52.5163&lon=13.3777&radius_m=3000")
        check("Nearby über Proxy", status == 200 and b"entries" in body)

        print("═══ 4. WebSocket-Proxy (fleet_update an Browser) ═══")
        ws_script = f"""
const WebSocket = require('ws');
const ws = new WebSocket('ws://127.0.0.1:3001/ws');
let got = false;
const t = setTimeout(() => {{ console.log(got ? 'WS_OK' : 'WS_TIMEOUT'); process.exit(got ? 0 : 1); }}, 6000);
ws.on('message', (d) => {{
  try {{ const m = JSON.parse(d); if (m.type === 'fleet_update') {{ got = true; clearTimeout(t); console.log('WS_OK'); ws.close(); process.exit(0); }} }} catch (e) {{}}
}});
ws.on('open', () => setTimeout(() => {{
  require('http').request({{host:'127.0.0.1', port:8080, path:'/api/v1/fleet/upsert', method:'POST',
    headers: {{'Content-Type':'application/json', 'Authorization': 'Bearer {token}'}}}},
    (res) => res.resume()).end(JSON.stringify({{device_id:'visualizer-1', timestamp: Date.now()/1000,
    vehicles: [{{id:'eb-1', name:'Flotten-Bike 1', kind:'ebike', lat:52.5201, lon:13.3901, accuracy_m:4.0, battery:84}}]}}));
}}, 500));
"""
        ws_probe = subprocess.run(
            ["node", "-e", ws_script],
            cwd=VIZ, capture_output=True, text=True, timeout=30,
        )
        check("WS-Proxy liefert fleet_update", "WS_OK" in (ws_probe.stdout or ""), ws_probe.stderr[:200])
    finally:
        if viz:
            viz.terminate()
        agent.terminate()
        for p in (viz, agent):
            if p:
                try:
                    p.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    p.kill()

    if FAILURES:
        print(f"\n❌ {len(FAILURES)} Fehler: {', '.join(FAILURES)}")
        return 1
    print("\n🎉 FLOTTEN-FEATURE END-TO-END ERFOLGREICH")
    return 0


if __name__ == "__main__":
    sys.exit(main())
