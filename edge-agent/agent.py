"""Edge-Agent: FastAPI + WebSocket + EKF/UWB/ICP/Pipeline-Integration."""
import asyncio
import hashlib
import json
import logging
import time
import uuid
from typing import List

import numpy as np
import uvicorn
from contextlib import asynccontextmanager, suppress
from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request, Response, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from fastapi.middleware.trustedhost import TrustedHostMiddleware
from pydantic import ValidationError
from security import (
    AttemptLimitExceeded,
    AuthenticationError,
    AuthenticationUnavailable,
    CredentialAttemptControls,
    CredentialStore,
    GatewaySecurity,
    Principal,
)

from alarm_repository import AlarmRepository
from alarm_service import (
    AlarmAuthorizationError,
    AlarmConflict,
    AlarmInputError,
    AlarmNotFound,
    AlarmService,
)
from config import CONFIG
from database import LocalVectorStore
from device_db import (
    COMPANY_IDS,
    GATT_STANDARD_SERVICES,
    SEED_OUI,
    TRACKER_PROFILES,
    DeviceDatabase,
    OuiDatabase,
    lookup_company,
    lookup_gatt_service,
    lookup_tracker,
    normalize_company_id,
    normalize_mac,
    normalize_uuid16,
)
from device_registry import Device, DeviceActionEngine, DeviceRegistry
from ekf_fusion import AdaptiveEKF
from export_formats import (
    points_to_obj,
    points_to_ply,
    points_to_stl,
    annotations_to_geojson,
    annotations_to_json,
    annotations_to_kml,
    points_to_geojson,
)
from floorplan import (
    SOURCES,
    fetch_osm_buildings,
    geocode as floorplan_geocode,
)
from icp_merger import ICPMerger
from external.manager import ExternalEntityManager
from geo.resolver import GeoResolver
from models import (
    AlarmEvidenceRequest,
    AlarmPolicyRequest,
    AlarmSnoozeRequest,
    ASSET_ID_PATTERN,
    POLICY_ID_PATTERN,
    AuraHeatmapRequest,
    AuraRtiRequest,
    BleTokenUpdate,
    BluetoothAccessoryUpdateRequest,
    DeviceActionRequest,
    DeviceLayerRequest,
    DeviceUpsertRequest,
    EkfState,
    EnrollmentClaimRequest,
    EnrollmentCodeRequest,
    ExportRequest,
    FloorPlanBuildingsRequest,
    FloorPlanGeocodeRequest,
    LidarFrame,
    MergeRequest,
    MmwaveTarget,
    NetworkTrafficRequest,
    PipelineRequest,
    ScenarioConfig,
    SessionRequest,
    SimulationRequest,
    ScenarioStopRequest,
    TopologyRequest,
    TriangulationRequest,
    UwbPhaseData,
    accuracy_to_quality,
)
from network_tracker import DeviceTracker
from network_topology import TopologyGraph, TopologyHistory
from network_traffic import NetworkTrafficSimulator, TrafficFlow, aggregate_activity, heatmap_columns
from pipeline import DataPipeline
from pointcloud_compressor import PointCloudCompressor
from rti_solver import Link, RfSample, RtiSolver, build_heatmap
from trilateration import solve_trilateration
from uwb_processor import UwbDopplerProcessor
from bluetooth_accessories import (
    BluetoothAccessory,
    BluetoothAccessoryType,
    global_accessory_registry,
)
from dataclasses import asdict

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
_metrics_pipeline_runs = 0          # echter Zähler: POST /api/v1/pipeline/run
_metrics_lidar_frames = 0           # echter Zähler: WS lidar-Frames
_metrics_mmwave_frames = 0          # echter Zähler: WS mmwave-Frames
_metrics_started_at = time.time()   # Prozess-Startzeit
topology_graph = TopologyGraph()      # Network3D: Live-Topologie (docs/NETWORK3D.md)
topology_history = TopologyHistory()  # Time Machine: Snapshot-Replay
device_tracker = DeviceTracker()      # Live-Netzwerk: Change-/Anomalie-Erkennung
device_registry = DeviceRegistry()    # Geräteinteraktion (docs/DEVICE_INTERACTION.md)
device_action_engine = DeviceActionEngine(device_registry)
traffic_simulator = NetworkTrafficSimulator(seed=42)  # LiveView-Demo (docs/NETWORK_LIVEVIEW.md)

# Offline-Gerätedatenbank (docs/DEVICE_DATABASE.md): gebaute DB falls
# vorhanden (data/device_db.json), sonst kuratierter Seed.
def _load_device_db() -> tuple:
    import pathlib

    db_path = pathlib.Path(__file__).parent / "data" / "device_db.json"
    if db_path.exists():
        try:
            payload = json.loads(db_path.read_text(encoding="utf-8"))
            records = [
                __import__("device_db", fromlist=["DeviceRecord"]).DeviceRecord.from_dict(r)
                for r in payload.get("records", [])
            ]
            DeviceDatabase.validate_list(records)
            db = DeviceDatabase(records)
            oui = OuiDatabase(payload.get("oui") or {})
            # Gebaute Company-ID-Liste (Builder) über den kuratierten Seed legen
            built_company_ids = payload.get("company_ids") or {}
            for key, name in built_company_ids.items():
                cid = normalize_company_id(str(key))
                if cid is not None and name:
                    COMPANY_IDS[cid] = str(name)
            return db, oui, db_path.name
        except Exception as exc:  # noqa: BLE001
            logger.warning("Geräte-DB fehlerhaft (%s) — Seed aktiv", exc)
    return DeviceDatabase.seed(), OuiDatabase(SEED_OUI), "seed"


device_db, device_oui_db, device_db_source = _load_device_db()


def _devices_payload() -> dict:
    """Broadcast-Payload: Geräte + Layer für alle Visualizer."""
    return {
        "type": "devices_update",
        "payload": {
            "devices": [d.to_dict() for d in device_registry.devices],
            "layers": {k: v.to_dict() for k, v in device_registry.layers.items()},
        },
    }

current_mode = "FULL"
scattering_detected = False
sensor_thermal_celsius: float | None = None
sensor_thermal_source: str | None = None

_loop: asyncio.AbstractEventLoop | None = None


# ─── WebSocket-Verwaltung ────────────────────────────────────
class ConnectionManager:
    def __init__(self):
        self.active_connections: dict[WebSocket, Principal] = {}

    async def connect(self, websocket: WebSocket, principal: Principal):
        await websocket.accept()
        self.active_connections[websocket] = principal

    def disconnect(self, websocket: WebSocket):
        self.active_connections.pop(websocket, None)

    def _authorized_connections(self, subject: str):
        return [
            connection
            for connection, principal in list(self.active_connections.items())
            if principal.role == "admin" or principal.subject == subject
        ]

    async def broadcast_binary(self, data: bytes, subject: str):
        for connection in self._authorized_connections(subject):
            try:
                await connection.send_bytes(data)
            except Exception:
                self.disconnect(connection)

    async def broadcast_json(self, data: dict, subject: str | None = None) -> int:
        text = json.dumps(data, separators=(",", ":"), allow_nan=False)
        delivered = 0
        targets = (
            list(self.active_connections.items())
            if subject is None
            else [
                (c, p) for c, p in self.active_connections.items()
                if p.role == "admin" or p.subject == subject
            ]
        )
        for connection, _principal in targets:
            try:
                await connection.send_text(text)
                delivered += 1
            except Exception:
                self.disconnect(connection)
        return delivered

    def broadcast_json_sync(self, data: dict, subject: str | None = None):
        """Thread-safe, subject-scoped broadcast for the MQTT thread."""
        if _loop is not None and _loop.is_running():
            asyncio.run_coroutine_threadsafe(
                self.broadcast_json(data, subject), _loop
            )


