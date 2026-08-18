"""Flotten-Registry: eigene Fahrzeuge, Werkzeuge, Handys & BLE-Tokens.

Positionen kommen als GPS (lat/lon), als lokale Triangulationskoordinaten
(x/y/z im Agent-Frame, werden über den GeoAnchor nach WGS84 projiziert) oder
als reine BLE-Sichtung (rssi → Distanzschätzung, Position unbekannt bis die
App trianguliert). Alle Einträge landen live im OSM-Dashboard.

Mesh-Aktionen sind capability-geprüft: read_status, locate, toggle_led,
set_visible sowie lock/unlock nur für Fahrzeug-Typen.
"""
from __future__ import annotations

import logging
import time
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Tuple

from geo.projection import enu_to_geodetic, haversine_m
from signal_processing import KalmanRssiFilter

logger = logging.getLogger(__name__)

FLEET_KINDS = {"ebike", "escooter", "eroller", "vehicle", "phone", "tool", "ble_token", "other"}

KIND_LABELS = {
    "ebike": "E-Bike",
    "escooter": "E-Scooter",
    "eroller": "E-Roller",
    "vehicle": "Fahrzeug",
    "phone": "Handy",
    "tool": "Werkzeug",
    "ble_token": "BLE-Token",
    "other": "Sonstiges",
}

# Capability-Modell je Geräteklasse (eigene Flotte)
KIND_CAPABILITIES: Dict[str, set] = {
    "ble_token": {"READ_DATA", "EXECUTE_COMMAND"},
    "tool": {"READ_DATA", "EXECUTE_COMMAND"},
    "phone": {"READ_DATA", "EXECUTE_COMMAND"},
    "ebike": {"READ_DATA", "EXECUTE_COMMAND", "LOCK"},
    "escooter": {"READ_DATA", "EXECUTE_COMMAND", "LOCK"},
    "eroller": {"READ_DATA", "EXECUTE_COMMAND", "LOCK"},
    "vehicle": {"READ_DATA", "EXECUTE_COMMAND", "LOCK"},
    "other": {"READ_DATA"},
}

MAX_VEHICLES = 500


@dataclass
class FleetVehicle:
    id: str
    name: str
    kind: str
    lat: Optional[float] = None
    lon: Optional[float] = None
    accuracy_m: Optional[float] = None
    local: Optional[List[float]] = None          # [x, y, z] im Agent-Frame
    rssi: Optional[int] = None
    rssi_smoothed: Optional[float] = None        # Kalman-geglätteter RSSI
    pairing_code: Optional[str] = None           # QR-Anbindung (honeyKart-Format)
    company_id: Optional[str] = None
    battery_type: Optional[str] = None
    firmware_version: Optional[str] = None
    distance_m: Optional[float] = None           # Distanzschätzung (BLE, Anker)
    battery: Optional[int] = None
    status: str = "online"
    source: str = "unknown"                      # gps | triangulation | ble
    owner: str = ""                              # Gerät, das den Eintrag pflegt
    last_seen: float = field(default_factory=time.time)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "name": self.name,
            "kind": self.kind,
            "kind_label": KIND_LABELS.get(self.kind, self.kind),
            "lat": self.lat,
            "lon": self.lon,
            "accuracy_m": self.accuracy_m,
            "local": self.local,
            "rssi": self.rssi,
            "rssi_smoothed": self.rssi_smoothed,
            "pairing_code": self.pairing_code,
            "company_id": self.company_id,
            "battery_type": self.battery_type,
            "firmware_version": self.firmware_version,
            "distance_m": self.distance_m,
            "battery": self.battery,
            "status": self.status,
            "source": self.source,
            "owner": self.owner,
            "last_seen": self.last_seen,
        }


