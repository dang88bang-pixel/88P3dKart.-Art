#!/usr/bin/env python3
"""
End-to-End-Smoke-Test für den 3dxAgent Edge-Agent.

Startet den echten uvicorn-Server (TLS deaktiviert) und prüft alle
Funktionsketten: Auth (Enrollment → Session), EKF, WebSocket-Ingest,
Mesh/Evaluation, Szenarien, Export, Alarme, Bluetooth, Geräte, Devicedb,
Metriken. Exit-Code 0 nur wenn ALLE Ketten erfolgreich sind.

Nutzung:  python3 scripts/smoke_test.py
"""
import json
import os
import subprocess
import sys
import time
import urllib.request
import urllib.error
import signal
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EDGE = ROOT / "edge-agent"

FAILURES: list[str] = []


def check(name: str, condition: bool, detail: str = "") -> None:
    if condition:
        print(f"  ✅ {name}")
    else:
        FAILURES.append(name)
        print(f"  ❌ {name}  {detail}")


def http(method: str, path: str, body: dict | None = None, token: str | None = None,
         raw: bool = False):
    url = f"http://127.0.0.1:8081{path}"
    data = json.dumps(body).encode() if body is not None else None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            payload = resp.read()
            return resp.status, payload if raw else (json.loads(payload) if payload else None)
    except urllib.error.HTTPError as e:
        body = e.read()
        try:
            return e.code, json.loads(body or b"{}")
        except json.JSONDecodeError:
            return e.code, {"detail": body[:200].decode(errors="replace")}