manager = ConnectionManager()


async def _alarm_runtime_loop(application: FastAPI) -> None:
    """Advance alarm deadlines and drain the durable WebSocket outbox."""
    tick_seconds = max(0.05, CONFIG.ALARM_TICK_MS / 1000.0)
    while True:
        try:
            service: AlarmService = application.state.alarm_service
            await asyncio.to_thread(service.tick_all)
            now_utc_ms = int(time.time() * 1000)
            records = await asyncio.to_thread(
                service.repository.claim_outbox,
                now_utc_ms,
                CONFIG.ALARM_OUTBOX_LEASE_MS,
                CONFIG.ALARM_OUTBOX_BATCH_SIZE,
            )
            for record in records:
                delivered = await manager.broadcast_json(
                    {"type": "alarm_event", "payload": record.payload},
                    record.payload["asset_id"],
                )
                if delivered:
                    await asyncio.to_thread(
                        service.repository.acknowledge_delivery, record.event_id
                    )
                else:
                    await asyncio.to_thread(
                        service.repository.retry_delivery,
                        record.event_id,
                        now_utc_ms + CONFIG.ALARM_OUTBOX_RETRY_MS,
                        "no authorized WebSocket subscriber",
                    )
        except asyncio.CancelledError:
            raise
        except Exception:
            logger.exception("Alarm scheduler/outbox iteration failed")
        await asyncio.sleep(tick_seconds)


# ─── FastAPI App ─────────────────────────────────────────────
@asynccontextmanager
async def lifespan(app: FastAPI):
    global _loop
    _loop = asyncio.get_running_loop()
    logger.info("Edge-Agent gestartet. Loop: %.1f Hz", CONFIG.LOOP_HZ)

    from mqtt_bridge import MqttBleBridge

    mqtt_bridge = MqttBleBridge(
        broker_host=CONFIG.MQTT_HOST,
        port=CONFIG.MQTT_PORT,
        websocket_manager=manager,
        enabled=CONFIG.MQTT_ENABLED,
        username=CONFIG.MQTT_USERNAME,
        password=CONFIG.MQTT_PASSWORD,
        tls_ca=CONFIG.MQTT_TLS_CA,
        tls_cert=CONFIG.MQTT_TLS_CERT,
        tls_key=CONFIG.MQTT_TLS_KEY,
    )
    mqtt_bridge.start()
    app.state.mqtt_bridge = mqtt_bridge
    alarm_task = asyncio.create_task(_alarm_runtime_loop(app))
    app.state.alarm_task = alarm_task

    try:
        yield
    finally:
        alarm_task.cancel()
        with suppress(asyncio.CancelledError):
            await alarm_task
        mqtt_bridge.stop()
        logger.info("Edge-Agent wird heruntergefahren...")


app = FastAPI(title="3dxAgent Edge-Agent", version="2.0.0", lifespan=lifespan)
app.state.scenarios = {}
app.state.security = GatewaySecurity(
    CredentialStore(CONFIG.AUTH_DB_PATH),
    CONFIG.AUTH_SIGNING_SECRET,
    CONFIG.ADMIN_BOOTSTRAP_TOKEN,
    CONFIG.SESSION_TTL_SECONDS,
)
app.state.credential_attempts = CredentialAttemptControls(
    max_failures=CONFIG.AUTH_MAX_FAILURES,
    window_s=CONFIG.AUTH_FAILURE_WINDOW_SECONDS,
    max_keys=CONFIG.AUTH_ATTEMPT_MAX_KEYS,
)
app.state.alarm_service = AlarmService(
    AlarmRepository(CONFIG.ALARM_DB_PATH),
    CONFIG.GATEWAY_ID,
)
if CONFIG.TRUSTED_HOSTS:
    app.add_middleware(TrustedHostMiddleware, allowed_hosts=list(CONFIG.TRUSTED_HOSTS))
if CONFIG.CORS_ORIGINS:
    app.add_middleware(
        CORSMiddleware,
        allow_origins=list(CONFIG.CORS_ORIGINS),
        allow_credentials=False,
        allow_methods=["GET", "POST"],
        allow_headers=["Authorization", "Content-Type"],
    )


@app.middleware("http")
async def enforce_transport_and_request_size(request: Request, call_next):
    if (
        CONFIG.REQUIRE_TLS
        and request.url.scheme != "https"
        and request.url.path != "/api/v1/health"
    ):
        return Response(status_code=426, content="TLS required")
    if request.method in {"POST", "PUT", "PATCH"}:
        content_length = request.headers.get("content-length")
        if content_length is None:
            return Response(status_code=411, content="Content-Length required")
        try:
            length = int(content_length)
        except ValueError:
            return Response(status_code=400, content="Invalid Content-Length")
        if length < 0 or length > CONFIG.MAX_HTTP_BODY_BYTES:
            return Response(status_code=413, content="Request body too large")

        # Count the actual ASGI body as well; Content-Length is attacker supplied.
        body = bytearray()
        async for chunk in request.stream():
            if len(body) + len(chunk) > CONFIG.MAX_HTTP_BODY_BYTES:
                return Response(status_code=413, content="Request body too large")
            body.extend(chunk)
        if len(body) != length:
            return Response(status_code=400, content="Content-Length mismatch")
        request._body = bytes(body)
    return await call_next(request)


def _bearer_value(authorization: str | None) -> str:
    if authorization is None or len(authorization) > 4096:
        raise HTTPException(status_code=401, detail="bearer authentication required")
    scheme, separator, token = authorization.partition(" ")
    if separator != " " or scheme.lower() != "bearer" or not token:
        raise HTTPException(status_code=401, detail="bearer authentication required")
    return token


def _direct_peer(scope: dict) -> str:
    """Return the direct ASGI peer; never derive security state from forwarded headers."""
    client = scope.get("client")
    if isinstance(client, (tuple, list)) and client:
        return str(client[0])[:255]
    return "unknown-peer"


def _credential_subject(value: str | None) -> str:
    # Store neither credentials nor attacker-controlled unbounded strings in limiter keys.
    encoded = (value or "<missing>").encode("utf-8", errors="replace")
    return hashlib.sha256(encoded).hexdigest()


def _ensure_attempt_allowed(
    controls: CredentialAttemptControls,
    flow: str,
    peer: str,
    subject: str,
) -> None:
    try:
        controls.ensure_allowed(flow, peer, subject)
    except AttemptLimitExceeded as exc:
        raise HTTPException(
            status_code=429,
            detail="too many credential attempts",
            headers={"Retry-After": str(exc.retry_after_s)},
        ) from exc


