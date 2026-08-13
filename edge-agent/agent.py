"""Edge-Agent: FastAPI + WebSocket + EKF/UWB/ICP/Pipeline-Integration."""
import asyncio
import hashlib
import json
import logging
import time
from contextlib import suppress

import numpy as np
import uvicorn
from contextlib import asynccontextmanager
from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request, Response, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from fastapi.middleware.trustedhost import TrustedHostMiddleware
from pydantic import ValidationError

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
from ekf_fusion import AdaptiveEKF
from icp_merger import ICPMerger
from models import (
    ASSET_ID_PATTERN,
    POLICY_ID_PATTERN,
    AlarmEvidenceRequest,
    AlarmPolicyRequest,
    AlarmSnoozeRequest,
    BleTokenUpdate,
    EkfState,
    EnrollmentClaimRequest,
    EnrollmentCodeRequest,
    LidarFrame,
    MergeRequest,
    MmwaveTarget,
    PipelineRequest,
    ScenarioConfig,
    SessionRequest,
    UwbPhaseData,
)
from pipeline import DataPipeline
from pointcloud_compressor import PointCloudCompressor
from security import (
    AttemptLimitExceeded,
    AuthenticationError,
    AuthenticationUnavailable,
    CredentialAttemptControls,
    CredentialStore,
    GatewaySecurity,
    Principal,
)
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

    async def broadcast_json(self, data: dict, subject: str) -> int:
        text = json.dumps(data, separators=(",", ":"), allow_nan=False)
        delivered = 0
        for connection in self._authorized_connections(subject):
            try:
                await connection.send_text(text)
                delivered += 1
            except Exception:
                self.disconnect(connection)
        return delivered

    def broadcast_json_sync(self, data: dict, subject: str):
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
    result = pipeline.run(
        request.points,
        source=request.metadata.get("source", "lidar"),
        quality=request.metadata.get("quality", 1.0),
        metadata=request.metadata,
    )
    result["device_id"] = request.device_id
    return result


@app.get("/api/v1/health")
async def health(request: Request):
    authentication_ready = request.app.state.security.available
    return {
        "status": "ok" if authentication_ready else "degraded",
        "authentication": "ready" if authentication_ready else "unconfigured",
    }


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
                _require_device_scope(principal, payload_device_id)
                device_id = payload_device_id

                if msg_type == "handshake":
                    await websocket.send_text(
                        json.dumps({"type": "handshake_ack", "device_id": device_id})
                    )

                elif msg_type == "lidar":
                    frame = LidarFrame(**payload)
                    points = np.asarray(frame.points, dtype=np.float32).reshape(-1, 3)
                    if len(points) > 0:
                        ekf.update_lidar(points[0])
                    await manager.broadcast_binary(
                        PointCloudCompressor.compress(points), device_id
                    )

                elif msg_type == "mmwave":
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
                    global thermal_celsius, scattering_detected, current_mode
                    thermal = payload.get("thermal_c")
                    scattering = payload.get("scattering")
                    if not isinstance(thermal, (int, float)) or not isinstance(scattering, bool):
                        raise ValueError("thermal_c and scattering are required")
                    if not -50 <= float(thermal) <= 150:
                        raise ValueError("thermal_c is outside accepted bounds")
                    thermal_celsius = float(thermal)
                    scattering_detected = scattering
                    ekf.adapt_to_environment(scattering_detected, thermal_celsius)
                    if thermal_celsius > CONFIG.THERMAL_CRITICAL_C:
                        current_mode = "MINIMAL"
                    elif scattering_detected or thermal_celsius > CONFIG.THERMAL_WARNING_C:
                        current_mode = "DEGRADED"
                    else:
                        current_mode = "FULL"

                else:
                    raise ValueError("unsupported message type")

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
            except HTTPException:
                await websocket.close(code=4403, reason="device scope denied")
                return
            except (json.JSONDecodeError, ValidationError, ValueError, TypeError) as error:
                logger.warning("Rejected WebSocket message from %s: %s", device_id, error)
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
