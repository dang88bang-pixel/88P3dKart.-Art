"""
Bluetooth-Zubehör Modul – Verwaltung & Fusion aller BLE + Classic Geräte.

Erweiterung für 3dxAgent Edge-Agent:
- Zentrale Registry aller Accessories (Token, Sensor-Tag, Wearable, Asset-Tag, Remote, Gateway...)
- Health Tracking, Batterie-Monitoring, SOS, Fall-Erkennung
- Signal-Qualität Bewertung (analog ClientRules)
- MQTT Bridge für externe Relays (ble/tokens/#, bluetooth/accessories/#)
- Fusion Hooks für EKF (Token Position via RSSI Trilateration)
- REST Endpunkte

Protokoll:
- Advertising (Company ID 0x0059) V1 legacy 9 Byte + V2 extended 16+ Byte
- iBeacon, Eddystone
- GATT Custom Service 8d81e7c0... für Config/Data/Command
"""

import math
import time
import logging
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Dict, List, Optional

logger = logging.getLogger(__name__)

class BluetoothAccessoryType(str, Enum):
    TOKEN_CLASSIC = "TOKEN_CLASSIC"
    TOKEN_PRO = "TOKEN_PRO"
    SENSOR_TAG = "SENSOR_TAG"
    WEARABLE = "WEARABLE"
    ASSET_TAG = "ASSET_TAG"
    REMOTE_CONTROLLER = "REMOTE_CONTROLLER"
    RELAY = "RELAY"
    GATEWAY_BRIDGE = "GATEWAY_BRIDGE"
    AUDIO_BEACON = "AUDIO_BEACON"
    CLASSIC_SPP = "CLASSIC_SPP"
    HEADSET = "HEADSET"
    HID = "HID"
    GENERIC_BLE = "GENERIC_BLE"
    GENERIC_CLASSIC = "GENERIC_CLASSIC"

# Flags aus AccessoryFlags Kotlin Gegenstück
FLAG_MOVING = 1 << 0
FLAG_BUTTON = 1 << 1
FLAG_LOW_BAT = 1 << 2
FLAG_TAMPER = 1 << 3
FLAG_CALIBRATING = 1 << 4
FLAG_OTA = 1 << 5
FLAG_FALL = 1 << 6
FLAG_SOS = 1 << 7