def _authenticate(security: GatewaySecurity, token: str) -> Principal:
    try:
        return security.authenticate_bearer(token)
    except AuthenticationUnavailable as exc:
        raise HTTPException(status_code=503, detail="authentication unavailable") from exc
    except (AuthenticationError, ValueError) as exc:
        raise HTTPException(status_code=401, detail="invalid bearer credential") from exc


async def authenticated_principal(
    request: Request,
    authorization: str | None = Header(default=None),
) -> Principal:
    controls: CredentialAttemptControls = request.app.state.credential_attempts
    peer = _direct_peer(request.scope)
    subject = _credential_subject(authorization)
    _ensure_attempt_allowed(controls, "bearer", peer, subject)
    try:
        token = _bearer_value(authorization)
        principal = _authenticate(request.app.state.security, token)
    except HTTPException as exc:
        if exc.status_code == 401:
            controls.failure("bearer", peer, subject)
        raise
    controls.success("bearer", peer, subject)
    return principal


async def admin_principal(
    principal: Principal = Depends(authenticated_principal),
) -> Principal:
    if principal.role != "admin":
        raise HTTPException(status_code=403, detail="administrator role required")
    return principal


def _require_device_scope(principal: Principal, device_id: str) -> None:
    if principal.role != "admin" and principal.subject != device_id:
        raise HTTPException(status_code=403, detail="device scope denied")


# ─── Enrollment and session endpoints ───────────────────────
@app.post("/api/v1/admin/enrollment-codes")
async def create_enrollment_code(
    request: EnrollmentCodeRequest,
    response: Response,
    http_request: Request,
    _principal: Principal = Depends(admin_principal),
):
    code = http_request.app.state.security.store.create_enrollment_code(
        request.device_id, int(time.time()), request.ttl_seconds
    )
    response.headers["Cache-Control"] = "no-store"
    return {
        "device_id": code.device_id,
        "enrollment_code": code.code,
        "expires_at": code.expires_utc_s,
    }


@app.post("/api/v1/enrollment/claim")
async def claim_enrollment(
    request: EnrollmentClaimRequest,
    response: Response,
    http_request: Request,
):
    security: GatewaySecurity = http_request.app.state.security
    controls: CredentialAttemptControls = http_request.app.state.credential_attempts
    peer = _direct_peer(http_request.scope)
    subject = request.device_id
    _ensure_attempt_allowed(controls, "enrollment", peer, subject)
    if not security.available:
        raise HTTPException(status_code=503, detail="authentication unavailable")
    try:
        credential = security.store.claim_enrollment(
            request.device_id, request.code, int(time.time())
        )
    except (AuthenticationError, ValueError) as exc:
        controls.failure("enrollment", peer, subject)
        raise HTTPException(status_code=401, detail="invalid enrollment credential") from exc
    controls.success("enrollment", peer, subject)
    response.headers["Cache-Control"] = "no-store"
    return {"device_id": credential.device_id, "device_secret": credential.secret}


@app.post("/api/v1/session")
async def create_session(request: SessionRequest, response: Response, http_request: Request):
    controls: CredentialAttemptControls = http_request.app.state.credential_attempts
    peer = _direct_peer(http_request.scope)
    subject = request.device_id
    _ensure_attempt_allowed(controls, "session", peer, subject)
    try:
        token, expires = http_request.app.state.security.issue_device_session(
            request.device_id, request.device_secret
        )
    except AuthenticationUnavailable as exc:
        raise HTTPException(status_code=503, detail="authentication unavailable") from exc
    except (AuthenticationError, ValueError) as exc:
        controls.failure("session", peer, subject)
        raise HTTPException(status_code=401, detail="invalid device credential") from exc
    controls.success("session", peer, subject)
    response.headers["Cache-Control"] = "no-store"
    return {
        "token_type": "Bearer",
        "access_token": token,
        "expires_at": expires,
    }


@app.post("/api/v1/admin/devices/{device_id}/disable")
async def disable_device(
    device_id: str,
    http_request: Request,
    _principal: Principal = Depends(admin_principal),
):
    if len(device_id) > 160:
        raise HTTPException(status_code=422, detail="invalid device id")
    disabled = http_request.app.state.security.store.disable_device(device_id)
    if not disabled:
        raise HTTPException(status_code=404, detail="device not found")
    return {"status": "disabled", "device_id": device_id}


# ─── REST-Endpunkte ──────────────────────────────────────────
def _raise_alarm_http_error(error: Exception) -> None:
    if isinstance(error, AlarmAuthorizationError):
        raise HTTPException(status_code=403, detail="asset scope denied") from error
    if isinstance(error, AlarmNotFound):
        raise HTTPException(status_code=404, detail="alarm policy not found") from error
    if isinstance(error, AlarmConflict):
        raise HTTPException(status_code=409, detail=str(error)) from error
    if isinstance(error, AlarmInputError):
        raise HTTPException(status_code=422, detail=str(error)) from error
    raise error


@app.post("/api/v1/alarm/policies")
async def store_alarm_policy(
    request: AlarmPolicyRequest,
    principal: Principal = Depends(admin_principal),
):
    try:
        return app.state.alarm_service.store_policy(request, principal)
    except (AlarmAuthorizationError, AlarmNotFound, AlarmConflict, AlarmInputError) as exc:
        _raise_alarm_http_error(exc)


@app.post("/api/v1/alarm/evidence")
async def ingest_alarm_evidence(
    request: AlarmEvidenceRequest,
    principal: Principal = Depends(authenticated_principal),
):
    try:
        return app.state.alarm_service.ingest(request, principal)
    except (AlarmAuthorizationError, AlarmNotFound, AlarmConflict, AlarmInputError) as exc:
        _raise_alarm_http_error(exc)


@app.get("/api/v1/alarm/runtime")
async def get_alarm_runtime(
    policy_id: str = Query(pattern=POLICY_ID_PATTERN),
    asset_id: str = Query(pattern=ASSET_ID_PATTERN),
    principal: Principal = Depends(authenticated_principal),
):
    try:
        return app.state.alarm_service.get_runtime(policy_id, asset_id, principal)
    except (AlarmAuthorizationError, AlarmNotFound, AlarmConflict, AlarmInputError) as exc:
        _raise_alarm_http_error(exc)


@app.get("/api/v1/alarm/events")
async def get_alarm_events(
    policy_id: str = Query(pattern=POLICY_ID_PATTERN),
    asset_id: str = Query(pattern=ASSET_ID_PATTERN),
    after_state_revision: int = Query(default=0, ge=0),
    limit: int = Query(default=100, ge=1, le=500),
    principal: Principal = Depends(authenticated_principal),
):
    try:
        events = app.state.alarm_service.list_events(
            policy_id,
            asset_id,
            principal,
            after_state_revision,
            limit,
        )
        return {"events": events}
    except (AlarmAuthorizationError, AlarmNotFound, AlarmConflict, AlarmInputError) as exc:
        _raise_alarm_http_error(exc)


@app.post("/api/v1/alarm/acknowledge")
async def acknowledge_alarm(
    policy_id: str = Query(pattern=POLICY_ID_PATTERN),
    asset_id: str = Query(pattern=ASSET_ID_PATTERN),
    principal: Principal = Depends(authenticated_principal),
):
    try:
        return app.state.alarm_service.acknowledge(policy_id, asset_id, principal)
    except (AlarmAuthorizationError, AlarmNotFound, AlarmConflict, AlarmInputError) as exc:
        _raise_alarm_http_error(exc)


