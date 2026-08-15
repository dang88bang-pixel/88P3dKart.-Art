"""Edge-Agent: FastAPI + WebSocket + EKF/UWB/ICP/Pipeline-Integration."""
import asyncio
import json
import logging
import os
import time

import numpy as np
import uvicorn
from contextlib import asynccontextmanager
from fastapi import FastAPI, HTTPException, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware

from config import CONFIG
from database import LocalVectorStore
from ekf_fusion import AdaptiveEKF
from icp_merger import ICPMerger
from external.manager import ExternalEntityManager
from geo.resolver import GeoResolver
from models import (
    BleTokenUpdate,
    EkfState,
    ExternalEntitySnapshot,
    GeoAnchor,
    GeoAnchorRequest,
    GeoFix,
    GeolocateRequest,
    LidarFrame,
    MergeRequest,
    MmwaveTarget,
    PipelineRequest,
    ScenarioConfig,
    UwbPhaseData,
    accuracy_to_quality,
)
from pipeline import DataPipeline
from pointcloud_compressor import PointCloudCompressor
from uwb_processor import UwbDopplerProcessor

logging.basicConfig(
    level=logging.INFO,
    format='{"timestamp":"%(asctime)s","level":"%(levelname)s","message":"%(message)s"}',
)
logger = logging.getLogger("edge-agent")

# ─── Globale Instanzen ──────────────────────────────────────
db = LocalVectorStore()
ekf = AdaptiveEKF(dt=1.0 / CONFIG.LOOP_HZ)
uwb_processor = UwbDopplerProcessor(fs=CONFIG.UWB_FS, buffer_secs=CONFIG.UWB_BUFFER_SECS)
pipeline = DataPipeline()
geo_resolver = GeoResolver()
external_manager = ExternalEntityManager(geo_resolver)

current_mode = "FULL"
scattering_detected = False
thermal_celsius = 45.0

_loop: asyncio.AbstractEventLoop | None = None


# ─── WebSocket-Verwaltung ────────────────────────────────────
class ConnectionManager:
    def __init__(self):
        self.active_connections: list[WebSocket] = []

    async def connect(self, websocket: WebSocket):
        await websocket.accept()
        self.active_connections.append(websocket)

    def disconnect(self, websocket: WebSocket):
        if websocket in self.active_connections:
            self.active_connections.remove(websocket)

    async def broadcast_binary(self, data: bytes):
        for conn in list(self.active_connections):
            try:
                await conn.send_bytes(data)
            except Exception:
                pass

    async def broadcast_json(self, data: dict):
        text = json.dumps(data)
        for conn in list(self.active_connections):
            try:
                await conn.send_text(text)
            except Exception:
                pass

    def broadcast_json_sync(self, data: dict):
        """Thread-sicherer Broadcast (für MQTT-Thread)."""
        if _loop is not None and _loop.is_running():
            asyncio.run_coroutine_threadsafe(self.broadcast_json(data), _loop)


manager = ConnectionManager()