@dataclass
class BluetoothAccessory:
    mac: str
    type: BluetoothAccessoryType = BluetoothAccessoryType.GENERIC_BLE
    name: str = "Unknown"
    rssi: int = -100
    tx_power: Optional[int] = None
    battery: int = 100
    is_connectable: bool = False
    is_bonded: bool = False
    is_connected: bool = False
    last_seen: float = field(default_factory=lambda: time.time())
    protocol_version: int = 1
    firmware_version: Optional[str] = None

    # Sensorik
    accel_x: float = 0.0
    accel_y: float = 0.0
    accel_z: float = 0.0
    temperature_c: Optional[float] = None
    humidity_pct: Optional[float] = None
    pressure_hpa: Optional[float] = None
    air_quality_ppm: Optional[float] = None
    light_lux: Optional[float] = None
    heart_rate_bpm: Optional[int] = None
    steps: Optional[int] = None

    # iBeacon / Eddystone
    ibeacon_uuid: Optional[str] = None
    ibeacon_major: Optional[int] = None
    ibeacon_minor: Optional[int] = None
    eddystone_url: Optional[str] = None
    eddystone_namespace: Optional[str] = None
    eddystone_instance: Optional[str] = None

    # Remote
    button_state: int = 0
    flags: int = 0
    distance_m: Optional[float] = None
    data_quality: float = 0.9

    def age_ms(self) -> float:
        return (time.time() - self.last_seen) * 1000.0

    @property
    def is_expired(self) -> bool:
        return self.age_ms() > 30_000

    @property
    def is_sos(self) -> bool:
        return (self.flags & FLAG_SOS) != 0

    @property
    def is_fall(self) -> bool:
        return (self.flags & FLAG_FALL) != 0

    @property
    def is_moving(self) -> bool:
        return (self.flags & FLAG_MOVING) != 0

    def update_distance(self):
        tx = self.tx_power if self.tx_power is not None else -59
        if self.rssi == 0:
            self.distance_m = None
            return
        ratio = self.rssi / tx if tx != 0 else 1.0
        if ratio < 1.0:
            self.distance_m = math.pow(ratio, 10.0)
        else:
            self.distance_m = 0.89976 * math.pow(ratio, 7.7095) + 0.111
        # Qualität
        distance_penalty = min(self.distance_m / 20.0, 0.5) if self.distance_m else 0.0
        age_penalty = min(self.age_ms() / 30000.0, 0.3)
        self.data_quality = max(0.1, 0.9 - distance_penalty - age_penalty * 0.3)

    def to_dict(self) -> Dict[str, Any]:
        d = {
            "mac": self.mac,
            "type": self.type.value,
            "name": self.name,
            "rssi": self.rssi,
            "battery": self.battery,
            "tx_power": self.tx_power,
            "distance_m": self.distance_m,
            "protocol_version": self.protocol_version,
            "flags": self.flags,
            "is_moving": self.is_moving,
            "is_sos": self.is_sos,
            "is_fall": self.is_fall,
            "is_connected": self.is_connected,
            "is_bonded": self.is_bonded,
            "last_seen": self.last_seen,
            "data_quality": self.data_quality,
            "firmware_version": self.firmware_version,
        }
        if self.accel_x or self.accel_y or self.accel_z:
            d.update({"accel_x": self.accel_x, "accel_y": self.accel_y, "accel_z": self.accel_z})
        if self.temperature_c is not None:
            d["temperature_c"] = self.temperature_c
        if self.humidity_pct is not None:
            d["humidity_pct"] = self.humidity_pct
        if self.pressure_hpa is not None:
            d["pressure_hpa"] = self.pressure_hpa
        if self.air_quality_ppm is not None:
            d["air_quality_ppm"] = self.air_quality_ppm
        if self.light_lux is not None:
            d["light_lux"] = self.light_lux
        if self.heart_rate_bpm is not None:
            d["heart_rate_bpm"] = self.heart_rate_bpm
        if self.steps is not None:
            d["steps"] = self.steps
        if self.ibeacon_uuid:
            d.update({"ibeacon_uuid": self.ibeacon_uuid, "ibeacon_major": self.ibeacon_major, "ibeacon_minor": self.ibeacon_minor})
        if self.eddystone_url:
            d["eddystone_url"] = self.eddystone_url
        if self.eddystone_namespace:
            d.update({"eddystone_namespace": self.eddystone_namespace, "eddystone_instance": self.eddystone_instance})
        if self.button_state:
            d["button_state"] = self.button_state
        return d

    @classmethod
    def from_dict(cls, payload: Dict[str, Any]) -> "BluetoothAccessory":
        mac = payload.get("mac") or payload.get("mac_address") or payload.get("macAddress") or "unknown"
        type_str = payload.get("type", "GENERIC_BLE")
        try:
            type_enum = BluetoothAccessoryType(type_str)
        except ValueError:
            # Map legacy lower / uppercase
            upper = type_str.upper()
            try:
                type_enum = BluetoothAccessoryType(upper)
            except:
                # attempt fuzzy mapping
                if "TOKEN" in upper:
                    type_enum = BluetoothAccessoryType.TOKEN_PRO if "PRO" in upper else BluetoothAccessoryType.TOKEN_CLASSIC
                elif "SENSOR" in upper:
                    type_enum = BluetoothAccessoryType.SENSOR_TAG
                elif "WEARABLE" in upper:
                    type_enum = BluetoothAccessoryType.WEARABLE
                elif "ASSET" in upper or "BEACON" in upper:
                    type_enum = BluetoothAccessoryType.ASSET_TAG
                elif "REMOTE" in upper or "CONTROLLER" in upper:
                    type_enum = BluetoothAccessoryType.REMOTE_CONTROLLER
                else:
                    type_enum = BluetoothAccessoryType.GENERIC_BLE

        # Battery field may be battery or battery_level
        batt = payload.get("battery", payload.get("battery_level", 100))
        try:
            batt = int(batt)
        except:
            batt = 100

        acc = cls(
            mac=str(mac).lower(),
            type=type_enum,
            name=payload.get("name", str(mac)),
            rssi=int(payload.get("rssi", -100)),
            tx_power=payload.get("tx_power") or payload.get("txPower"),
            battery=batt,
            is_connectable=payload.get("is_connectable", payload.get("isConnectable", False)) or False,
            is_bonded=payload.get("is_bonded", payload.get("bonded", False)) or False,
            is_connected=payload.get("is_connected", payload.get("connected", False)) or False,
            last_seen=payload.get("last_seen", payload.get("timestamp", time.time())),
            protocol_version=payload.get("protocol_version", 1),
            firmware_version=payload.get("firmware_version"),
            accel_x=float(payload.get("accel_x", payload.get("accelX", 0.0)) or 0.0),
            accel_y=float(payload.get("accel_y", payload.get("accelY", 0.0)) or 0.0),
            accel_z=float(payload.get("accel_z", payload.get("accelZ", 0.0)) or 0.0),
            temperature_c=payload.get("temperature_c") or payload.get("temperature"),
            humidity_pct=payload.get("humidity_pct"),
            pressure_hpa=payload.get("pressure_hpa"),
            air_quality_ppm=payload.get("air_quality_ppm"),
            light_lux=payload.get("light_lux"),
            heart_rate_bpm=payload.get("heart_rate_bpm"),
            steps=payload.get("steps"),
            ibeacon_uuid=payload.get("ibeacon_uuid"),
            ibeacon_major=payload.get("ibeacon_major"),
            ibeacon_minor=payload.get("ibeacon_minor"),
            eddystone_url=payload.get("eddystone_url"),
            eddystone_namespace=payload.get("eddystone_namespace"),
            eddystone_instance=payload.get("eddystone_instance"),
            button_state=int(payload.get("button_state", 0) or 0),
            flags=int(payload.get("flags", 0) or 0),
            distance_m=payload.get("distance_m"),
            data_quality=float(payload.get("data_quality", 0.9)),
        )
        # Normalize last_seen if in ms
        if acc.last_seen > 1e12: # ms timestamp
            acc.last_seen = acc.last_seen / 1000.0
        if acc.distance_m is None:
            acc.update_distance()
        return acc


