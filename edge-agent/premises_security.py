"""Betriebsgelände-Sicherheit (passiv, eigenes Gelände) — docs/PREMISES_EDM.md.

Kontext: Das System läuft ausschließlich auf dem eigenen, EDM-verwalteten
Betriebsgelände (eigene CT45P-Geräte, eigene Flotte, eigene Infrastruktur).
Dieses Modul setzt die PASSIVEN Erkennungs-Stufen der honeyKart-Spezifikation
um ("Bekannte vs. Unbekannte Geräte", "Beobachtungs-Sicherheit", passive
Fremdgeräte-Erkennung):

  STUFE 1 (implementiert): Netzwerk-/BLE-Sichtungen gegen die eigenen
      Register (Flotte, gebundene Tokens, Device-DB/OUI) klassifizieren:
      own / infra / unknown. Unbekannte Geräte → Alert (WS + REST).
  STUFE 2 (Datenvertrag): Magnetfeld-/IR-Sensorberichte der eigenen Geräte
      entgegennehmen und im Überblick ausweisen (Hardware-Auswertung bleibt
      der App-Ebene vorbehalten).
  Anomalie-Erkennung (passiv): plötzliches Verschwinden vieler eigener
      Geräte (mögliche Störung) wird als Warnung gemeldet — DETEKTION, keine
      Gegenmaßnahmen.

Bewusst NICHT enthalten: aktive Stör-/Angriffswerkzeuge (Deauth, Beacon-Spam,
Reconnect-Flooding, Rogue-AP) — unabhängig vom Einsatzort.
"""
from __future__ import annotations

import time
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Set


@dataclass
class PremisesDevice:
    """Beobachtetes Gerät mit Klassifikation."""

    id: str
    kind: str                  # fleet | accessory | network
    name: str
    status: str                # own | infra | unknown
    reason: str
    vendor: Optional[str] = None
    rssi: Optional[int] = None
    first_seen: float = field(default_factory=time.time)
    last_seen: float = field(default_factory=time.time)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "kind": self.kind,
            "name": self.name,
            "status": self.status,
            "reason": self.reason,
            "vendor": self.vendor,
            "rssi": self.rssi,
            "first_seen": round(self.first_seen, 3),
            "last_seen": round(self.last_seen, 3),
        }


@dataclass
class SensorReport:
    """Passiver Sensorbericht der Stufe 2 (Magnetfeld/IR) eines eigenen Geräts."""

    device_id: str
    kind: str                  # magnetometer | ir | rf_power
    value: float
    unit: str
    timestamp: float = field(default_factory=time.time)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "device_id": self.device_id,
            "kind": self.kind,
            "value": round(self.value, 3),
            "unit": self.unit,
            "timestamp": round(self.timestamp, 3),
        }


class PremisesSecurity:
    """Passive Fremdgeräte-Erkennung auf dem eigenen Betriebsgelände."""

    def __init__(self, drop_alert_ratio: float = 0.5, drop_alert_min: int = 3) -> None:
        self._observed: Dict[str, PremisesDevice] = {}
        self._sensor_reports: List[SensorReport] = []
        self._known_own_ids: Set[str] = set()
        self._known_infra_ids: Set[str] = set()
        self.drop_alert_ratio = float(drop_alert_ratio)
        self.drop_alert_min = int(drop_alert_min)
        self._last_own_counts: Dict[str, float] = {}
        self.alerts: List[Dict[str, Any]] = []

    # ─── Registerpflege ───────────────────────────────────────
    def register_own(self, device_id: str) -> None:
        self._known_own_ids.add(device_id)

    def register_infra(self, device_id: str) -> None:
        self._known_infra_ids.add(device_id)

    # ─── Beobachtung ──────────────────────────────────────────
    def observe(
        self,
        device_id: str,
        kind: str,
        name: str,
        vendor: Optional[str] = None,
        rssi: Optional[int] = None,
        own_ids: Optional[Set[str]] = None,
        infra_ids: Optional[Set[str]] = None,
    ) -> PremisesDevice:
        own = (own_ids or self._known_own_ids) | self._known_own_ids
        infra = (infra_ids or self._known_infra_ids) | self._known_infra_ids
        now = time.time()

        existing = self._observed.get(device_id)
        if existing is not None:
            existing.last_seen = now
            existing.rssi = rssi if rssi is not None else existing.rssi
            existing.status, existing.reason = self._classify(device_id, own, infra, existing.status)
            return existing

        if device_id in own:
            status, reason = "own", "eigenes gebundenes Gerät"
        elif device_id in infra:
            status, reason = "infra", "bekannte Infrastruktur (OUI/Register)"
        else:
            status, reason = "unknown", "kein eigener Eintrag — Fremdgerät möglich"

        entry = PremisesDevice(
            id=device_id, kind=kind, name=name or device_id,
            status=status, reason=reason, vendor=vendor, rssi=rssi,
        )
        self._observed[device_id] = entry
        return entry

    @staticmethod
    def _classify(device_id: str, own: Set[str], infra: Set[str], current: str) -> tuple:
        if device_id in own:
            return "own", "eigenes gebundenes Gerät"
        if device_id in infra:
            return "infra", "bekannte Infrastruktur (OUI/Register)"
        return current if current == "unknown" else "unknown", "kein eigener Eintrag — Fremdgerät möglich"

    def evaluate(self, own_ids: Set[str], infra_ids: Set[str]) -> List[PremisesDevice]:
        """Bewertet alle Sichtungen neu; liefert NEUE unbekannte Geräte."""
        new_unknown: List[PremisesDevice] = []
        for device in self._observed.values():
            before = device.status
            device.status, device.reason = self._classify(device.id, own_ids, infra_ids, device.status)
            if device.status == "unknown" and before != "unknown":
                new_unknown.append(device)
        return new_unknown

    def unknown_devices(self) -> List[PremisesDevice]:
        return [d for d in self._observed.values() if d.status == "unknown"]

    def overview(self) -> Dict[str, Any]:
        devices = list(self._observed.values())
        counts = {"own": 0, "infra": 0, "unknown": 0}
        for d in devices:
            counts[d.status] = counts.get(d.status, 0) + 1
        return {
            "observed": len(devices),
            "counts": counts,
            "unknown": [d.to_dict() for d in devices if d.status == "unknown"],
            "sensor_reports": [s.to_dict() for s in self._sensor_reports[-50:]],
            "alerts": self.alerts[-50:],
        }

    # ─── Stufe 2: Sensorberichte (passiv) ─────────────────────
    def add_sensor_report(self, report: SensorReport) -> SensorReport:
        self._sensor_reports.append(report)
        if len(self._sensor_reports) > 200:
            self._sensor_reports = self._sensor_reports[-200:]
        return report

    # ─── Anomalie: plötzlicher Schwund eigener Geräte ─────────
    def check_own_device_drop(self, now_active_own: int) -> Optional[Dict[str, Any]]:
        """Passive Störungs-DETEKTION: viele eigene Geräte plötzlich weg."""
        now = time.time()
        baseline = self._last_own_counts.get("count")
        self._last_own_counts["count"] = now_active_own
        if baseline is None or baseline <= 0 or now_active_own >= baseline:
            return None
        dropped = baseline - now_active_own
        if dropped >= self.drop_alert_min and dropped / baseline >= self.drop_alert_ratio:
            alert = {
                "type": "own_device_drop",
                "dropped": dropped,
                "baseline": baseline,
                "remaining": now_active_own,
                "timestamp": round(now, 3),
                "note": "passive Detektion — mögliche Störung des eigenen Netzes",
            }
            self.alerts.append(alert)
            return alert
        return None
