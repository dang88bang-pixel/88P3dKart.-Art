"""Gateway enrollment, credential hashing, and short-lived session authentication."""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import secrets
import sqlite3
import time
from collections import OrderedDict, deque
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
from threading import Lock
from typing import Any, Callable, Deque, Hashable, Iterator, Optional


class AuthenticationError(ValueError):
    pass


class AuthenticationUnavailable(RuntimeError):
    pass


class AttemptLimitExceeded(RuntimeError):
    def __init__(self, retry_after_s: int):
        super().__init__("credential attempts temporarily exhausted")
        self.retry_after_s = max(1, retry_after_s)


class _SlidingWindowLimiter:
    """Thread-safe bounded sliding-window failure counter."""

    def __init__(
        self,
        max_failures: int,
        window_s: int,
        max_keys: int,
        clock: Callable[[], float] = time.monotonic,
    ):
        if not 1 <= max_failures <= 10_000:
            raise ValueError("max_failures is outside accepted bounds")
        if not 1 <= window_s <= 86_400:
            raise ValueError("window_s is outside accepted bounds")
        if not 1 <= max_keys <= 1_000_000:
            raise ValueError("max_keys is outside accepted bounds")
        self.max_failures = max_failures
        self.window_s = window_s
        self.max_keys = max_keys
        self._clock = clock
        self._entries: OrderedDict[Hashable, Deque[float]] = OrderedDict()
        self._lock = Lock()

    def ensure_allowed(self, key: Hashable) -> None:
        now = self._clock()
        with self._lock:
            failures = self._active_failures(key, now)
            if len(failures) >= self.max_failures:
                retry_after = int(self.window_s - (now - failures[0]) + 0.999)
                raise AttemptLimitExceeded(retry_after)

    def failure(self, key: Hashable) -> None:
        now = self._clock()
        with self._lock:
            failures = self._active_failures(key, now)
            failures.append(now)
            self._entries.move_to_end(key)
            while len(self._entries) > self.max_keys:
                self._entries.popitem(last=False)

    def success(self, key: Hashable) -> None:
        with self._lock:
            self._entries.pop(key, None)

    def _active_failures(self, key: Hashable, now: float) -> Deque[float]:
        failures = self._entries.get(key)
        if failures is None:
            failures = deque()
            self._entries[key] = failures
            while len(self._entries) > self.max_keys:
                self._entries.popitem(last=False)
        cutoff = now - self.window_s
        while failures and failures[0] <= cutoff:
            failures.popleft()
        return failures


class CredentialAttemptControls:
    """Bound credential guessing by direct peer and credential subject.

    Caller-controlled forwarding headers are deliberately not accepted here. The
    application supplies the direct ASGI peer address. A peer-wide limiter also
    prevents rotating unbounded subject identifiers to evade the specific key.
    Successful authentication clears only the specific key; aggregate failures
    age out naturally so a known credential cannot reset attack traffic.
    """

    def __init__(
        self,
        max_failures: int = 5,
        window_s: int = 60,
        max_keys: int = 4096,
        aggregate_multiplier: int = 5,
        clock: Callable[[], float] = time.monotonic,
    ):
        if not 1 <= aggregate_multiplier <= 100:
            raise ValueError("aggregate_multiplier is outside accepted bounds")
        self._specific = _SlidingWindowLimiter(
            max_failures, window_s, max_keys, clock
        )
        self._aggregate = _SlidingWindowLimiter(
            max_failures * aggregate_multiplier,
            window_s,
            max_keys,
            clock,
        )

    @staticmethod
    def _specific_key(flow: str, peer: str, subject: str) -> tuple[str, str, str]:
        return flow, peer, subject

    @staticmethod
    def _aggregate_key(flow: str, peer: str) -> tuple[str, str]:
        return flow, peer

    def ensure_allowed(self, flow: str, peer: str, subject: str) -> None:
        self._aggregate.ensure_allowed(self._aggregate_key(flow, peer))
        self._specific.ensure_allowed(self._specific_key(flow, peer, subject))

    def failure(self, flow: str, peer: str, subject: str) -> None:
        self._aggregate.failure(self._aggregate_key(flow, peer))
        self._specific.failure(self._specific_key(flow, peer, subject))

    def success(self, flow: str, peer: str, subject: str) -> None:
        self._specific.success(self._specific_key(flow, peer, subject))