@dataclass
class AccessoryHealth:
    mac: str
    type: BluetoothAccessoryType
    status: str  # HEALTHY / DEGRADED / CRITICAL / LOST
    score: float
    warnings: List[str] = field(default_factory=list)
    details: Dict[str, float] = field(default_factory=dict)


class BluetoothAccessoryRegistry:
    """
    Registry für alle Bluetooth Zubehörgeräte – analog ClientRegistry (Kotlin).
    - Thread-safe via dict (single thread asyncio aber MQTT hat eigenen Thread)
    - Auto-expiry (60s)
    - Health Bewertung
    - SOS / Button / Fall Callbacks
    """
    def __init__(self):
        self._accessories: Dict[str, BluetoothAccessory] = {}
        self._callbacks_sos: List[Any] = []
        self._callbacks_button: List[Any] = []

    def update_or_create(self, accessory: BluetoothAccessory) -> BluetoothAccessory:
        mac = accessory.mac.lower()
        existing = self._accessories.get(mac)
        if existing:
            # Merge intelligente: neuer RSSI + neuere Sensorwerte überschreiben
            existing.rssi = accessory.rssi
            existing.last_seen = time.time()
            existing.battery = accessory.battery
            existing.accel_x = accessory.accel_x if accessory.accel_x else existing.accel_x
            existing.accel_y = accessory.accel_y if accessory.accel_y else existing.accel_y
            existing.accel_z = accessory.accel_z if accessory.accel_z else existing.accel_z
            existing.flags = accessory.flags
            existing.button_state = accessory.button_state or existing.button_state
            existing.temperature_c = accessory.temperature_c or existing.temperature_c
            existing.humidity_pct = accessory.humidity_pct or existing.humidity_pct
            existing.heart_rate_bpm = accessory.heart_rate_bpm or existing.heart_rate_bpm
            existing.steps = accessory.steps or existing.steps
            existing.pressure_hpa = accessory.pressure_hpa or existing.pressure_hpa
            existing.is_connected = accessory.is_connected or existing.is_connected
            existing.firmware_version = accessory.firmware_version or existing.firmware_version
            # Distance neu schätzen
            existing.tx_power = accessory.tx_power or existing.tx_power
            existing.update_distance()
            # Type Upgrade wenn spezifischer
            if existing.type == BluetoothAccessoryType.GENERIC_BLE and accessory.type != BluetoothAccessoryType.GENERIC_BLE:
                existing.type = accessory.type
            accessory = existing
        else:
            accessory.update_distance()
            self._accessories[mac] = accessory

        # SOS / Fall Erkennung
        if accessory.is_sos:
            logger.warning("🚨 SOS von %s (%s) RSSI=%d", accessory.mac, accessory.name, accessory.rssi)
            for cb in self._callbacks_sos:
                try: cb(accessory)
                except Exception as e: logger.error("SOS callback error: %s", e)
        if accessory.button_state != 0:
            for cb in self._callbacks_button:
                try: cb(accessory)
                except Exception as e: logger.error("Button callback error: %s", e)

        return accessory

    def update_from_payload(self, payload: Dict[str, Any]) -> BluetoothAccessory:
        acc = BluetoothAccessory.from_dict(payload)
        return self.update_or_create(acc)

    def update_batch(self, payloads: List[Dict[str, Any]]) -> List[BluetoothAccessory]:
        res = []
        for p in payloads:
            try:
                res.append(self.update_from_payload(p))
            except Exception as e:
                logger.warning("Failed to parse accessory payload %s: %s", p, e)
        return res

    def get(self, mac: str) -> Optional[BluetoothAccessory]:
        return self._accessories.get(mac.lower())

    def get_all(self) -> List[BluetoothAccessory]:
        return list(self._accessories.values())

    def get_by_type(self, type_: BluetoothAccessoryType) -> List[BluetoothAccessory]:
        return [a for a in self._accessories.values() if a.type == type_]

    def get_by_types(self, types: List[BluetoothAccessoryType]) -> List[BluetoothAccessory]:
        s = set(types)
        return [a for a in self._accessories.values() if a.type in s]

    def remove_expired(self, max_age_s: float = 60.0) -> List[str]:
        now = time.time()
        expired = [mac for mac, acc in self._accessories.items() if now - acc.last_seen > max_age_s]
        for mac in expired:
            del self._accessories[mac]
        if expired:
            logger.info("Expired %d accessories removed: %s", len(expired), expired)
        return expired

    def count(self) -> int:
        return len(self._accessories)

    def stats(self) -> Dict[str, Any]:
        all_ = self.get_all()
        by_type: Dict[str, int] = {}
        low_bat = 0
        sos = 0
        lost = 0
        for acc in all_:
            by_type[acc.type.value] = by_type.get(acc.type.value, 0) + 1
            if acc.battery < 20: low_bat += 1
            if acc.is_sos: sos += 1
            if acc.is_expired: lost += 1
        return {
            "total": len(all_),
            "by_type": by_type,
            "low_battery": low_bat,
            "sos_active": sos,
            "lost": lost,
        }

    def evaluate_health(self, mac: str) -> Optional[AccessoryHealth]:
        acc = self.get(mac)
        if not acc:
            return None
        warnings: List[str] = []
        if acc.battery < 15: warnings.append("LOW_BATTERY")
        if acc.is_expired: warnings.append("EXPIRED")
        if acc.rssi < -85: warnings.append("WEAK_SIGNAL")
        if acc.is_sos: warnings.append("SOS_ACTIVE")
        if acc.is_fall: warnings.append("FALL_DETECTED")
        if acc.distance_m and acc.distance_m > 15: warnings.append("OUT_OF_RANGE")

        quality = acc.data_quality
        battery_score = acc.battery / 100.0
        rssi_norm = (acc.rssi + 100) / 70.0
        rssi_norm = max(0.0, min(1.0, rssi_norm))
        distance_score = 1.0 - min((acc.distance_m or 0)/20.0, 1.0)

        overall = quality * 0.4 + battery_score * 0.25 + rssi_norm * 0.2 + distance_score * 0.15
        if acc.is_expired:
            overall *= 0.2

        if warnings and ("SOS_ACTIVE" in warnings or "FALL_DETECTED" in warnings):
            status = "CRITICAL"
        elif acc.is_expired:
            status = "LOST"
        elif overall > 0.8:
            status = "HEALTHY"
        elif overall > 0.5:
            status = "DEGRADED"
        else:
            status = "CRITICAL"

        return AccessoryHealth(
            mac=acc.mac,
            type=acc.type,
            status=status,
            score=overall,
            warnings=warnings,
            details={"quality": quality, "battery": battery_score, "rssi": rssi_norm, "distance": distance_score},
        )

    def evaluate_all_health(self) -> List[AccessoryHealth]:
        return [h for mac in self._accessories for h in [self.evaluate_health(mac)] if h]

    def get_critical(self) -> List[BluetoothAccessory]:
        return [acc for acc in self._accessories.values() if acc.is_sos or acc.is_fall or acc.battery < 10]

    def on_sos(self, callback):
        self._callbacks_sos.append(callback)

    def on_button(self, callback):
        self._callbacks_button.append(callback)

# Globale Registry Instance (Singleton für Edge-Agent)
global_accessory_registry = BluetoothAccessoryRegistry()
