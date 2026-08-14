"""Offline-Gerätedatenbank — Python-Kern (docs/DEVICE_DATABASE.md).

Portiert die v16.0.0-Kernidee (Offline-Erkennung von Drahtlosgeräten:
OUI-Lookup, BLE-Service-/Company-ID-Zuordnung, Tracker-Profile,
Reset-/Kommando-Registry) — **mit verifizierten Korrekturen**
(Fehlerkatalog in der Doku):

- `0xFEAA` ist **Eddystone (Google)**, nicht Tile; Tile nutzt die
  Bluetooth-SIG-zugewiesenen UUIDs `0xFEEC`/`0xFEED`.
- Tile-Company-ID `0x0055` der Spec ist nicht bestätigt — als
  „unverifiziert" markiert, Erkennung primär über die Tile-UUIDs.
- Frame-basierte Kommandos (Scooter/E-Bikes) stammen aus Community-
  Reverse-Engineering und sind **nicht** zertifiziert — im Modell als
  `verified=false` mit Referenz geführt; prozedurale Resets (Tasten-
  Sequenzen der Hersteller) als `verified=true`.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

# ─── GATT-Standard-Services (Bluetooth SIG, verifiziert) ───────────────────


@dataclass
class GattCharacteristic:
    uuid: str
    name: str
    properties: List[str]


@dataclass
class GattService:
    uuid: str  # 16-Bit, z. B. "0x180D"
    name: str
    characteristics: List[GattCharacteristic]


GATT_STANDARD_SERVICES: List[GattService] = [
    GattService("0x1800", "Generic Access", [
        GattCharacteristic("0x2A00", "Device Name", ["read"]),
        GattCharacteristic("0x2A01", "Appearance", ["read"]),
    ]),
    GattService("0x1801", "Generic Attribute", [
        GattCharacteristic("0x2A05", "Service Changed", ["indicate"]),
    ]),
    GattService("0x180A", "Device Information", [
        GattCharacteristic("0x2A24", "Model Number String", ["read"]),
        GattCharacteristic("0x2A25", "Serial Number String", ["read"]),
        GattCharacteristic("0x2A26", "Firmware Revision String", ["read"]),
        GattCharacteristic("0x2A27", "Hardware Revision String", ["read"]),
        GattCharacteristic("0x2A28", "Software Revision String", ["read"]),
        GattCharacteristic("0x2A29", "Manufacturer Name String", ["read"]),
    ]),
    GattService("0x180D", "Heart Rate", [
        GattCharacteristic("0x2A37", "Heart Rate Measurement", ["notify"]),
        GattCharacteristic("0x2A38", "Body Sensor Location", ["read"]),
    ]),
    GattService("0x180F", "Battery Service", [
        GattCharacteristic("0x2A19", "Battery Level", ["read", "notify"]),
    ]),
    GattService("0x1816", "Cycling Speed and Cadence", [
        GattCharacteristic("0x2A5B", "CSC Measurement", ["notify"]),
        GattCharacteristic("0x2A5C", "CSC Feature", ["read"]),
    ]),
    GattService("0x1826", "Fitness Machine", [
        GattCharacteristic("0x2ACC", "Fitness Machine Feature", ["read"]),
        GattCharacteristic("0x2AD2", "Indoor Bike Data", ["notify"]),
        GattCharacteristic("0x2AD9", "Fitness Machine Control Point", ["write", "indicate"]),
    ]),
    GattService("0x183A", "Environmental Sensing", [
        GattCharacteristic("0x2A6E", "Temperature", ["read", "notify"]),
        GattCharacteristic("0x2A6F", "Humidity", ["read", "notify"]),
        GattCharacteristic("0x2A6D", "Pressure", ["read", "notify"]),
    ]),
]

_GATT_BY_UUID: Optional[Dict[str, GattService]] = None


def _gatt_index() -> Dict[str, GattService]:
    global _GATT_BY_UUID
    if _GATT_BY_UUID is None:
        _GATT_BY_UUID = {normalize_uuid16(s.uuid): s for s in GATT_STANDARD_SERVICES}
    return _GATT_BY_UUID


def lookup_gatt_service(uuid: str) -> Optional[GattService]:
    return _gatt_index().get(normalize_uuid16(uuid))


def normalize_uuid16(uuid: str) -> str:
    """Normalisiert 16-Bit-UUIDs auf '0xXXXX'-Form (128-Bit bleiben unverändert)."""
    cleaned = uuid.strip().lower().replace(" ", "").replace("-", "")
    if cleaned.startswith("0x"):
        cleaned = cleaned[2:]
    if len(cleaned) == 4 and re.fullmatch(r"[0-9a-f]{4}", cleaned):
        return "0x" + cleaned.upper()
    # 128-Bit-Kurzform (32 Hex-Zeichen) großschreiben
    if len(cleaned) == 32 and re.fullmatch(r"[0-9a-f]{32}", cleaned):
        return "0x" + cleaned.upper()
    return "0x" + cleaned.upper() if cleaned else uuid


# ─── Bluetooth-SIG-Company-IDs & Hersteller-UUIDs ──────────────────────────

# Company-IDs (verifizierte, weit verbreitete Zuordnungen)
COMPANY_IDS: Dict[int, str] = {
    0x004C: "Apple, Inc.",
    0x0075: "Samsung Electronics Co. Ltd.",
    0x00E0: "Google",
}

# Hersteller-/Produkt-16-Bit-UUIDs (Advertising-Erkennung)
VENDOR_SERVICE_UUIDS: Dict[str, List[Dict[str, str]]] = {
    "apple": [
        {"uuid": "0xFD6F", "note": "Apple Nearby Interaction (Find My, AirTag)"},
        {"uuid": "0xFD5A", "note": "Apple (zusätzliche Service-Klasse)"},
    ],
    "samsung": [
        {"uuid": "0xFE6E", "note": "Samsung (SmartThings/SmartTag-Kontext, Spec-Angabe)"},
        {"uuid": "0xFE6F", "note": "Samsung (Spec-Angabe)"},
    ],
    "tile": [
        # Korrektur: Spec nannte 0xFEAA (Eddystone/Google) und 0xFED5 —
        # Tile sind laut Bluetooth SIG 0xFEEC/0xFEED zugewiesen.
        {"uuid": "0xFEED", "note": "Tile (Bluetooth-SIG-zugewiesen)"},
        {"uuid": "0xFEEC", "note": "Tile (Bluetooth-SIG-zugewiesen)"},
    ],
    "google": [
        {"uuid": "0xFDF0", "note": "Google Fast Pair"},
        {"uuid": "0xFEAA", "note": "Eddystone (Google) — in der Spec fälschlich Tile zugeordnet"},
    ],
    "xiaomi": [
        {"uuid": "0xFE95", "note": "Xiaomi (Mi Band/Sensoren, Spec-Angabe)"},
        {"uuid": "0xFEE7", "note": "Xiaomi (Spec-Angabe)"},
    ],
}


# Kuratierter OUI-Seed — weit verbreitete, öffentlich dokumentierte
# Hersteller-Präfixe (EU-relevante Auswahl; volle DB via Builder).
SEED_OUI: Dict[str, str] = {
    "00:1A:22": "Honeywell International Inc.",
    "00:01:C0": "Honeywell",
    "00:0C:29": "VMware, Inc.",
    "00:50:56": "VMware, Inc.",
    "00:15:5D": "Microsoft Corporation",
    "00:1B:21": "Cisco Systems, Inc.",
    "00:1D:09": "Cisco Systems, Inc.",
    "00:25:45": "Cisco Systems, Inc.",
    "00:10:DB": "Juniper Networks",
    "00:0F:B5": "TP-Link Technologies Co., Ltd.",
    "00:1D:0F": "TP-Link Technologies Co., Ltd.",
    "00:11:22": "Dell Inc.",
    "00:14:22": "Dell Inc.",
}


# ─── Smart-Tracker-Profile (Erkennung + prozeduraler Reset) ────────────────


@dataclass
class TrackerProfile:
    id: str
    vendor: str
    company_id: Optional[int]  # None = nicht bestätigt
    service_uuids: List[str]
    detection: str
    reset_procedure: str
    verified: bool


TRACKER_PROFILES: List[TrackerProfile] = [
    TrackerProfile(
        id="apple_airtag", vendor="Apple", company_id=0x004C,
        service_uuids=["0xFD6F", "0xFD5A"],
        detection="Advertising: Company 0x004C + Service 0xFD6F (Find My)",
        reset_procedure="AirTag: Batterie 30 s entfernen, wieder einsetzen (Apple-Anleitung)",
        verified=True,
    ),
    TrackerProfile(
        id="samsung_smarttag2", vendor="Samsung", company_id=0x0075,
        service_uuids=["0xFE6E", "0xFE6F"],
        detection="Advertising: Company 0x0075 + Service 0xFE6E (SmartThings Find)",
        reset_procedure="SmartTag2: Taste 5 s halten, bis die LED blinkt (Samsung-Anleitung)",
        verified=True,
    ),
    TrackerProfile(
        id="tile", vendor="Tile/Life360", company_id=None,
        service_uuids=["0xFEED", "0xFEEC"],
        detection="Advertising: Service 0xFEED/0xFEEC (Bluetooth-SIG-zugewiesen an Tile)",
        reset_procedure="Tile: Taste 2× drücken, dann 5 s halten (Hersteller-Anleitung)",
        verified=True,  # UUID-Zuordnung verifiziert; Company-ID 0x0055 der Spec unbestätigt
    ),
    TrackerProfile(
        id="google_pixel_tag", vendor="Google", company_id=0x00E0,
        service_uuids=["0xFDF0"],
        detection="Advertising: Fast Pair 0xFDF0 + Google Find My",
        reset_procedure="Pixel Tag: Taste 10 s halten (Google-Anleitung)",
        verified=True,
    ),
]

_TRACKERS_BY_ID = {t.id: t for t in TRACKER_PROFILES}


def lookup_tracker(company_id: Optional[int] = None, service_uuids: Optional[List[str]] = None) -> List[TrackerProfile]:
    """Findet Tracker-Profile anhand von Company-ID und/oder Service-UUIDs."""
    service_uuids = [normalize_uuid16(u) for u in (service_uuids or [])]
    hits = []
    for profile in TRACKER_PROFILES:
        if company_id is not None and profile.company_id == company_id:
            hits.append(profile)
            continue
        if service_uuids and any(u in service_uuids for u in profile.service_uuids):
            hits.append(profile)
    return hits


# ─── OUI-Lookup (MAC → Hersteller) ─────────────────────────────────────────


def normalize_mac(mac: str) -> str:
    """Normalisiert MAC-Adressen auf 'AA:BB:CC:DD:EE:FF'."""
    cleaned = re.sub(r"[^0-9a-fA-F]", "", mac.strip())
    if len(cleaned) != 12:
        raise ValueError(f"Ungültige MAC-Adresse: {mac!r}")
    return ":".join(cleaned[i:i + 2].upper() for i in range(0, 12, 2))


def oui_prefix(mac: str, bits: int = 24) -> str:
    """Präfix der MAC (Standard: 24 Bit OUI, z. B. 'AA:BB:CC')."""
    normalized = normalize_mac(mac)
    octets = bits // 8
    return ":".join(normalized.split(":")[:octets])


class OuiDatabase:
    """OUI-Datenbank: Präfix (24/28/36 Bit) → Hersteller.

    Längere Präfixe (MA-M/MA-S) gewinnen: Lookup probiert von lang nach kurz.
    """

    def __init__(self, entries: Optional[Dict[str, str]] = None) -> None:
        self._entries: Dict[str, str] = {k.upper(): v for k, v in (entries or {}).items()}

    @property
    def entries(self) -> Dict[str, str]:
        return dict(self._entries)

    def add(self, prefix: str, vendor: str) -> None:
        self._entries[prefix.upper()] = vendor

    def lookup(self, mac: str) -> Optional[str]:
        normalized = normalize_mac(mac)
        prefixes = sorted(self._entries, key=len, reverse=True)
        for prefix in prefixes:
            if normalized.startswith(prefix):
                return self._entries[prefix]
        return None

    def __len__(self) -> int:
        return len(self._entries)


# ─── Geräte-Datenbank ──────────────────────────────────────────────────────


@dataclass
class DeviceRecord:
    id: str
    name: str
    type: str
    category: str
    vendor: str
    model: str = ""
    technologies: List[str] = field(default_factory=list)
    service_uuids: List[str] = field(default_factory=list)
    mac_prefix: Optional[str] = None
    source: str = "seed"
    verified: bool = True
    notes: str = ""

    def to_dict(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "name": self.name,
            "type": self.type,
            "category": self.category,
            "vendor": self.vendor,
            "model": self.model,
            "technologies": list(self.technologies),
            "service_uuids": [normalize_uuid16(u) for u in self.service_uuids],
            "mac_prefix": self.mac_prefix.upper() if self.mac_prefix else None,
            "source": self.source,
            "verified": self.verified,
            "notes": self.notes,
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "DeviceRecord":
        return cls(
            id=str(data["id"]),
            name=str(data.get("name", data["id"])),
            type=str(data.get("type", "UNKNOWN")),
            category=str(data.get("category", "OTHER")),
            vendor=str(data.get("vendor", "")),
            model=str(data.get("model", "")),
            technologies=[str(t) for t in data.get("technologies", [])],
            service_uuids=[normalize_uuid16(u) for u in data.get("service_uuids", [])],
            mac_prefix=data.get("mac_prefix"),
            source=str(data.get("source", "seed")),
            verified=bool(data.get("verified", True)),
            notes=str(data.get("notes", "")),
        )


class DeviceDatabase:
    """Offline-Gerätedatenbank: Suche nach MAC-Präfix, Service-UUID, Text, Kategorie."""

    def __init__(self, records: Optional[List[DeviceRecord]] = None) -> None:
        self._records: Dict[str, DeviceRecord] = {}
        for record in records or []:
            self.upsert(record)

    def upsert(self, record: DeviceRecord) -> None:
        self._records[record.id] = record

    def by_id(self, device_id: str) -> Optional[DeviceRecord]:
        return self._records.get(device_id)

    def by_mac(self, mac: str) -> List[DeviceRecord]:
        normalized = normalize_mac(mac)
        return [r for r in self._records.values() if r.mac_prefix and normalized.startswith(r.mac_prefix)]

    def by_service(self, uuid: str) -> List[DeviceRecord]:
        normalized = normalize_uuid16(uuid)
        return [r for r in self._records.values() if normalized in r.service_uuids]

    def search(self, query: str, category: Optional[str] = None) -> List[DeviceRecord]:
        q = query.strip().lower()
        results = []
        for record in self._records.values():
            if category and record.category.upper() != category.upper():
                continue
            haystack = " ".join([
                record.name, record.type, record.category, record.vendor, record.model,
            ]).lower()
            if not q or q in haystack:
                results.append(record)
        return results

    def categories(self) -> Dict[str, int]:
        counts: Dict[str, int] = {}
        for record in self._records.values():
            counts[record.category] = counts.get(record.category, 0) + 1
        return counts

    def __len__(self) -> int:
        return len(self._records)

    def to_dict(self) -> Dict[str, Any]:
        return {"records": [r.to_dict() for r in self._records.values()]}

    def save(self, path: str) -> None:
        with open(path, "w", encoding="utf-8") as fh:
            json.dump(self.to_dict(), fh, ensure_ascii=False, indent=2)

    @classmethod
    def load(cls, path: str) -> "DeviceDatabase":
        with open(path, encoding="utf-8") as fh:
            data = json.load(fh)
        records = [DeviceRecord.from_dict(r) for r in data.get("records", [])]
        cls.validate_list(records)
        return cls(records)

    @classmethod
    def seed(cls) -> "DeviceDatabase":
        """Kuratierter Seed (verifizierte Einträge, klein gehalten)."""
        return cls(SEED_RECORDS)

    @staticmethod
    def validate_list(records: List["DeviceRecord"]) -> None:
        """Prüft die Rohliste (Duplikate, Pflichtfelder) — vor dem Upsert."""
        ids = [r.id for r in records]
        if len(ids) != len(set(ids)):
            raise ValueError("Doppelte Geräte-IDs in der Datenbank")
        for record in records:
            if not record.name or not record.vendor:
                raise ValueError(f"Record {record.id}: name/vendor fehlen")


# Kuratierter Seed — Herstellerangaben nach öffentlicher Dokumentation
SEED_RECORDS: List[DeviceRecord] = [
    DeviceRecord(
        id="ikea_tradfri_e1603", name="TRÅDFRI LED Bulb E27", type="light",
        category="SMART_HOME", vendor="IKEA", model="E1603",
        technologies=["Zigbee"], source="zigbee2mqtt", notes="Reset: Pairing-Taste 10 s",
    ),
    DeviceRecord(
        id="philips_hue_9290012573", name="Hue White E27", type="light",
        category="SMART_HOME", vendor="Signify (Philips)", model="9290012573",
        technologies=["Zigbee", "BLE"], source="zigbee2mqtt",
        notes="Reset über Hue-Bridge (Reset-Taste) bzw. Dimmer-Fernbedienung",
    ),
    DeviceRecord(
        id="aqara_wsdcgq11lm", name="Aqara Temperature/Humidity", type="sensor",
        category="SMART_HOME", vendor="Xiaomi/Aqara", model="WSDCGQ11LM",
        technologies=["Zigbee"], source="zigbee2mqtt", notes="Reset: Taste 5× drücken",
    ),
    DeviceRecord(
        id="sonoff_s31", name="Sonoff S31 Lite", type="plug",
        category="SMART_HOME", vendor="SONOFF", model="S31",
        technologies=["WiFi"], source="vendor-docs", notes="Reset: Taste 5 s → AP-Modus",
    ),
    DeviceRecord(
        id="xiaomi_m365", name="Mi Electric Scooter M365", type="escooter",
        category="VEHICLE", vendor="Xiaomi", model="M365",
        technologies=["BLE"], service_uuids=["0xFFE0"],
        source="community", verified=False,
        notes="BLE 0xFFE0/0xFFE1; Frames aus Community-Reverse-Engineering (m365py) — nicht zertifiziert",
    ),
    DeviceRecord(
        id="ninebot_es2", name="Ninebot ES2", type="escooter",
        category="VEHICLE", vendor="Segway-Ninebot", model="ES2",
        technologies=["BLE"], service_uuids=["0xFFE0"],
        source="community", verified=False,
        notes="BLE 0xFFE0/0xFFE1; Frames aus Community-Reverse-Engineering",
    ),
    DeviceRecord(
        id="qorvo_dwm3000", name="DWM3000 UWB Module", type="uwb_module",
        category="UWB", vendor="Qorvo", model="DWM3000 (DW3110)",
        technologies=["UWB"], source="vendor-docs",
        notes="IEEE 802.15.4z, FiRa-kompatibel",
    ),
    DeviceRecord(
        id="nxp_sr150", name="Trimension SR150", type="uwb_module",
        category="UWB", vendor="NXP", model="SR150",
        technologies=["UWB"], source="vendor-docs",
        notes="UWB-Chip für Smartphones/IoT (Ranging)",
    ),
]