@app.post("/api/v1/alarm/snooze")
async def snooze_alarm(
    request: AlarmSnoozeRequest,
    policy_id: str = Query(pattern=POLICY_ID_PATTERN),
    asset_id: str = Query(pattern=ASSET_ID_PATTERN),
    principal: Principal = Depends(authenticated_principal),
):
    try:
        return app.state.alarm_service.snooze(
            policy_id, asset_id, request.duration_ms, principal
        )
    except (AlarmAuthorizationError, AlarmNotFound, AlarmConflict, AlarmInputError) as exc:
        _raise_alarm_http_error(exc)


@app.get("/api/v1/agent/state", response_model=EkfState)
async def get_state(_principal: Principal = Depends(authenticated_principal)):
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
async def update_config(
    scenario: ScenarioConfig,
    _principal: Principal = Depends(admin_principal),
):
    logger.info("Szenario gestartet: %s mit Parametern %s", scenario.type, scenario.params)
    return {"status": "ok", "scenario": scenario.type}


@app.get("/api/v1/agent/history")
async def get_history(
    device_id: str,
    limit: int = 100,
    principal: Principal = Depends(authenticated_principal),
):
    _require_device_scope(principal, device_id)
    if not 1 <= limit <= 1000:
        raise HTTPException(status_code=422, detail="limit must be between 1 and 1000")
    records = db.get_latest(device_id, limit)
    return {"device_id": device_id, "records": records}


@app.post("/api/v1/agent/merge")
async def merge_maps(
    request: MergeRequest,
    _principal: Principal = Depends(admin_principal),
):
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
async def run_pipeline(
    request: PipelineRequest,
    principal: Principal = Depends(authenticated_principal),
):
    _require_device_scope(principal, request.device_id)
    global _metrics_pipeline_runs
    _metrics_pipeline_runs += 1
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
async def health(request: Request):
    authentication_ready = request.app.state.security.available
    return {
        "status": "ok" if authentication_ready else "degraded",
        "authentication": "ready" if authentication_ready else "unconfigured",
        "bluetooth": global_accessory_registry.stats(),
    }


# ─── Bluetooth-Zubehör (docs/BT_ACCESSORIES.md) ──────────────────


@app.post("/api/v1/bluetooth/accessories/update")
async def bluetooth_accessories_update(request: BluetoothAccessoryUpdateRequest):
    """Batch-Ingest eigener Zubehörgeräte (Tokens, Sensor-Tags, Wearables)."""
    updated = global_accessory_registry.update_batch(request.accessories)
    return {"updated": len(updated)}


@app.get("/api/v1/bluetooth/accessories")
async def bluetooth_accessories_list(
    type: str | None = Query(default=None, max_length=32),
):
    """Liste aller bekannten Zubehörgeräte (optional nach Typ gefiltert)."""
    if type:
        try:
            type_enum = BluetoothAccessoryType(type.upper())
        except ValueError:
            raise HTTPException(status_code=400, detail="unbekannter Zubehörtyp")
        accessories = global_accessory_registry.get_by_type(type_enum)
    else:
        accessories = global_accessory_registry.get_all()
    return {
        "count": len(accessories),
        "accessories": [a.to_dict() for a in accessories],
        "stats": global_accessory_registry.stats(),
    }


@app.get("/api/v1/bluetooth/accessories/{mac}")
async def bluetooth_accessory_detail(mac: str):
    accessory = global_accessory_registry.get(mac)
    if accessory is None:
        raise HTTPException(status_code=404, detail="Zubehörgerät nicht gefunden")
    return {"accessory": accessory.to_dict()}


@app.delete("/api/v1/bluetooth/accessories/{mac}")
async def bluetooth_accessory_delete(mac: str):
    removed = global_accessory_registry.remove(mac)
    if not removed:
        raise HTTPException(status_code=404, detail="Zubehörgerät nicht gefunden")
    return {"removed": mac.lower()}


@app.get("/api/v1/bluetooth/health")
async def bluetooth_health():
    """Health-Bewertung aller Zubehörgeräte."""
    health = [asdict(h) for h in global_accessory_registry.evaluate_all_health()]
    return {
        "total": global_accessory_registry.count(),
        "health": health,
    }


@app.get("/api/v1/bluetooth/stats")
async def bluetooth_stats():
    return global_accessory_registry.stats()


# ─── Triangulation (CT45P) — docs/TRIANGULATION.md §8 ────────────


@app.post("/api/v1/triangulation/solve")
async def triangulation_solve(request: TriangulationRequest):
    """Trilateration: Anker + Distanzen → Position (REST-Fallback zur App)."""
    result = solve_trilateration(
        request.anchors,
        request.distances,
        request.uncertainties,
        use_z=request.use_z,
    )
    if result is None:
        raise HTTPException(
            status_code=400,
            detail="Mindestens 3 Anker mit gültigen Distanzen benötigt (3D: 4)",
        )
    return {"position": result, "anchor_count": result["anchor_count"]}


@app.get("/api/v1/agent/mesh")
async def get_mesh(
    device_id: str,
    limit: int = 10000,
    format: str = Query(default="json", max_length=8),
    semantic_filter: str = Query(default="all", max_length=16),
    principal: Principal = Depends(authenticated_principal),
):
    """Aktuelle 3D-Mesh-Punkte eines Geräts (Voxel-Rohdaten aus der DB).

    format: json (Vollstruktur), obj, ply, stl (Textformate).
    glb/gltf/ifc sind bewusst nicht implementiert (501) — siehe README.
    """
    _require_device_scope(principal, device_id)
    if not 1 <= limit <= 500_000:
        raise HTTPException(status_code=422, detail="limit must be between 1 and 500000")
    if semantic_filter != "all":
        raise HTTPException(
            status_code=400,
            detail="semantic_filter wird aktuell nur mit 'all' unterstützt (DB ohne Semantik-Labels)",
        )
    points = [list(p) for p in db.get_all_points(device_id, limit)]
    if format in ("obj", "ply", "stl"):
        if format == "obj":
            content = points_to_obj(points)
            media = "text/plain"
        elif format == "ply":
            content = points_to_ply(points)
            media = "text/plain"
        else:
            content = points_to_stl(points)
            media = "model/stl"
        return Response(content=content, media_type=media)
    if format == "json":
        mesh = pipeline.mesh_generator.generate(np.asarray(points, dtype=float).reshape(-1, 3))
        arr = np.asarray(points, dtype=float)
        bounds = {
            "min": arr.min(axis=0).tolist() if len(arr) else [0, 0, 0],
            "max": arr.max(axis=0).tolist() if len(arr) else [0, 0, 0],
        }
        return {
            "device_id": device_id,
            "count": len(points),
            "points": points,
            "faces": mesh.faces.tolist(),
            "bounds": bounds,
            "timestamp": int(time.time()),
        }
    raise HTTPException(
        status_code=501,
        detail=f"Format '{format}' nicht implementiert (verfügbar: json, obj, ply, stl)",
    )