@dataclass(frozen=True)
class Principal:
    subject: str
    role: str
    session_id: str


@dataclass(frozen=True)
class EnrollmentCode:
    device_id: str
    code: str
    expires_utc_s: int


@dataclass(frozen=True)
class DeviceCredential:
    device_id: str
    secret: str


class CredentialStore:
    """SQLite credential storage; raw enrollment/device secrets are never persisted."""

    def __init__(self, db_path: str):
        self.db_path = db_path
        Path(db_path).parent.mkdir(parents=True, exist_ok=True)
        self._initialize()
        try:
            os.chmod(db_path, 0o600)
        except OSError:
            pass

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.db_path, timeout=10.0)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA journal_mode=WAL")
        connection.execute("PRAGMA synchronous=FULL")
        return connection

    @contextmanager
    def _transaction(self) -> Iterator[sqlite3.Connection]:
        connection = self._connect()
        try:
            connection.execute("BEGIN IMMEDIATE")
            yield connection
            connection.commit()
        except BaseException:
            connection.rollback()
            raise
        finally:
            connection.close()

    def _initialize(self) -> None:
        with self._transaction() as connection:
            connection.executescript(
                """
                CREATE TABLE IF NOT EXISTS enrollment_codes (
                    code_id TEXT PRIMARY KEY,
                    device_id TEXT NOT NULL,
                    secret_salt BLOB NOT NULL,
                    secret_hash BLOB NOT NULL,
                    expires_utc_s INTEGER NOT NULL,
                    consumed_utc_s INTEGER,
                    created_utc_s INTEGER NOT NULL
                );
                CREATE INDEX IF NOT EXISTS idx_enrollment_device
                    ON enrollment_codes(device_id, expires_utc_s);

                CREATE TABLE IF NOT EXISTS device_credentials (
                    device_id TEXT PRIMARY KEY,
                    secret_salt BLOB NOT NULL,
                    secret_hash BLOB NOT NULL,
                    generation INTEGER NOT NULL CHECK(generation >= 1),
                    enabled INTEGER NOT NULL CHECK(enabled IN (0, 1)),
                    created_utc_s INTEGER NOT NULL,
                    rotated_utc_s INTEGER NOT NULL
                );
                """
            )

    def create_enrollment_code(
        self,
        device_id: str,
        now_utc_s: int,
        ttl_s: int,
    ) -> EnrollmentCode:
        _validate_device_id(device_id)
        if not 60 <= ttl_s <= 86_400:
            raise ValueError("enrollment-code TTL must be between 60 and 86400 seconds")
        code = _token(24)
        salt, digest = _hash_secret(code)
        expires = now_utc_s + ttl_s
        with self._transaction() as connection:
            connection.execute(
                """INSERT INTO enrollment_codes
                   (code_id, device_id, secret_salt, secret_hash, expires_utc_s,
                    consumed_utc_s, created_utc_s)
                   VALUES (?, ?, ?, ?, ?, NULL, ?)""",
                (_token(16), device_id, salt, digest, expires, now_utc_s),
            )
            connection.execute(
                """DELETE FROM enrollment_codes
                   WHERE expires_utc_s < ? OR consumed_utc_s IS NOT NULL""",
                (now_utc_s - 86_400,),
            )
        return EnrollmentCode(device_id, code, expires)

    def claim_enrollment(
        self,
        device_id: str,
        code: str,
        now_utc_s: int,
    ) -> DeviceCredential:
        _validate_device_id(device_id)
        if not 20 <= len(code) <= 200:
            raise AuthenticationError("invalid enrollment credential")
        with self._transaction() as connection:
            rows = connection.execute(
                """SELECT code_id, secret_salt, secret_hash
                   FROM enrollment_codes
                   WHERE device_id=? AND consumed_utc_s IS NULL AND expires_utc_s>=?
                   ORDER BY created_utc_s DESC LIMIT 8""",
                (device_id, now_utc_s),
            ).fetchall()
            matched: Optional[sqlite3.Row] = None
            for row in rows:
                if _verify_secret(code, row["secret_salt"], row["secret_hash"]):
                    matched = row
                    break
            if matched is None:
                raise AuthenticationError("invalid enrollment credential")

            device_secret = _token(32)
            salt, digest = _hash_secret(device_secret)
            previous = connection.execute(
                "SELECT generation FROM device_credentials WHERE device_id=?",
                (device_id,),
            ).fetchone()
            generation = 1 if previous is None else previous["generation"] + 1
            connection.execute(
                """INSERT INTO device_credentials
                   (device_id, secret_salt, secret_hash, generation, enabled,
                    created_utc_s, rotated_utc_s)
                   VALUES (?, ?, ?, ?, 1, ?, ?)
                   ON CONFLICT(device_id) DO UPDATE SET
                       secret_salt=excluded.secret_salt,
                       secret_hash=excluded.secret_hash,
                       generation=excluded.generation,
                       enabled=1,
                       rotated_utc_s=excluded.rotated_utc_s""",
                (device_id, salt, digest, generation, now_utc_s, now_utc_s),
            )
            updated = connection.execute(
                """UPDATE enrollment_codes SET consumed_utc_s=?
                   WHERE code_id=? AND consumed_utc_s IS NULL""",
                (now_utc_s, matched["code_id"]),
            ).rowcount
            if updated != 1:
                raise AuthenticationError("enrollment credential was already consumed")
        return DeviceCredential(device_id, device_secret)

    def authenticate_device(self, device_id: str, secret: str) -> int:
        _validate_device_id(device_id)
        if not 32 <= len(secret) <= 256:
            raise AuthenticationError("invalid device credential")
        connection = self._connect()
        try:
            row = connection.execute(
                """SELECT secret_salt, secret_hash, generation, enabled
                   FROM device_credentials WHERE device_id=?""",
                (device_id,),
            ).fetchone()
        finally:
            connection.close()
        # Run one scrypt even for unknown users to reduce account-existence timing leaks.
        if row is None:
            _hash_secret(secret, b"\0" * 16)
            raise AuthenticationError("invalid device credential")
        valid = _verify_secret(secret, row["secret_salt"], row["secret_hash"])
        if not valid or row["enabled"] != 1:
            raise AuthenticationError("invalid device credential")
        return int(row["generation"])

    def credential_is_current(self, device_id: str, generation: int) -> bool:
        connection = self._connect()
        try:
            row = connection.execute(
                """SELECT generation, enabled FROM device_credentials
                   WHERE device_id=?""",
                (device_id,),
            ).fetchone()
            return bool(
                row is not None
                and row["enabled"] == 1
                and row["generation"] == generation
            )
        finally:
            connection.close()

    def disable_device(self, device_id: str) -> bool:
        with self._transaction() as connection:
            return (
                connection.execute(
                    "UPDATE device_credentials SET enabled=0 WHERE device_id=?",
                    (device_id,),
                ).rowcount
                == 1
            )