# ─── FastAPI App ─────────────────────────────────────────────
@asynccontextmanager
async def lifespan(app: FastAPI):
    global _loop
    _loop = asyncio.get_running_loop()
    logger.info("Edge-Agent gestartet. Loop: %.1f Hz", CONFIG.LOOP_HZ)

    from mqtt_bridge import MqttBleBridge

    mqtt_bridge = MqttBleBridge(
        broker_host=CONFIG.MQTT_HOST, port=CONFIG.MQTT_PORT, websocket_manager=manager
    )
    mqtt_bridge.start()
    app.state.mqtt_bridge = mqtt_bridge

    # Externe Tracking-Feeds: Broadcast über den bestehenden WS-Kanal,
    # kein zweiter Endpunkt (docs/API_INTEGRATION_REVIEW.md, W3)
    async def _broadcast_entities(snapshot: ExternalEntitySnapshot) -> None:
        payload = snapshot.model_dump()
        await manager.broadcast_json({"type": "external_entities", "payload": payload})
        mqtt_bridge.publish_json("3dxagent/external/entities", payload)

    external_manager.set_update_callback(_broadcast_entities)
    external_manager.start()

    # Retention war implementiert, wurde aber nie ausgeführt. Ohne diesen
    # Task verfällt kein Fix — die von den Provider-ToS erzwungene
    # 30-Tage-Grenze (Google) wäre damit verletzt, siehe docs/LICENSES.md.
    async def _retention_loop() -> None:
        while True:
            try:
                await asyncio.sleep(CONFIG.RETENTION_INTERVAL_S)
                expired = await asyncio.to_thread(db.purge_expired_geo)
                aged = await asyncio.to_thread(db.enforce_retention)
                if expired or aged:
                    logger.info(
                        "Retention: %d abgelaufene Geo-Fixes, %d Altdatensätze entfernt",
                        expired,
                        aged,
                    )
            except asyncio.CancelledError:
                raise
            except Exception:
                # Ein Datenbankfehler darf den Agenten nicht beenden
                logger.exception("Retention-Durchlauf fehlgeschlagen")

    retention_task = asyncio.create_task(_retention_loop())

    yield
    logger.info("Edge-Agent wird heruntergefahren...")
    retention_task.cancel()
    try:
        await retention_task
    except asyncio.CancelledError:
        pass
    await external_manager.stop()
    await geo_resolver.aclose()


app = FastAPI(title="3dxAgent Edge-Agent", version="2.0.0", lifespan=lifespan)
app.add_middleware(
    CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"]
)


# ─── REST-Endpunkte ──────────────────────────────────────────
@app.get("/api/v1/agent/state", response_model=EkfState)
async def get_state():
    state = ekf.get_state()
    return EkfState(
        x=state.x,
        y=state.y,
        z=state.z,
        vx=state.vx,
        vy=state.vy,
        vz=state.vz,
        covariance=ekf.P.tolist(),
        kalman_gain_lidar=ekf.get_kalman_gain_lidar(),
        mode=current_mode,
    )


@app.post("/api/v1/agent/config")
async def update_config(scenario: ScenarioConfig):
    logger.info("Szenario gestartet: %s mit Parametern %s", scenario.type, scenario.params)
    return {"status": "ok", "scenario": scenario.type}


@app.get("/api/v1/agent/history")
async def get_history(device_id: str = "CT45P-01", limit: int = 100):
    records = db.get_latest(device_id, limit)
    return {"device_id": device_id, "records": records}


@app.post("/api/v1/agent/merge")
async def merge_maps(request: MergeRequest):
    device_ids = request.device_ids
    reference_id = request.reference or (device_ids[0] if device_ids else None)

    if len(device_ids) < 2:
        raise HTTPException(status_code=400, detail="Mindestens 2 Geräte benötigt")

    all_points = {}
    for dev_id in device_ids:
        points = db.get_all_points(dev_id, limit=1000)
        if points:
            all_points[dev_id] = np.asarray(points, dtype=float)

    if len(all_points) < 2:
        raise HTTPException(status_code=404, detail="Nicht genügend Daten gefunden")

    if reference_id not in all_points:
        reference_id = next(iter(all_points))
    ref_points = all_points[reference_id]

    merged = ref_points.copy()
    for dev_id, pts in all_points.items():
        if dev_id == reference_id or len(pts) < 10:
            continue
        transformed, _, _ = ICPMerger.icp(pts, ref_points, max_iterations=30)
        merged = np.vstack([merged, transformed])

    db.save_merged_map("merged", merged.tolist())
    return {
        "status": "merged",
        "total_points": int(len(merged)),
        "reference": reference_id,
        "devices": list(all_points.keys()),
    }


@app.post("/api/v1/pipeline/run")
async def run_pipeline(request: PipelineRequest):
    result = pipeline.run(
        request.points,
        source=request.metadata.get("source", "lidar"),
        quality=request.metadata.get("quality", 1.0),
        metadata=request.metadata,
    )
    result["device_id"] = request.device_id
    return result