@app.get("/api/v1/agent/evaluation")
async def get_evaluation(
    device_id: str,
    principal: Principal = Depends(authenticated_principal),
):
    """Evaluierungsbericht: echte Pipeline-Bewertung der gespeicherten Punkte."""
    _require_device_scope(principal, device_id)
    points = [list(p) for p in db.get_all_points(device_id, 100_000)]
    if not points:
        return {
            "device_id": device_id,
            "status": "empty",
            "num_points": 0,
            "confidence": 0.0,
            "message": "Keine Punkte gespeichert — zuerst Lidar-Daten senden (WS 'lidar').",
        }
    flat: list[float] = [c for p in points for c in p]
    result = pipeline.run(flat, source="lidar", quality=1.0)
    return {"device_id": device_id, **result}


@app.post("/api/v1/agent/scenario/start")
async def scenario_start(
    scenario: ScenarioConfig,
    _principal: Principal = Depends(admin_principal),
):
    """Startet ein benanntes Kartierungsszenario (Zustand in app.state)."""
    scenarios: dict = app.state.scenarios
    scenario_id = f"scn_{uuid.uuid4().hex[:16]}"
    now_ms = int(time.time() * 1000)
    scenarios[scenario_id] = {
        "scenario_id": scenario_id,
        "type": scenario.type,
        "params": scenario.params,
        "status": "running",
        "started_at": now_ms,
        "points_at_start": sum(
            len(db.get_all_points(dev, 500_000)) for dev in db.known_devices()
        ),
    }
    logger.info("Szenario %s gestartet (Typ %s)", scenario_id, scenario.type)
    return {
        "scenario_id": scenario_id,
        "type": scenario.type,
        "status": "running",
        "started_at": now_ms,
    }


@app.post("/api/v1/agent/scenario/stop")
async def scenario_stop(
    request: ScenarioStopRequest,
    _principal: Principal = Depends(admin_principal),
):
    """Stoppt ein laufendes Szenario und liefert die Bilanz."""
    scenarios: dict = app.state.scenarios
    entry = scenarios.get(request.scenario_id)
    if entry is None:
        raise HTTPException(status_code=404, detail="Szenario nicht gefunden")
    now_ms = int(time.time() * 1000)
    points_now = sum(
        len(db.get_all_points(dev, 500_000)) for dev in db.known_devices()
    )
    entry.update(
        status="stopped",
        stopped_at=now_ms,
        duration_seconds=round((now_ms - entry["started_at"]) / 1000, 1),
        points_delta=points_now - entry["points_at_start"],
    )
    logger.info("Szenario %s gestoppt (Δ %d Punkte)", request.scenario_id, entry["points_delta"])
    return {k: entry[k] for k in (
        "scenario_id", "type", "status", "started_at", "stopped_at",
        "duration_seconds", "points_delta",
    )}


@app.get("/api/v1/metrics")
async def metrics():
    """Prometheus-Metriken (Textformat 0.0.4) — echte, laufende Zähler."""
    scenarios: dict = app.state.scenarios
    active_scenarios = sum(1 for e in scenarios.values() if e["status"] == "running")
    bt_stats = global_accessory_registry.stats()
    alarm_service: AlarmService = app.state.alarm_service
    lines = [
        "# HELP 3dxagent_uptime_seconds Prozess-Laufzeit in Sekunden",
        "# TYPE 3dxagent_uptime_seconds gauge",
        f"3dxagent_uptime_seconds {time.time() - _metrics_started_at:.3f}",
        "# HELP 3dxagent_ws_connections Aktive WebSocket-Verbindungen",
        "# TYPE 3dxagent_ws_connections gauge",
        f"3dxagent_ws_connections {len(manager.active_connections)}",
        "# HELP 3dxagent_pipeline_runs_total Verarbeitete Pipeline-Läufe",
        "# TYPE 3dxagent_pipeline_runs_total counter",
        f"3dxagent_pipeline_runs_total {_metrics_pipeline_runs}",
        "# HELP 3dxagent_lidar_frames_total Empfangene Lidar-Frames (WS)",
        "# TYPE 3dxagent_lidar_frames_total counter",
        f"3dxagent_lidar_frames_total {_metrics_lidar_frames}",
        "# HELP 3dxagent_mmwave_frames_total Empfangene mmWave-Frames (WS)",
        "# TYPE 3dxagent_mmwave_frames_total counter",
        f"3dxagent_mmwave_frames_total {_metrics_mmwave_frames}",
        "# HELP 3dxagent_ekf_position_x EKF-Position X [m]",
        "# TYPE 3dxagent_ekf_position_x gauge",
        f"3dxagent_ekf_position_x {ekf.get_state().x:.4f}",
        "# HELP 3dxagent_ekf_position_y EKF-Position Y [m]",
        "# TYPE 3dxagent_ekf_position_y gauge",
        f"3dxagent_ekf_position_y {ekf.get_state().y:.4f}",
        "# HELP 3dxagent_ekf_position_z EKF-Position Z [m]",
        "# TYPE 3dxagent_ekf_position_z gauge",
        f"3dxagent_ekf_position_z {ekf.get_state().z:.4f}",
        "# HELP 3dxagent_mode Aktueller Betriebsmodus (FULL/DEGRADED/MINIMAL)",
        "# TYPE 3dxagent_mode gauge",
        f"3dxagent_mode{{mode=\"{current_mode}\"}} 1",
        "# HELP 3dxagent_devices_registered Registrierte Geräte",
        "# TYPE 3dxagent_devices_registered gauge",
        f"3dxagent_devices_registered {len(device_registry.devices)}",
        "# HELP 3dxagent_network_topology_nodes Knoten im Topologie-Graph",
        "# TYPE 3dxagent_network_topology_nodes gauge",
        f"3dxagent_network_topology_nodes {len(topology_graph.nodes)}",
        "# HELP 3dxagent_bluetooth_accessories Bekannte BLE-Zubehörgeräte",
        "# TYPE 3dxagent_bluetooth_accessories gauge",
        f"3dxagent_bluetooth_accessories {bt_stats.get('total', 0)}",
        "# HELP 3dxagent_bluetooth_low_battery Zubehörgeräte mit <20% Akku",
        "# TYPE 3dxagent_bluetooth_low_battery gauge",
        f"3dxagent_bluetooth_low_battery {bt_stats.get('low_battery', 0)}",
        "# HELP 3dxagent_bluetooth_sos Zubehörgeräte mit aktivem SOS",
        "# TYPE 3dxagent_bluetooth_sos gauge",
        f"3dxagent_bluetooth_sos {bt_stats.get('sos_active', 0)}",
        "# HELP 3dxagent_active_scenarios Laufende Szenarien",
        "# TYPE 3dxagent_active_scenarios gauge",
        f"3dxagent_active_scenarios {active_scenarios}",
        "# HELP 3dxagent_alarm_service_ready Alarm-Service verfügbar",
        "# TYPE 3dxagent_alarm_service_ready gauge",
        f"3dxagent_alarm_service_ready {1 if alarm_service else 0}",
    ]
    return Response(content="\n".join(lines) + "\n", media_type="text/plain; version=0.0.4")


# ─── Export (docs/SERVICE_WORKER.md §Export Worker) ───────────────


