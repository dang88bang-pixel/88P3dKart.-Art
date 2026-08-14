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
from models import (
    AuraHeatmapRequest,
    AuraRtiRequest,
    BleTokenUpdate,
    EkfState,
    LidarFrame,
    MergeRequest,
    MmwaveTarget,
    PipelineRequest,
    ScenarioConfig,
    UwbPhaseData,
)
from pipeline import DataPipeline
from pointcloud_compressor import PointCloudCompressor
from rti_solver import Link, RfSample, RtiSolver, build_heatmap
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

    yield
    logger.info("Edge-Agent wird heruntergefahren...")


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


# ─── Aura (SDR/RTI) — docs/AURA.md §8 ─────────────────────────


@app.post("/api/v1/aura/rti")
async def aura_rti(request: AuraRtiRequest):
    """RTI-Rekonstruktion: Messlinien → Voxel-Dämpfungsfeld (Tikhonov/Backprojection)."""
    if len(request.links) < 2:
        raise HTTPException(status_code=400, detail="Mindestens 2 Messlinien benötigt")

    solver = RtiSolver(
        bounds_min=tuple(request.bounds_min),
        bounds_max=tuple(request.bounds_max),
        voxel_size=request.voxel_size,
        ellipse_width=request.ellipse_width,
        regularization=request.regularization,
    )
    for link in request.links:
        solver.add_link(Link(tx=tuple(link.tx), rx=tuple(link.rx), attenuation_db=link.attenuation_db))

    if request.method == "backprojection":
        field = solver.solve_backprojection()
    else:
        field = solver.solve()

    # Nur signifikante Voxel übertragen (Reduktion der WebSocket-Last)
    threshold = max((v.attenuation for v in field), default=0.0) * 0.1
    voxels = [
        {
            "x": v.x,
            "y": v.y,
            "z": v.z,
            "attenuation": v.attenuation,
            "weight": v.weight,
        }
        for v in field
        if v.attenuation >= threshold
    ]
    peaks = solver.locate_peaks(field, top_k=8)

    response = {
        "type": "aura_voxels",
        "payload": {
            "device_id": request.device_id,
            "voxel_count": solver.voxel_count,
            "link_count": solver.link_count,
            "voxels": voxels,
        },
    }
    await manager.broadcast_json(response)

    return {
        "device_id": request.device_id,
        "method": request.method,
        "voxel_count": solver.voxel_count,
        "link_count": solver.link_count,
        "voxels": voxels[:5000],
        "peaks": [{"x": p.x, "y": p.y, "z": p.z, "attenuation": p.attenuation} for p in peaks],
    }


@app.post("/api/v1/aura/heatmap")
async def aura_heatmap(request: AuraHeatmapRequest):
    """RF-Samples → extrudierte Heatmap-Zellen (Höhe ∝ Signalstärke)."""
    if not request.samples:
        raise HTTPException(status_code=400, detail="Keine Samples übergeben")

    samples = [
        RfSample(
            timestamp_ms=int(s.get("timestamp_ms", 0)),
            x=float(s["x"]),
            y=float(s["y"]),
            z=float(s.get("z", 0.0)),
            dbm=float(s["dbm"]),
            frequency_hz=float(s.get("frequency_hz", 433.92e6)),
        )
        for s in request.samples
    ]
    cells = build_heatmap(samples, cell_size_m=request.cell_size_m)
    cells_payload = [
        {
            "x": c.center_x,
            "y": c.center_y,
            "z": c.base_z,
            "height": c.height_m,
            "dbm": c.dbm,
            "size": c.cell_size_m,
        }
        for c in cells
    ]

    await manager.broadcast_json(
        {
            "type": "aura_heatmap",
            "payload": {"device_id": request.device_id, "cells": cells_payload},
        }
    )
    return {"device_id": request.device_id, "cells": cells_payload}


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

            elif msg_type == "aura_voxels":
                # RTI-Voxel der CT45P-App → an alle Visualizer-Clients weiterreichen
                await manager.broadcast_json(data)

            elif msg_type == "aura_heatmap":
                await manager.broadcast_json(data)

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
