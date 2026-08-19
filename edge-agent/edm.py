"""EDM-Geräte-Lebenszyklus für EIGENE, verwaltete Geräte — docs/PREMISES_EDM.md.

Kontext: Die Honeywell CT45P sind EDM-/MDM-verwaltete Eigengeräte des
Betriebsgeländes (kein Drittzugriff möglich — Provisioning nur durch die
eigene Administration mit Gerätekenntnis).

Dieses Modul bildet den legitimen Lebenszyklus ab:

    ENROLLED → PROVISIONED → QUARANTINED → RESET_PENDING → RESET

- Reset-Empfehlung erfolgt ausschließlich über den dokumentierten,
  legitimen Weg: Honeywell Provisioning Mode / OEMConfig (EDM) — die
  Plattform erzeugt dafür einen auditierten, admin-gebundenen
  Reset-Auftrag. Es gibt KEINEN Google-FRP-Bypass für Fremdgeräte.
- Jede Zustandsänderung wird audit-logiert (wer, wann, von, nach, Grund).
"""
from __future__ import annotations

import time
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

VALID_STATES = {"ENROLLED", "PROVISIONED", "QUARANTINED", "RESET_PENDING", "RESET"}

# Legitime Übergänge (Reset nur über den EDM-Prozess, nicht direkt)
ALLOWED_TRANSITIONS = {
    "ENROLLED": {"PROVISIONED", "QUARANTINED"},
    "PROVISIONED": {"QUARANTINED", "RESET_PENDING"},
    "QUARANTINED": {"PROVISIONED", "RESET_PENDING"},
    "RESET_PENDING": {"RESET", "PROVISIONED"},
    "RESET": {"ENROLLED"},
}


@dataclass
class EdmDevice:
    device_id: str
    state: str = "ENROLLED"
    serial: Optional[str] = None
    model: Optional[str] = None
    assigned_to: Optional[str] = None
    location: Optional[str] = None
    last_change: float = field(default_factory=time.time)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "device_id": self.device_id,
            "state": self.state,
            "serial": self.serial,
            "model": self.model,
            "assigned_to": self.assigned_to,
            "location": self.location,
            "last_change": round(self.last_change, 3),
        }


@dataclass
class EdmAuditEntry:
    timestamp: float
    device_id: str
    actor: str
    action: str
    from_state: Optional[str]
    to_state: Optional[str]
    reason: str

    def to_dict(self) -> Dict[str, Any]:
        return {
            "timestamp": round(self.timestamp, 3),
            "device_id": self.device_id,
            "actor": self.actor,
            "action": self.action,
            "from_state": self.from_state,
            "to_state": self.to_state,
            "reason": self.reason,
        }


class EdmRegistry:
    """Lebenszyklus-Registry der eigenen, EDM-verwalteten Geräte."""

    def __init__(self, max_audit: int = 1000) -> None:
        self._devices: Dict[str, EdmDevice] = {}
        self._audit: List[EdmAuditEntry] = []
        self.max_audit = int(max_audit)

    # ─── Mutationen ───────────────────────────────────────────
    def upsert(
        self,
        device_id: str,
        actor: str,
        serial: Optional[str] = None,
        model: Optional[str] = None,
        assigned_to: Optional[str] = None,
        location: Optional[str] = None,
    ) -> EdmDevice:
        device = self._devices.get(device_id)
        if device is None:
            device = EdmDevice(device_id=device_id)
            self._devices[device_id] = device
            self._log(actor, device_id, "REGISTER", None, device.state, "Gerät registriert")
        device.serial = serial or device.serial
        device.model = model or device.model
        device.assigned_to = assigned_to or device.assigned_to
        device.location = location or device.location
        device.last_change = time.time()
        return device

    def set_state(
        self,
        device_id: str,
        new_state: str,
        actor: str,
        reason: str,
    ) -> EdmDevice:
        if new_state not in VALID_STATES:
            raise ValueError(f"Unbekannter Zustand: {new_state}")
        device = self._devices.get(device_id)
        if device is None:
            raise KeyError(f"Gerät {device_id} nicht registriert")
        if new_state not in ALLOWED_TRANSITIONS.get(device.state, set()):
            raise PermissionError(
                f"Übergang {device.state} → {new_state} nicht erlaubt "
                "(Reset nur über den EDM-Prozess RESET_PENDING → RESET)"
            )
        if not reason or not reason.strip():
            raise ValueError("reason ist für Zustandsänderungen erforderlich")
        old = device.state
        device.state = new_state
        device.last_change = time.time()
        self._log(actor, device_id, "SET_STATE", old, new_state, reason)
        return device

    def request_reset(self, device_id: str, actor: str, reason: str) -> EdmDevice:
        """Legitimer Reset-Auftrag: Gerät → RESET_PENDING (EDM/Provisioning Mode)."""
        return self.set_state(device_id, "RESET_PENDING", actor, reason)

    def confirm_reset(self, device_id: str, actor: str, reason: str) -> EdmDevice:
        """Bestätigung, dass der EDM-Reset (Honeywell Provisioning Mode)
        durch die Administration durchgeführt wurde."""
        device = self._devices.get(device_id)
        if device is None:
            raise KeyError(f"Gerät {device_id} nicht registriert")
        if device.state != "RESET_PENDING":
            raise PermissionError("confirm_reset nur aus RESET_PENDING erlaubt")
        return self.set_state(device_id, "RESET", actor, reason)

    # ─── Abfragen ─────────────────────────────────────────────
    def get(self, device_id: str) -> Optional[EdmDevice]:
        return self._devices.get(device_id)

    def list_devices(self) -> List[EdmDevice]:
        return list(self._devices.values())

    def list_audit(self, limit: int = 100) -> List[EdmAuditEntry]:
        return self._audit[-limit:]

    def stats(self) -> Dict[str, int]:
        out: Dict[str, int] = {}
        for d in self._devices.values():
            out[d.state] = out.get(d.state, 0) + 1
        return out

    def _log(self, actor: str, device_id: str, action: str,
             from_state: Optional[str], to_state: Optional[str], reason: str) -> None:
        self._audit.append(
            EdmAuditEntry(
                timestamp=time.time(),
                device_id=device_id,
                actor=actor,
                action=action,
                from_state=from_state,
                to_state=to_state,
                reason=reason[:500],
            )
        )
        if len(self._audit) > self.max_audit:
            self._audit = self._audit[-self.max_audit:]