@app.post("/api/v1/export")
async def export_data(request: ExportRequest):
    """Datenexport: Annotationen/Punkte → GeoJSON/KML/JSON (Retention in der App)."""
    if request.format not in ("geojson", "kml", "json"):
        raise HTTPException(status_code=400, detail="Unbekanntes Format (geojson|kml|json)")

    if request.annotations:
        if request.format == "geojson":
            content = annotations_to_geojson(request.annotations)
        elif request.format == "kml":
            content = annotations_to_kml(request.annotations)
        else:
            content = annotations_to_json(request.annotations)
    elif request.points:
        if request.format == "geojson":
            content = points_to_geojson(request.points, device_id=request.device_id)
        elif request.format == "kml":
            # Punkte als Pseudo-Annotationen für KML abbilden
            anns = [
                {
                    "id": f"p{i}",
                    "title": f"Punkt {i}",
                    "description": "",
                    "lon": p[0],
                    "lat": p[1],
                    "z": p[2] if len(p) > 2 else 0.0,
                }
                for i, p in enumerate(request.points)
            ]
            content = annotations_to_kml(anns)
        else:
            content = annotations_to_json(
                [
                    {
                        "id": f"p{i}",
                        "title": f"Punkt {i}",
                        "description": "",
                        "lon": p[0],
                        "lat": p[1],
                        "z": p[2] if len(p) > 2 else 0.0,
                    }
                    for i, p in enumerate(request.points)
                ]
            )
    else:
        raise HTTPException(status_code=400, detail="Keine annotations/points übergeben")

    return {"format": request.format, "content": content}


# ─── Network3D: Topologie, What-If, Time Machine (docs/NETWORK3D.md) ─


@app.post("/api/v1/network/topology")
async def network_topology_ingest(request: TopologyRequest):
    """Ingestiert/aktualisiert die Live-Topologie (Upsert) und broadcastet sie."""
    from network_topology import TopologyEdge, TopologyNode

    for node in request.nodes:
        topology_graph.upsert_node(TopologyNode.from_dict(node))
    for edge in request.edges:
        edge_obj = TopologyEdge.from_dict(edge)
        if edge_obj.source in topology_graph.nodes and edge_obj.target in topology_graph.nodes:
            topology_graph.edges[edge_obj.id] = edge_obj

    payload = {
        "type": "network_topology",
        "payload": topology_graph.to_dict(),
    }
    await manager.broadcast_json(payload)
    snapshot_index = topology_history.snapshot(topology_graph)
    return {
        "status": "ok",
        "node_count": len(topology_graph.nodes),
        "edge_count": len(topology_graph.edges),
        "snapshot_index": snapshot_index,
    }


@app.get("/api/v1/network/topology")
async def network_topology_get():
    """Aktuelle Topologie (für Initial-Load und LOD-Refresh)."""
    return topology_graph.to_dict()


@app.post("/api/v1/network/simulate")
async def network_simulate(request: SimulationRequest):
    """What-If: Failover-Simulation (Node-Ausfall → Betroffenheit → Rerouting)."""
    if request.node_id not in topology_graph.nodes:
        raise HTTPException(status_code=404, detail=f"Node {request.node_id} nicht in der Topologie")
    result = topology_graph.simulate_failover(request.node_id, request.flows)
    await manager.broadcast_json({"type": "topology_simulation", "payload": result})
    return result


@app.get("/api/v1/network/history")
async def network_history(index: int | None = None, limit: int = 100):
    """Time Machine: Snapshot-Replay der Topologie-Historie."""
    if index is not None:
        snapshot = topology_history.replay(index)
        if snapshot is None:
            raise HTTPException(status_code=404, detail=f"Snapshot {index} nicht im Fenster")
        return snapshot
    low, high = topology_history.range()
    if low is None:
        return {"snapshots": []}
    snapshots = [topology_history.replay(i) for i in range(low, high + 1)]
    return {"snapshots": snapshots[-limit:]}


@app.get("/api/v1/network/devices")
async def network_devices():
    """Live-Netzwerk: Geräte des Trackers (Change-/Anomalie-Erkennung)."""
    return {
        "devices": list(device_tracker.known_devices().values()),
        "count": len(device_tracker.known_devices()),
    }


# ─── Grundriss-Integration (docs/FLOORPLAN.md) ───────────────────


@app.get("/api/v1/floorplan/sources")
async def floorplan_sources():
    """Verifizierter Quellen-Katalog (Verfügbarkeit, Auth, Priorität)."""
    return {
        "sources": [
            {
                "name": s.name,
                "kind": s.kind,
                "endpoint": s.endpoint,
                "available": s.available,
                "requires_auth": s.requires_auth,
                "priority": s.priority,
                "notes": s.notes,
            }
            for s in SOURCES
        ]
    }


@app.post("/api/v1/floorplan/geocode")
async def floorplan_geocode_endpoint(request: FloorPlanGeocodeRequest):
    """Adresssuche: Nominatim (primär) mit Photon-Fallback — serverseitig,
    damit die Nominatim-Usage-Policy zentral eingehalten wird (User-Agent,
    Caching, ≤ 1 req/s)."""
    if not request.query.strip():
        raise HTTPException(status_code=400, detail="query darf nicht leer sein")
    try:
        results = floorplan_geocode(request.query)
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(status_code=502, detail=f"Geocoding fehlgeschlagen: {exc}")
    return {"query": request.query, "results": [r.to_dict() for r in results]}


@app.post("/api/v1/floorplan/buildings")
async def floorplan_buildings(request: FloorPlanBuildingsRequest):
    """Gebäudeumrisse via Overpass (mit Kumi-Spiegel-Fallback) → GeoJSON
    + Broadcast an alle Visualizer (Typ `floorplan_buildings`)."""
    try:
        result = fetch_osm_buildings(request.lat, request.lon, request.radius)
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(status_code=502, detail=f"Overpass-Abruf fehlgeschlagen: {exc}")
    await manager.broadcast_json({"type": "floorplan_buildings", "payload": result})
    return result


# ─── Geräteinteraktion (docs/DEVICE_INTERACTION.md) ──────────────


@app.get("/api/v1/devices")
async def devices_get():
    """Alle Geräte + Layer-Konfiguration des Registers."""
    return {
        "devices": [d.to_dict() for d in device_registry.devices],
        "layers": {k: v.to_dict() for k, v in device_registry.layers.items()},
    }


@app.post("/api/v1/devices/upsert")
async def devices_upsert(request: DeviceUpsertRequest):
    """Gerät upserten (Merge-Semantik) + Broadcast an alle Visualizer."""
    device = Device.from_dict(request.device)
    device_registry.upsert(device)
    device_registry.mark_stale()
    await manager.broadcast_json(_devices_payload())
    return {"status": "ok", "device_count": len(device_registry.devices)}


@app.post("/api/v1/devices/action")
async def devices_action(request: DeviceActionRequest):
    """Capability-geprüfte Geräteaktion ausführen + Ergebnis broadcasten."""
    result = device_action_engine.execute(request.device_id, request.action, request.params)
    await manager.broadcast_json({"type": "device_action_result", "payload": result.to_dict()})
    return result.to_dict()