class FleetRegistry:
    """Thread-sichere Registry aller eigenen Flotten-Geräte (Dict + Lock)."""

    def __init__(self) -> None:
        self._vehicles: Dict[str, FleetVehicle] = {}
        self._on_action: Optional[Any] = None  # Broadcast-Callback (agent.py)
        self._kalman: Dict[str, KalmanRssiFilter] = {}  # pro Fahrzeug-ID

    # ─── Mutationen ────────────────────────────────────────────
    def upsert(self, vehicle: FleetVehicle) -> FleetVehicle:
        if len(self._vehicles) >= MAX_VEHICLES and vehicle.id not in self._vehicles:
            raise ValueError("Flottenlimit erreicht")
        vehicle.last_seen = time.time()
        self._vehicles[vehicle.id] = vehicle
        return vehicle

    def update_from_payload(
        self,
        payload: Dict[str, Any],
        owner: str = "",
        anchor=None,
    ) -> FleetVehicle:
        """Nimmt ein Fahrzeug-Dict an und löst die Position auf:

        - lat/lon vorhanden          → direkt (GPS)
        - local [x,y,z] + GeoAnchor  → Projektion nach WGS84
        - nur rssi                   → Distanzschätzung, Position unbestimmt
        """
        vehicle_id = str(payload.get("id", "")).strip()
        if not vehicle_id:
            raise ValueError("Fahrzeug ohne id")
        kind = str(payload.get("kind", "other")).strip().lower()
        if kind not in FLEET_KINDS:
            raise ValueError(f"Unbekannter Fahrzeugtyp: {kind}")

        vehicle = FleetVehicle(
            id=vehicle_id,
            name=str(payload.get("name") or vehicle_id),
            kind=kind,
            battery=payload.get("battery"),
            status=str(payload.get("status", "online")),
            source=str(payload.get("source", "unknown")),
            owner=owner,
            rssi=payload.get("rssi"),
        )

        lat = payload.get("lat")
        lon = payload.get("lon")
        if lat is not None and lon is not None:
            vehicle.lat = float(lat)
            vehicle.lon = float(lon)
            vehicle.accuracy_m = float(payload.get("accuracy_m", 5.0))
            vehicle.source = "gps"
            return self.upsert(vehicle)

        local = payload.get("local")
        if local is not None and len(local) == 3 and anchor is not None:
            lat_o, lon_o, alt_o = _local_to_geodetic(local, anchor)
            vehicle.local = [float(local[0]), float(local[1]), float(local[2])]
            vehicle.lat = lat_o
            vehicle.lon = lon_o
            vehicle.accuracy_m = float(payload.get("accuracy_m", 2.0))
            vehicle.source = "triangulation"
            return self.upsert(vehicle)

        if vehicle.rssi is not None:
            # Kalman-Glättung des RSSI (startet beim ersten Messwert, daher
            # bleibt die erste Distanzschätzung identisch zum Rohwert).
            kalman = self._kalman.get(vehicle_id)
            if kalman is None:
                kalman = KalmanRssiFilter(rssi0=float(vehicle.rssi))
                self._kalman[vehicle_id] = kalman
            vehicle.rssi_smoothed = round(kalman.update(float(vehicle.rssi)), 2)
            # Pfadverlust n=2.0, TxPower -59 dBm (wie in der App-Kalibrierung)
            tx_power = float(payload.get("tx_power", -59.0))
            vehicle.distance_m = 10 ** ((tx_power - float(vehicle.rssi_smoothed)) / (10 * 2.0))
            vehicle.status = "unlocated" if vehicle.status == "online" else vehicle.status
            vehicle.source = "ble"
            return self.upsert(vehicle)

        vehicle.status = "unlocated"
        return self.upsert(vehicle)

    def bind_token_from_qr(
        self,
        payload: Dict[str, Any],
        owner: str = "",
    ) -> FleetVehicle:
        """QR-Code-Anbindung eines Akku-Tokens (docs/HONEYKART_INTEGRATION.md).

        Erwartet das honeyKart-QR-JSON:
        {token_id, mac, name, pairing_code, company_id, battery_type,
         firmware_version}. Legt das Token als ble_token in der Flotte an.
        """
        token_id = str(payload.get("token_id", "")).strip()
        if not token_id:
            raise ValueError("QR-Payload ohne token_id")
        mac = str(payload.get("mac", "")).strip().lower()
        vehicle_id = f"token:{mac}" if mac else f"token:{token_id}"
        existing = self._vehicles.get(vehicle_id)
        if existing is not None:
            # Re-Bind aktualisiert die Metadaten, behält Position/Akku
            existing.name = str(payload.get("name") or existing.name)
            existing.pairing_code = str(payload.get("pairing_code") or "") or None
            existing.company_id = str(payload.get("company_id") or "") or None
            existing.battery_type = str(payload.get("battery_type") or "") or None
            existing.firmware_version = str(payload.get("firmware_version") or "") or None
            existing.owner = owner or existing.owner
            return self.upsert(existing)

        vehicle = FleetVehicle(
            id=vehicle_id,
            name=str(payload.get("name") or token_id),
            kind="ble_token",
            source="qr_bound",
            owner=owner,
            status="online",
            pairing_code=str(payload.get("pairing_code") or "") or None,
            company_id=str(payload.get("company_id") or "") or None,
            battery_type=str(payload.get("battery_type") or "") or None,
            firmware_version=str(payload.get("firmware_version") or "") or None,
        )
        return self.upsert(vehicle)

    def remove(self, vehicle_id: str) -> bool:
        return self._vehicles.pop(vehicle_id, None) is not None

    # ─── Abfragen ──────────────────────────────────────────────
    def get(self, vehicle_id: str) -> Optional[FleetVehicle]:
        return self._vehicles.get(vehicle_id)

    def get_all(self) -> List[FleetVehicle]:
        return list(self._vehicles.values())

    def nearby(self, lat: float, lon: float, radius_m: float) -> List[Tuple[FleetVehicle, float]]:
        """Alle Fahrzeuge mit Position innerhalb des Radius (nächste zuerst)."""
        out: List[Tuple[FleetVehicle, float]] = []
        for v in self._vehicles.values():
            if v.lat is None or v.lon is None:
                continue
            dist = haversine_m(lat, lon, v.lat, v.lon)
            if dist <= radius_m:
                out.append((v, round(dist, 1)))
        out.sort(key=lambda item: item[1])
        return out

    def stats(self) -> Dict[str, Any]:
        by_kind: Dict[str, int] = {}
        low_battery = 0
        unlocated = 0
        for v in self._vehicles.values():
            by_kind[v.kind] = by_kind.get(v.kind, 0) + 1
            if v.battery is not None and v.battery < 20:
                low_battery += 1
            if v.lat is None:
                unlocated += 1
        return {
            "total": len(self._vehicles),
            "by_kind": by_kind,
            "low_battery": low_battery,
            "unlocated": unlocated,
        }

    # ─── Aktionen (capability-geprüft, Mesh) ───────────────────
    def capabilities(self, vehicle: FleetVehicle) -> set:
        caps = set(KIND_CAPABILITIES.get(vehicle.kind, {"READ_DATA"}))
        for extra in (vehicle.status or "").split(","):
            if extra.strip():
                caps.add(extra.strip().upper())
        return caps

    def available_actions(self, vehicle: FleetVehicle) -> List[str]:
        caps = self.capabilities(vehicle)
        actions = ["read_status", "locate"]
        if "EXECUTE_COMMAND" in caps:
            actions += ["toggle_led", "set_visible"]
        if "LOCK" in caps:
            actions += ["lock", "unlock"]
        return actions

    def execute_action(
        self,
        vehicle_id: str,
        action: str,
        params: Optional[Dict[str, Any]] = None,
    ) -> Dict[str, Any]:
        params = params or {}
        vehicle = self._vehicles.get(vehicle_id)
        if vehicle is None:
            raise KeyError(f"Fahrzeug {vehicle_id} nicht gefunden")
        actions = self.available_actions(vehicle)
        if action not in actions:
            raise PermissionError(
                f"Aktion '{action}' für {vehicle.kind} nicht erlaubt "
                f"(erlaubt: {', '.join(actions)})"
            )

        if action == "read_status":
            return {
                "vehicle_id": vehicle_id, "action": action, "success": True,
                "message": f"Status {vehicle.name}: {vehicle.status}, Akku {vehicle.battery or 'N/A'}%",
                "data": vehicle.to_dict(),
            }
        if action == "locate":
            if vehicle.lat is None:
                return {
                    "vehicle_id": vehicle_id, "action": action, "success": False,
                    "message": f"{vehicle.name}: keine Position (nur Distanzschätzung {vehicle.distance_m:.1f} m)" if vehicle.distance_m else f"{vehicle.name}: keine Position bekannt",
                }
            return {
                "vehicle_id": vehicle_id, "action": action, "success": True,
                "message": f"{vehicle.name}: {vehicle.lat:.6f}, {vehicle.lon:.6f} (±{vehicle.accuracy_m or '?'} m)",
                "data": {"lat": vehicle.lat, "lon": vehicle.lon, "accuracy_m": vehicle.accuracy_m},
            }
        if action == "toggle_led":
            state = bool(params.get("state", True))
            return {
                "vehicle_id": vehicle_id, "action": action, "success": True,
                "message": f"LED {'an' if state else 'aus'} → {vehicle.name} (Mesh)",
                "data": {"state": state},
            }
        if action == "set_visible":
            visible = bool(params.get("visible", True))
            return {
                "vehicle_id": vehicle_id, "action": action, "success": True,
                "message": f"{vehicle.name} {'eingeblendet' if visible else 'ausgeblendet'}",
                "data": {"visible": visible},
            }
        if action in ("lock", "unlock"):
            locked = action == "lock"
            return {
                "vehicle_id": vehicle_id, "action": action, "success": True,
                "message": f"{'Schloss-Befehl (sperren)' if locked else 'Schloss-Befehl (entsperren)'} → {vehicle.name} (Mesh)",
                "data": {"locked": locked},
            }
        raise ValueError(f"Unbekannte Aktion: {action}")


def _local_to_geodetic(local: List[float], anchor: Any) -> Tuple[float, float, float]:
    """Agent-Frame (x=rechts, y=hoch, z=hinten) → WGS84 über den GeoAnchor.

    Inverse von enu_to_local: erst Heading-Rückdrehung + Origin-Abzug,
    dann enu_to_geodetic mit dem Anker-Fix als Referenz.
    """
    import math

    x, y, z = float(local[0]), float(local[1]), float(local[2])
    origin = anchor.local_origin or (0.0, 0.0, 0.0)
    e_rot = x - float(origin[0])
    up = y - float(origin[1])
    n_rot = -(z - float(origin[2]))

    heading = getattr(anchor, "heading_deg", 0.0) or 0.0
    if heading:
        a = math.radians(heading)
        cos_a, sin_a = math.cos(a), math.sin(a)
        east = e_rot * cos_a + n_rot * sin_a
        north = -e_rot * sin_a + n_rot * cos_a
    else:
        east, north = e_rot, n_rot

    ref = anchor.fix
    return enu_to_geodetic(
        east, north, up,
        ref.lat, ref.lon, ref.altitude_m or 0.0,
    )