# ─── Georeferenzierung ───────────────────────────────────────
@app.post("/api/v1/geolocate", response_model=GeoFix)
async def geolocate(request: GeolocateRequest):
    """Netzwerkbasierte Ortung über die konfigurierte Provider-Kaskade.

    Standardmässig sind nur Offline-Provider aktiv (GEO_OFFLINE_ONLY=true) —
    ohne lokalen Datenbestand liefert der Endpunkt daher bewusst 404.
    """
    if not CONFIG.GEO_ENABLED:
        raise HTTPException(status_code=503, detail="Geolokalisierung deaktiviert")
    if request.is_empty():
        raise HTTPException(
            status_code=400, detail="Keine Scan-Daten (WLAN/Zelle/BLE) übermittelt"
        )

    fix = await geo_resolver.locate(request)
    if fix is None:
        raise HTTPException(
            status_code=404,
            detail="Kein Fix oberhalb der Qualitätsschwelle ermittelbar",
        )

    db.save_geo_fix(
        lat=fix.lat,
        lon=fix.lon,
        accuracy_m=fix.accuracy_m,
        source=fix.source,
        license=fix.license,
        quality=fix.quality,
        ttl_days=fix.ttl_days,
    )
    await manager.broadcast_json({"type": "geo_fix", "payload": fix.model_dump()})
    return fix


@app.get("/api/v1/geo/providers")
async def geo_providers():
    return {
        "enabled": CONFIG.GEO_ENABLED,
        "offline_only": CONFIG.GEO_OFFLINE_ONLY,
        "min_quality": CONFIG.GEO_MIN_QUALITY,
        "providers": geo_resolver.describe_providers(),
    }


@app.get("/api/v1/geo/audit")
async def geo_audit(limit: int = 50):
    """Nachvollziehbarkeit: welcher Provider wurde wann mit welchem Ergebnis
    befragt. Grundlage für das Verarbeitungsverzeichnis."""
    return {"entries": geo_resolver.audit_log[-limit:]}


@app.post("/api/v1/geo/anchor", response_model=GeoAnchor)
async def set_geo_anchor(request: GeoAnchorRequest):
    """Setzt die Verknüpfung lokaler Frame <-> WGS84.

    Ohne Anker ist keine externe Entität in der lokalen Szene platzierbar.
    """
    fix = GeoFix(
        lat=request.lat,
        lon=request.lon,
        accuracy_m=request.accuracy_m,
        altitude_m=request.altitude_m,
        source=request.source,
        license=request.license,
        timestamp=time.time(),
        quality=accuracy_to_quality(request.accuracy_m),
    )
    anchor = GeoAnchor(
        fix=fix,
        local_origin=request.local_origin,
        heading_deg=request.heading_deg,
        frame_id=request.frame_id,
    )
    geo_resolver.set_anchor(anchor)
    await manager.broadcast_json(
        {"type": "geo_anchor", "payload": anchor.model_dump()}
    )
    return anchor


@app.get("/api/v1/geo/anchor")
async def get_geo_anchor():
    anchor = geo_resolver.anchor
    if anchor is None:
        raise HTTPException(status_code=404, detail="Kein GeoAnchor gesetzt")
    return anchor


@app.delete("/api/v1/geo/anchor")
async def delete_geo_anchor():
    geo_resolver.clear_anchor()
    return {"status": "cleared"}


# ─── Externe Tracking-Feeds ──────────────────────────────────
@app.get("/api/v1/external/entities", response_model=ExternalEntitySnapshot)
async def external_entities():
    """Aktueller Stand aller externen Entitäten — projiziert und gefiltert."""
    return external_manager.snapshot()