@app.get("/api/v1/devices/layers")
async def devices_layers_get():
    return {"layers": {k: v.to_dict() for k, v in device_registry.layers.items()}}


@app.post("/api/v1/devices/layers")
async def devices_layers_set(request: DeviceLayerRequest):
    """Layer-Sichtbarkeit setzen (propagiert auf die Kategorie)."""
    ok = device_registry.set_layer_visibility(request.layer_id, request.visible)
    if not ok:
        raise HTTPException(status_code=404, detail=f"Layer {request.layer_id} nicht gefunden")
    await manager.broadcast_json(_devices_payload())
    return {"status": "ok", "layer_id": request.layer_id, "visible": request.visible}


# ─── Aktive Netzwerkvisualisierung (docs/NETWORK_LIVEVIEW.md) ─────


def _traffic_broadcast(flows: List[TrafficFlow]) -> dict:
    """Broadcast-Payload: Flüsse + Aktivitäts-Aggregation + Heatmap-Säulen."""
    activity = aggregate_activity(flows)
    return {
        "type": "network_traffic_update",
        "payload": {
            "flows": [f.to_dict() for f in flows],
            "activity": {k: v.to_dict() for k, v in activity.items()},
            "heatmap": heatmap_columns(activity, max_height=1.0),
        },
    }


@app.post("/api/v1/network/traffic")
async def network_traffic_ingest(request: NetworkTrafficRequest):
    """Live-Traffic-Ingest (SNMP/NetFlow-Adapter oder App) → Broadcast."""
    flows = [TrafficFlow.from_dict(f) for f in request.flows]
    if not flows:
        raise HTTPException(status_code=400, detail="Keine Flüsse übergeben")
    await manager.broadcast_json(_traffic_broadcast(flows))
    return {"status": "ok", "flow_count": len(flows)}


@app.post("/api/v1/network/traffic/simulate")
async def network_traffic_simulate():
    """Deterministische Flusssimulation auf den Topologie-Kanten (Demo)."""
    edges = [(e.source, e.target) for e in topology_graph.edges.values()]
    if not edges:
        raise HTTPException(status_code=404, detail="Keine Topologie geladen")
    flows = traffic_simulator.simulate(edges)
    await manager.broadcast_json(_traffic_broadcast(flows))
    return {"status": "ok", "flow_count": len(flows)}


# ─── Offline-Gerätedatenbank (docs/DEVICE_DATABASE.md) ───────────


@app.get("/api/v1/devicedb/status")
async def devicedb_status():
    """Datenbank-Status: Quelle (gebaut/Seed), Größen, Kategorien, Technologien."""
    return {
        "source": device_db_source,
        "records": len(device_db),
        "oui_entries": len(device_oui_db),
        "gatt_services": len(GATT_STANDARD_SERVICES),
        "tracker_profiles": len(TRACKER_PROFILES),
        "company_ids": len(COMPANY_IDS),
        "categories": device_db.categories(),
        "technologies": device_db.technologies(),
    }


@app.get("/api/v1/devicedb/lookup/company/{company_id}")
async def devicedb_lookup_company(company_id: str):
    """Company-ID (0x…-Hex oder dezimal) → Bluetooth-SIG-Hersteller."""
    cid = normalize_company_id(company_id)
    if cid is None:
        raise HTTPException(status_code=400, detail=f"Ungültige Company-ID: {company_id!r}")
    name = lookup_company(cid)
    if name is None:
        raise HTTPException(status_code=404, detail=f"Unbekannte Company-ID: 0x{cid:04X}")
    return {"company_id": f"0x{cid:04X}", "name": name}


@app.get("/api/v1/devicedb/lookup/mac/{mac}")
async def devicedb_lookup_mac(mac: str):
    """MAC-Adresse → OUI-Hersteller + passende Geräte-Records."""
    try:
        normalized = normalize_mac(mac)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))
    vendor = device_oui_db.lookup(normalized)
    records = [r.to_dict() for r in device_db.by_mac(normalized)]
    return {"mac": normalized, "oui_vendor": vendor, "devices": records}


@app.get("/api/v1/devicedb/lookup/service/{uuid}")
async def devicedb_lookup_service(uuid: str):
    """16-Bit-/128-Bit-UUID → GATT-Service, Tracker-Profile, Geräte."""
    service = lookup_gatt_service(uuid)
    trackers = [
        {"id": t.id, "vendor": t.vendor, "verified": t.verified, "reset": t.reset_procedure}
        for t in lookup_tracker(service_uuids=[uuid])
    ]
    devices = [r.to_dict() for r in device_db.by_service(uuid)]
    return {
        "uuid": normalize_uuid16(uuid),
        "gatt_service": {
            "name": service.name,
            "characteristics": [
                {"uuid": c.uuid, "name": c.name, "properties": c.properties}
                for c in service.characteristics
            ],
        } if service else None,
        "trackers": trackers,
        "devices": devices,
    }


@app.get("/api/v1/devicedb/search")
async def devicedb_search(
    q: str = "",
    category: str | None = None,
    technology: str | None = None,
    limit: int = 50,
):
    """Volltext-/Kategorie-/Technologie-Suche über die Gerätedatenbank."""
    results = device_db.search(q, category, technology)[: max(1, min(limit, 500))]
    return {"query": q, "category": category, "technology": technology,
            "count": len(results), "results": [r.to_dict() for r in results]}


@app.get("/api/v1/devicedb/categories")
async def devicedb_categories():
    """Kategorie-Statistik der Datenbank."""
    return {"categories": device_db.categories()}