class GatewaySecurity:
    def __init__(
        self,
        store: CredentialStore,
        signing_secret: str,
        admin_bootstrap_token: str,
        session_ttl_s: int = 900,
    ):
        self.store = store
        self._signing_key = signing_secret.encode("utf-8")
        self._admin_token = admin_bootstrap_token
        self.session_ttl_s = session_ttl_s
        self.available = (
            len(self._signing_key) >= 32
            and len(self._admin_token) >= 32
            and 60 <= session_ttl_s <= 86_400
        )

    def require_admin(self, bearer_token: str) -> Principal:
        self._require_available()
        if not hmac.compare_digest(bearer_token, self._admin_token):
            raise AuthenticationError("invalid bearer credential")
        return Principal("bootstrap-admin", "admin", "bootstrap")

    def issue_device_session(
        self,
        device_id: str,
        device_secret: str,
        now_utc_s: Optional[int] = None,
    ) -> tuple[str, int]:
        self._require_available()
        now = int(time.time()) if now_utc_s is None else now_utc_s
        generation = self.store.authenticate_device(device_id, device_secret)
        expires = now + self.session_ttl_s
        payload = {
            "v": 1,
            "sub": device_id,
            "role": "device",
            "generation": generation,
            "iat": now,
            "exp": expires,
            "jti": _token(16),
        }
        encoded = _b64url(_canonical_json(payload).encode("utf-8"))
        signature = _b64url(hmac.new(self._signing_key, encoded.encode(), hashlib.sha256).digest())
        return f"{encoded}.{signature}", expires

    def authenticate_bearer(
        self,
        bearer_token: str,
        now_utc_s: Optional[int] = None,
    ) -> Principal:
        self._require_available()
        if hmac.compare_digest(bearer_token, self._admin_token):
            return Principal("bootstrap-admin", "admin", "bootstrap")
        now = int(time.time()) if now_utc_s is None else now_utc_s
        try:
            encoded, supplied_signature = bearer_token.split(".", 1)
            expected_signature = _b64url(
                hmac.new(self._signing_key, encoded.encode(), hashlib.sha256).digest()
            )
            if not hmac.compare_digest(supplied_signature, expected_signature):
                raise AuthenticationError("invalid bearer credential")
            payload = json.loads(_b64url_decode(encoded))
            if not isinstance(payload, dict):
                raise AuthenticationError("invalid bearer credential")
            if payload.get("v") != 1 or payload.get("role") != "device":
                raise AuthenticationError("invalid bearer credential")
            issued = int(payload["iat"])
            expires = int(payload["exp"])
            if issued > now + 30 or now >= expires or expires - issued > 86_400:
                raise AuthenticationError("expired bearer credential")
            device_id = str(payload["sub"])
            generation = int(payload["generation"])
            session_id = str(payload["jti"])
            _validate_device_id(device_id)
            if not self.store.credential_is_current(device_id, generation):
                raise AuthenticationError("revoked bearer credential")
            return Principal(device_id, "device", session_id)
        except AuthenticationError:
            raise
        except (KeyError, TypeError, ValueError, UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise AuthenticationError("invalid bearer credential") from exc

    def _require_available(self) -> None:
        if not self.available:
            raise AuthenticationUnavailable("gateway authentication is not configured")


def _validate_device_id(device_id: str) -> None:
    if not 1 <= len(device_id) <= 160:
        raise ValueError("device_id length is invalid")
    if any(character not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._:-" for character in device_id):
        raise ValueError("device_id contains unsupported characters")


def _hash_secret(secret: str, salt: Optional[bytes] = None) -> tuple[bytes, bytes]:
    actual_salt = secrets.token_bytes(16) if salt is None else salt
    digest = hashlib.scrypt(
        secret.encode("utf-8"),
        salt=actual_salt,
        n=2**14,
        r=8,
        p=1,
        dklen=32,
    )
    return actual_salt, digest


def _verify_secret(secret: str, salt: bytes, expected: bytes) -> bool:
    _, actual = _hash_secret(secret, salt)
    return hmac.compare_digest(actual, expected)


def _token(byte_count: int) -> str:
    return secrets.token_urlsafe(byte_count)


def _canonical_json(document: dict[str, Any]) -> str:
    return json.dumps(document, sort_keys=True, separators=(",", ":"), allow_nan=False)


def _b64url(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def _b64url_decode(value: str) -> bytes:
    padding = "=" * (-len(value) % 4)
    return base64.urlsafe_b64decode(value + padding)