@app.get("/api/v1/external/sources")
async def external_sources():
    return {
        "enabled": CONFIG.EXT_ENABLED,
        "radius_m": CONFIG.EXT_RADIUS_M,
        "max_age_s": CONFIG.EXT_MAX_AGE_S,
        "anchor_set": geo_resolver.anchor is not None,
        "sources": [s.model_dump() for s in external_manager.status()],
    }


@app.post("/api/v1/external/refresh")
async def external_refresh():
    """Erzwingt einen sofortigen Abruf aller Quellen (Diagnose)."""
    if not CONFIG.EXT_ENABLED:
        raise HTTPException(status_code=503, detail="Externe Feeds deaktiviert")
    for source in external_manager.sources:
        if source.available():
            await source.poll()
    return external_manager.snapshot()


@app.get("/api/v1/health")
async def health():
    return {"status": "ok", "mode": current_mode, "mqtt": getattr(app.state, "mqtt_bridge", None).available if hasattr(app.state, "mqtt_bridge") else False}


# ─── WebSocket-Endpunkt ──────────────────────────────────────
@app.websocket("/ws/agent/events")
async def websocket_endpoint(websocket: WebSocket):
    await manager.connect(websocket)
    device_id = "CT45P-01"
    try:
        while True:
            raw = await websocket.receive_text()
            try:
                data = json.loads(raw)
            except json.JSONDecodeError:
                continue

            msg_type = data.get("type")
            payload = data.get("payload", {})

            if msg_type == "handshake":
                device_id = payload.get("device_id", device_id)

            elif msg_type == "lidar":
                frame = LidarFrame(**payload)
                points = np.asarray(frame.points, dtype=np.float32).reshape(-1, 3)
                if len(points) > 0:
                    ekf.update_lidar(points[0])
                await manager.broadcast_binary(PointCloudCompressor.compress(points))

            elif msg_type == "mmwave":
                targets = MmwaveTarget(**payload)
                if targets.targets:
                    t = targets.targets[0]
                    ekf.update_mmwave(np.array([t["x"], t["y"], t["z"]]))

            elif msg_type == "ble":
                ble_data = BleTokenUpdate(**payload)
                logger.debug("BLE-Tokens: %d", len(ble_data.tokens))

            elif msg_type == "uwb_phase":
                uwb = UwbPhaseData(**payload)
                uwb_processor.feed_phase(uwb.phase)
                resp_hz, conf = uwb_processor.detect_respiration()
                if resp_hz > 0:
                    logger.info("Atmung erkannt: %.2f Hz (Konfidenz: %.2f)", resp_hz, conf)

            elif msg_type == "telemetry":
                global thermal_celsius, scattering_detected, current_mode
                thermal_celsius = payload.get("thermal_c", 45.0)
                scattering_detected = payload.get("scattering", False)
                ekf.adapt_to_environment(scattering_detected, thermal_celsius)
                if thermal_celsius > CONFIG.THERMAL_CRITICAL_C:
                    current_mode = "MINIMAL"
                elif scattering_detected or thermal_celsius > CONFIG.THERMAL_WARNING_C:
                    current_mode = "DEGRADED"
                else:
                    current_mode = "FULL"

            # Persistenz nach Positions-Updates
            if msg_type in ("lidar", "mmwave"):
                state = ekf.get_state()
                db.save_transform(
                    device_id,
                    (state.x, state.y, state.z),
                    (ekf.R_lidar[0, 0], ekf.R_mmwave[0, 0]),
                    {
                        "mode": current_mode,
                        "scattering": scattering_detected,
                        "thermal": thermal_celsius,
                    },
                )

    except WebSocketDisconnect:
        manager.disconnect(websocket)
        logger.info("Client getrennt")
    except Exception as e:  # noqa: BLE001
        manager.disconnect(websocket)
        logger.error("WebSocket-Fehler: %s", e, exc_info=True)


# ─── Main ────────────────────────────────────────────────────
if __name__ == "__main__":
    uvicorn.run(app, host=CONFIG.API_HOST, port=CONFIG.API_PORT)