# ─── WebSocket-Endpunkt ──────────────────────────────────────
@app.websocket("/ws/agent/events")
async def websocket_endpoint(websocket: WebSocket):
    if CONFIG.REQUIRE_TLS and websocket.url.scheme != "wss":
        await websocket.close(code=4403, reason="TLS required")
        return
    origin = websocket.headers.get("origin")
    if origin is not None and origin not in CONFIG.CORS_ORIGINS:
        await websocket.close(code=4403, reason="origin denied")
        return
    controls: CredentialAttemptControls = websocket.app.state.credential_attempts
    peer = _direct_peer(websocket.scope)
    authorization = websocket.headers.get("authorization")
    subject = _credential_subject(authorization)
    try:
        controls.ensure_allowed("websocket-bearer", peer, subject)
    except AttemptLimitExceeded:
        await websocket.close(code=4429, reason="too many credential attempts")
        return
    try:
        token = _bearer_value(authorization)
        principal = websocket.app.state.security.authenticate_bearer(token)
    except AuthenticationUnavailable:
        await websocket.close(code=1013, reason="authentication unavailable")
        return
    except (HTTPException, AuthenticationError, ValueError):
        controls.failure("websocket-bearer", peer, subject)
        await websocket.close(code=4401, reason="authentication required")
        return
    controls.success("websocket-bearer", peer, subject)

    await manager.connect(websocket, principal)
    device_id = principal.subject
    try:
        while True:
            raw = await websocket.receive_text()
            if len(raw.encode("utf-8")) > CONFIG.MAX_WEBSOCKET_MESSAGE_BYTES:
                await websocket.close(code=4400, reason="message too large")
                return
            try:
                data = json.loads(raw)
                if not isinstance(data, dict) or set(data) != {"type", "payload"}:
                    raise ValueError("invalid envelope")
                msg_type = data["type"]
                payload = data["payload"]
                if not isinstance(msg_type, str) or not isinstance(payload, dict):
                    raise ValueError("invalid envelope")

                payload_device_id = payload.get("device_id")
                if not isinstance(payload_device_id, str):
                    raise ValueError("device_id is required")
                try:
                    _require_device_scope(principal, payload_device_id)
                except HTTPException as exc:
                    await websocket.close(
                        code=4403, reason=f"device scope denied: {exc.detail}"
                    )
                    return
                device_id = payload_device_id

                if msg_type == "handshake":
                    await websocket.send_text(
                        json.dumps({"type": "handshake_ack", "device_id": device_id})
                    )

                elif msg_type == "lidar":
                    global _metrics_lidar_frames
                    _metrics_lidar_frames += 1
                    frame = LidarFrame(**payload)
                    points = np.asarray(frame.points, dtype=np.float32).reshape(-1, 3)
                    if len(points) > 0:
                        ekf.update_lidar(points[0])
                    await manager.broadcast_binary(
                        PointCloudCompressor.compress(points), device_id
                    )

                elif msg_type == "mmwave":
                    global _metrics_mmwave_frames
                    _metrics_mmwave_frames += 1
                    targets = MmwaveTarget(**payload)
                    if targets.targets:
                        target = targets.targets[0]
                        if not all(axis in target for axis in ("x", "y", "z")):
                            raise ValueError("mmwave target lacks Cartesian axes")
                        ekf.update_mmwave(
                            np.array([target["x"], target["y"], target["z"]])
                        )

                elif msg_type == "ble":
                    ble_data = BleTokenUpdate(**payload)
                    logger.debug("BLE-Tokens: %d", len(ble_data.tokens))

                elif msg_type == "uwb_phase":
                    uwb = UwbPhaseData(**payload)
                    uwb_processor.feed_phase(uwb.phase)
                    resp_hz, conf = uwb_processor.detect_respiration()
                    if resp_hz > 0:
                        logger.info(
                            "Atmung erkannt: %.2f Hz (Konfidenz: %.2f)",
                            resp_hz,
                            conf,
                        )

                elif msg_type == "telemetry":
                    global sensor_thermal_celsius, sensor_thermal_source
                    global scattering_detected, current_mode
                    thermal = payload.get("thermal_c")
                    thermal_source = payload.get("thermal_source")
                    scattering = payload.get("scattering")
                    valid_source = (
                        isinstance(thermal_source, str)
                        and 1 <= len(thermal_source) <= 64
                        and all(
                            character.isalnum() or character in "._:-"
                            for character in thermal_source
                        )
                    )
                    if (
                        not isinstance(thermal, (int, float))
                        or isinstance(thermal, bool)
                        or not isinstance(scattering, bool)
                        or not valid_source
                    ):
                        raise ValueError(
                            "thermal_source, thermal_c and scattering are required"
                        )
                    if not -50 <= float(thermal) <= 150:
                        raise ValueError("thermal_c is outside accepted bounds")
                    sensor_thermal_celsius = float(thermal)
                    sensor_thermal_source = thermal_source
                    scattering_detected = scattering
                    ekf.adapt_to_environment(
                        scattering_detected, sensor_thermal_celsius
                    )
                    if sensor_thermal_celsius > CONFIG.THERMAL_CRITICAL_C:
                        current_mode = "MINIMAL"
                    elif (
                        scattering_detected
                        or sensor_thermal_celsius > CONFIG.THERMAL_WARNING_C
                    ):
                        current_mode = "DEGRADED"
                    else:
                        current_mode = "FULL"

                elif msg_type == "aura_voxels":
                    # RTI-Voxel der CT45P-App → an alle Visualizer-Clients weiterreichen
                    await manager.broadcast_json(data)

                elif msg_type == "aura_heatmap":
                    await manager.broadcast_json(data)

                elif msg_type == "position_update":
                    # Fusionierte Triangulations-Position → Visualizer + Persistenz
                    await manager.broadcast_json(data)
                    x = float(payload.get("x", 0.0))
                    y = float(payload.get("y", 0.0))
                    z = float(payload.get("z", 0.0))
                    accuracy = float(payload.get("accuracy_m", 1.0))
                    db.save_transform(
                        device_id,
                        (x, y, z),
                        (accuracy, accuracy),
                        {
                            "kind": "triangulation",
                            "source": payload.get("source", "unknown"),
                            "confidence": payload.get("confidence", 0.0),
                        },
                    )

                elif msg_type == "triangulation_anchors":
                    await manager.broadcast_json(data)

                elif msg_type == "network_devices_update":
                    # Scan-Zyklus der App → Change-/Anomalie-Erkennung + Broadcast
                    changes = device_tracker.update(payload.get("devices", []))
                    await manager.broadcast_json(
                        {"type": "network_devices", "payload": changes}
                    )

                elif msg_type == "annotation_update":
                    # Kollaborative Annotation (Live-Sync) → alle Teilnehmer
                    await manager.broadcast_json(data)

                elif msg_type == "devices_update":
                    # Geräte-Ingest der App (DeviceSync) → Registry → Broadcast
                    for dev_data in payload.get("devices", []):
                        device_registry.upsert(Device.from_dict(dev_data))
                    device_registry.mark_stale()
                    await manager.broadcast_json(_devices_payload())

                elif msg_type == "device_action":
                    # Client → Agent: Geräteaktion ausführen + Ergebnis broadcasten
                    result = device_action_engine.execute(
                        payload.get("device_id"),
                        payload.get("action"),
                        payload.get("params") or {},
                    )
                    await manager.broadcast_json(
                        {"type": "device_action_result", "payload": result.to_dict()}
                    )

                elif msg_type == "network_traffic":
                    # Live-Traffic-Ingest (DeviceSync/Adapter) → Broadcast
                    flows = [TrafficFlow.from_dict(f) for f in payload.get("flows", [])]
                    if flows:
                        await manager.broadcast_json(_traffic_broadcast(flows))

                else:
                    raise ValueError("unsupported message type")

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
                            "thermal": sensor_thermal_celsius,
                        },
                    )

            except ValueError as exc:
                logger.warning("Ungültige WS-Nachricht: %s", exc)
                await websocket.send_text(
                    json.dumps({"type": "error", "code": "INVALID_MESSAGE"})
                )

    except WebSocketDisconnect:
        logger.info("Client getrennt")
    except Exception as error:  # noqa: BLE001
        logger.error("WebSocket-Fehler: %s", error, exc_info=True)
    finally:
        manager.disconnect(websocket)


# ─── Main ────────────────────────────────────────────────────
def run_server(config=CONFIG) -> None:
    if config.REQUIRE_TLS and (not config.TLS_CERTFILE or not config.TLS_KEYFILE):
        raise SystemExit(
            "TLS is required: configure AGENT_TLS_CERTFILE and AGENT_TLS_KEYFILE"
        )
    uvicorn.run(
        app,
        host=config.API_HOST,
        port=config.API_PORT,
        ssl_certfile=config.TLS_CERTFILE or None,
        ssl_keyfile=config.TLS_KEYFILE or None,
    )


if __name__ == "__main__":
    run_server()