def main() -> int:
    env = {
        **os.environ,
        "AGENT_REQUIRE_TLS": "false",
        "AGENT_AUTH_SIGNING_SECRET": "smoke-signing-secret-0123456789abcdef",
        "AGENT_ADMIN_BOOTSTRAP_TOKEN": "admin-token-0123456789abcdef0123456789",
        "AGENT_DB_PATH": "/tmp/smoke-agent.db",
        "AGENT_AUTH_DB_PATH": "/tmp/smoke-credentials.db",
        "AGENT_ALARM_DB_PATH": "/tmp/smoke-alarms.db",
        "AGENT_GATEWAY_ID": "smoke-gateway",
        "AGENT_LOG_DIR": "/tmp/smoke-logs",
        "API_PORT": "8081",
    }
    for f in ("/tmp/smoke-agent.db", "/tmp/smoke-credentials.db", "/tmp/smoke-alarms.db"):
        Path(f).unlink(missing_ok=True)
    Path("/tmp/smoke-logs").mkdir(exist_ok=True)

    server = subprocess.Popen(
        [sys.executable, "agent.py"], cwd=EDGE, env=env,
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    try:
        # Warten bis der Server antwortet
        for _ in range(60):
            try:
                status, _ = http("GET", "/api/v1/health")
                if status == 200:
                    break
            except Exception:
                pass
            time.sleep(0.5)
        else:
            check("Server startet", False, "kein Health nach 30s")
            return 1

        print("═══ 1. Health ═══")
        status, health = http("GET", "/api/v1/health")
        check("GET /api/v1/health → 200", status == 200)
        check("Health enthält bluetooth-Stats", isinstance(health, dict) and "bluetooth" in health)

        print("═══ 2. Auth-Kette ═══")
        status, code = http("POST", "/api/v1/admin/enrollment-codes",
                            {"device_id": "CT45P-01", "ttl_seconds": 600}, token="admin-token-0123456789abcdef0123456789")
        check("Enrollment-Code (admin)", status == 200, str(code))
        enrollment = code.get("enrollment_code", "")
        status, cred = http("POST", "/api/v1/enrollment/claim",
                            {"device_id": "CT45P-01", "code": enrollment})
        check("Claim Enrollment", status == 200, str(cred))
        device_secret = cred.get("device_secret", "")
        status, session = http("POST", "/api/v1/session",
                               {"device_id": "CT45P-01", "device_secret": device_secret})
        check("Session-Token", status == 200, str(session))
        device_token = session.get("access_token", "")
        check("Token nicht leer", bool(device_token))
        status, _ = http("POST", "/api/v1/session",
                         {"device_id": "CT45P-01", "device_secret": "falsch" * 10})
        check("Falsches Secret → 401", status == 401, f"war {status}")

        print("═══ 3. EKF / History (Auth) ═══")
        status, state = http("GET", "/api/v1/agent/state", token=device_token)
        check("GET /agent/state", status == 200 and "x" in state, str(state))
        status, hist = http("GET", "/api/v1/agent/history?device_id=CT45P-01&limit=10",
                            token=device_token)
        check("GET /agent/history", status == 200 and "records" in hist, str(hist))
        status, _ = http("GET", "/api/v1/agent/history?device_id=OTHER", token=device_token)
        check("Cross-Device → 403", status == 403, f"war {status}")

        print("═══ 4. WebSocket-Ingest + Mesh/Evaluation ═══")
        import asyncio
        import websockets

        async def ws_flow():
            uri = "ws://127.0.0.1:8081/ws/agent/events"
            async with websockets.connect(
                uri, extra_headers={"Authorization": f"Bearer {device_token}"}
            ) as ws:
                await ws.send(json.dumps({"type": "handshake", "payload": {"device_id": "CT45P-01"}}))
                ack = json.loads(await ws.recv())
                check("WS handshake_ack", ack.get("type") == "handshake_ack", str(ack))
                pts = [1.0, 0.0, 0.0, 2.0, 0.0, 0.0, 1.0, 1.0, 0.0, 3.0, 1.0, 0.5]
                await ws.send(json.dumps({"type": "lidar", "payload": {
                    "device_id": "CT45P-01", "timestamp": time.time(), "points": pts}}))
                await ws.send(json.dumps({"type": "mmwave", "payload": {
                    "device_id": "CT45P-01", "timestamp": time.time(),
                    "targets": [{"x": 1.5, "y": 0.2, "z": 0.0}]}}))
                await asyncio.sleep(0.5)
        asyncio.run(ws_flow())

        status, mesh = http("GET", "/api/v1/agent/mesh?device_id=CT45P-01", token=device_token)
        check("GET /agent/mesh (JSON)", status == 200 and mesh.get("count", 0) >= 1, str(mesh)[:120])
        check("Mesh bounds vorhanden", "bounds" in mesh)
        status, obj = http("GET", "/api/v1/agent/mesh?device_id=CT45P-01&format=obj",
                           token=device_token, raw=True)
        check("Mesh als OBJ", status == 200 and b"o 3dxagent" in obj)
        status, ply = http("GET", "/api/v1/agent/mesh?device_id=CT45P-01&format=ply",
                           token=device_token, raw=True)
        check("Mesh als PLY", status == 200 and b"element vertex" in ply)
        status, stl = http("GET", "/api/v1/agent/mesh?device_id=CT45P-01&format=stl",
                           token=device_token, raw=True)
        check("Mesh als STL", status == 200 and b"endsolid" in stl)
        status, _ = http("GET", "/api/v1/agent/mesh?device_id=CT45P-01&format=glb",
                         token=device_token)
        check("glb → 501 (ehrlich)", status == 501, f"war {status}")
        status, ev = http("GET", "/api/v1/agent/evaluation?device_id=CT45P-01", token=device_token)
        check("GET /agent/evaluation", status == 200 and ev.get("status") == "ready", str(ev)[:120])
        check("Evaluation enthält confidence", "confidence" in ev)

        print("═══ 5. Szenarien ═══")
        status, scn = http("POST", "/api/v1/agent/scenario/start",
                           {"type": "architecture", "params": {"floor": 1}}, token="admin-token-0123456789abcdef0123456789")
        check("Scenario start", status == 200 and scn.get("status") == "running", str(scn))
        scn_id = scn.get("scenario_id", "")
        status, stop = http("POST", "/api/v1/agent/scenario/stop",
                            {"scenario_id": scn_id}, token="admin-token-0123456789abcdef0123456789")
        check("Scenario stop", status == 200 and stop.get("status") == "stopped", str(stop))
        check("Scenario-Dauer gesetzt", "duration_seconds" in stop)
        status, _ = http("POST", "/api/v1/agent/scenario/stop",
                         {"scenario_id": "unbekannt"}, token="admin-token-0123456789abcdef0123456789")
        check("Unbekanntes Szenario → 404", status == 404, f"war {status}")

        print("═══ 6. Pipeline + Export ═══")
        status, run = http("POST", "/api/v1/pipeline/run",
                           {"device_id": "CT45P-01", "points": [1, 0, 0, 2, 0, 0, 1, 1, 0, 3, 1, 0.5]},
                           token=device_token)
        check("Pipeline run", status == 200 and run.get("status") == "ready", str(run)[:100])
        for fmt, needle in (("geojson", "FeatureCollection"), ("kml", "<kml"), ("json", "annotations")):
            status, payload = http("POST", "/api/v1/export",
                                   {"format": fmt, "device_id": "CT45P-01",
                                    "annotations": [{"name": "T1", "lat": 52.5, "lon": 13.4}]},
                                   token=device_token)
            check(f"Export {fmt}", status == 200 and (needle in str(payload)), str(payload)[:80])

        print("═══ 7. Alarme (Policy → Evidence → Runtime) ═══")
        policy = {
            "schema_version": "1.0.0",
            "policy_id": "123e4567-e89b-12d3-a456-426614174000",
            "asset_id": "CT45P-01", "revision": 1, "enabled": True,
            "metric": "RANGE_FROM_CT45P", "threshold_m": 10.0,
            "trigger_direction": "ABOVE", "decision_mode": "CONFIRMED_BREACH",
            "minimum_confidence": 0.8, "maximum_age_ms": 10000,
            "dwell_ms": 0, "clear_dwell_ms": 0, "data_loss_dwell_ms": 500,
            "recovery_dwell_ms": 0, "hysteresis_m": 1.0, "cooldown_ms": 0,
            "severity": "WARNING", "data_loss_behavior": "SEPARATE_ALARM",
            "delivery_profile_id": "operators",
        }
        status, created = http("POST", "/api/v1/alarm/policies", policy, token="admin-token-0123456789abcdef0123456789")
        check("Policy anlegen", status == 200 and created.get("authority") == "GATEWAY_AUTHORITATIVE", str(created))
        import datetime as _dt
        evidence = {
            "policy_id": policy["policy_id"], "asset_id": "CT45P-01",
            "source_id": "fusion", "cursor": "c1", "estimate_status": "VALID",
            "method": "calibrated-fusion-v1", "value_m": 12.0, "confidence": 0.9,
            "lower_95_m": 11.0, "upper_95_m": 13.0,
            "observed_at": _dt.datetime.now(_dt.timezone.utc).isoformat(),
            "source_ids": ["lidar-1"], "measurement_ids": ["m1"],
            "calibration_id": "cal-1", "quality_flags": [],
        }
        status, ing = http("POST", "/api/v1/alarm/evidence", evidence, token=device_token)
        check("Evidence → ACTIVE", status == 200 and ing.get("condition") == "ACTIVE", str(ing))
        status, rt = http("GET", "/api/v1/alarm/runtime?policy_id=123e4567-e89b-12d3-a456-426614174000&asset_id=CT45P-01",
                          token=device_token)
        check("Runtime", status == 200 and rt.get("condition") == "ACTIVE", str(rt))

        print("═══ 8. Bluetooth + Geräte + Devicedb ═══")
        status, upd = http("POST", "/api/v1/bluetooth/accessories/update",
                           {"device_id": "CT45P-01", "timestamp": time.time(),
                            "accessories": [{"mac": "aa:bb:cc:dd:ee:01", "type": "SENSOR_TAG", "rssi": -55, "battery": 88}]},
                           token=device_token)
        check("BT Update", status == 200 and upd.get("updated") == 1, str(upd))
        status, lst = http("GET", "/api/v1/bluetooth/accessories", token=device_token)
        check("BT Liste", status == 200 and lst.get("count") == 1, str(lst))
        status, dev = http("POST", "/api/v1/devices/upsert",
                           {"device": {"id": "d1", "name": "Sensor-A", "category": "sensor",
                                       "capabilities": ["STATUS"], "position": [1, 2, 3]}},
                           token=device_token)
        check("Device Upsert", status == 200, str(dev))
        status, devs = http("GET", "/api/v1/devices", token=device_token)
        check("Device Liste", status == 200 and len(devs.get("devices", [])) >= 1, str(devs)[:100])
        status, dbstatus = http("GET", "/api/v1/devicedb/status", token=device_token)
        check("Devicedb Status", status == 200 and "records" in dbstatus, str(dbstatus)[:100])

        print("═══ 9. Metriken ═══")
        status, metrics_raw = http("GET", "/api/v1/metrics", raw=True)
        metrics_text = metrics_raw.decode() if isinstance(metrics_raw, bytes) else str(metrics_raw)
        check("Metrics 200", status == 200)
        check("Counter pipeline_runs ≥ 1", "3dxagent_pipeline_runs_total 1" in metrics_text)
        check("Counter lidar_frames ≥ 1", "3dxagent_lidar_frames_total 1" in metrics_text)

        print("═══ 10. Transport-Härtung ═══")
        # TLS-Pflicht ist pro Konfiguration; hier deaktiviert. Origin-Filter testen:
        status, _ = http("GET", "/api/v1/agent/state", token=device_token)
        check("State ohne Origin-Header erreichbar (WS-Browser irrelevant)", status == 200)
    finally:
        server.send_signal(signal.SIGTERM)
        try:
            server.wait(timeout=10)
        except subprocess.TimeoutExpired:
            server.kill()

    if FAILURES:
        print(f"\n❌ {len(FAILURES)} Fehler: {', '.join(FAILURES)}")
        return 1
    print("\n🎉 ALLE FUNKTIONSKETTEN ERFOLGREICH DURCHLAUFEN")
    return 0


if __name__ == "__main__":
    sys.exit(main())
