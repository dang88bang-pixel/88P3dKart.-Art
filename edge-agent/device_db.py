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

# Company-IDs — kuratierte, verifizierte Auswahl aus der Bluetooth-Numbers-
# Datenbank (NordicSemiconductor/bluetooth-numbers-database, v1/company_ids.json,
# Spiegel der Bluetooth-SIG-Assigned-Numbers). Fehlerkatalog der Spec v17:
#  - 0x0000 = "Ericsson AB" (Spec: „Ericsson Technology Licensing" — falsch)
#  - 0x017A = "Telemonitor, Inc." (Spec: „Telemontior" — Tippfehler)
#  - Xiaomi = 0x038F (Spec: 0xFDAB — falsch, 0xFDAB existiert nicht als Company-ID)
#  - HP = 0x0065 (Spec: 0xFDB4 — falsch)
#  - Oura/0xFDB0 und ECSG/0xFDB5 existieren nicht als Company-IDs
#    (Liste endet bei 0x10F4, danach nur 0xFFFF als Reserved-Wert).
COMPANY_IDS: Dict[int, str] = {
    0x0000: "Ericsson AB",
    0x0001: "Nokia Mobile Phones",
    0x0002: "Intel Corp.",
    0x0003: "IBM Corp.",
    0x000D: "Texas Instruments Inc.",
    0x001F: "AVM Berlin",
    0x0025: "NXP B.V.",
    0x004C: "Apple, Inc.",
    0x0059: "Nordic Semiconductor ASA",
    0x005C: "Belkin International, Inc.",
    0x0065: "HP, Inc.",
    0x0067: "GN Hearing",
    0x006B: "Polar Electro OY",
    0x0075: "Samsung Electronics Co. Ltd.",
    0x0093: "Universal Electronics, Inc.",
    0x00C4: "LG Electronics",
    0x00CE: "Eve Systems GmbH",
    0x00D0: "Dexcom, Inc.",
    0x00E0: "Google",
    0x0171: "Amazon.com Services LLC",
    0x017A: "Telemonitor, Inc.",
    0x017B: "taskit GmbH",
    0x017E: "BluDotz Ltd",
    0x038F: "Xiaomi Inc.",
    0x03BB: "Abbott",
    0x03D5: "Wyzelink Systems Inc.",
    0x0520: "Target Corporation",
    0x0526: "Honeywell International Inc.",
    0x0544: "OrthoSensor, Inc.",
    0x0568: "Bodyport Inc.",
    0x05C8: "SOMFY SAS",
    0x0739: "Jiangsu Qinheng Co., Ltd.",
    0x0A53: "KKM COMPANY LIMITED",
    0x0A54: "SQL Technologies Corp.",
}


def normalize_company_id(value: Any) -> Optional[int]:
    """Normalisiert eine Company-ID (int, dezimaler String, '0x…'-Hex) → int."""
    if isinstance(value, int):
        return value
    text = str(value).strip().lower().replace(" ", "")
    if not text:
        return None
    try:
        if text.startswith("0x"):
            return int(text[2:], 16)
        if all(c in "0123456789" for c in text):
            return int(text, 10)
        if all(c in "0123456789abcdef" for c in text):
            return int(text, 16)
    except ValueError:
        pass
    return None


def lookup_company(value: Any) -> Optional[str]:
    """Company-ID → Herstellername (None, wenn unbekannt)."""
    company_id = normalize_company_id(value)
    if company_id is None:
        return None
    return COMPANY_IDS.get(company_id)

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
    frequency_bands: List[str] = field(default_factory=list)
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
            "frequency_bands": list(self.frequency_bands),
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
            frequency_bands=[str(b) for b in data.get("frequency_bands", [])],
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

    def search(self, query: str, category: Optional[str] = None,
               technology: Optional[str] = None) -> List[DeviceRecord]:
        q = query.strip().lower()
        tech = (technology or "").strip().lower()
        results = []
        for record in self._records.values():
            if category and record.category.upper() != category.upper():
                continue
            if tech and not any(tech == t.strip().lower() for t in record.technologies):
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

    def technologies(self) -> Dict[str, int]:
        """Zählt Records je Technologie (ein Record kann mehrere Technologien haben)."""
        counts: Dict[str, int] = {}
        for record in self._records.values():
            for technology in record.technologies:
                key = technology.strip()
                if key:
                    counts[key] = counts.get(key, 0) + 1
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
    # ── Thread/Matter (Spec 17.x; verifiziert: Thread Group Nov 2025 1.000+ ──
    #    zertifizierte Produkte, Thread 1.4 seit Sep 2024; CSA >1.000 Matter-
    #    zertifizierte Geräte; IKEA-Sensoren: 5 Modelle, Ankündigung Nov 2025,
    #    Marktstart Jan 2026 — nicht „7 Produkte")
    DeviceRecord(
        id="eve_door_window", name="Eve Door & Window (Matter)", type="sensor",
        category="SMART_HOME", vendor="Eve Systems GmbH", model="20EBT4101",
        technologies=["Thread", "Matter"], source="vendor-docs",
        notes="Matter-over-Thread; Company-ID 0x00CE (SIG)",
    ),
    DeviceRecord(
        id="eve_thermo", name="Eve Thermo (Matter)", type="thermostat",
        category="SMART_HOME", vendor="Eve Systems GmbH", model="20EAM9901",
        technologies=["Thread", "Matter"], source="vendor-docs",
        notes="Matter-over-Thread; Heizkörperthermostat",
    ),
    DeviceRecord(
        id="nanoleaf_essentials_a19", name="Nanoleaf Essentials A19", type="light",
        category="SMART_HOME", vendor="Nanoleaf", model="NL45",
        technologies=["Thread", "Matter"], source="vendor-docs",
        notes="Matter-over-Thread (Border-Router nötig)",
    ),
    DeviceRecord(
        id="aqara_hub_m3", name="Aqara Smart Hub M3", type="hub",
        category="SMART_HOME", vendor="Aqara", model="HM-G01D",
        technologies=["Thread", "Matter", "Zigbee", "WiFi"], source="vendor-docs",
        notes="Matter-Bridge + Thread-Border-Router",
    ),
    DeviceRecord(
        id="ikea_dirigera", name="IKEA DIRIGERA Hub", type="hub",
        category="SMART_HOME", vendor="IKEA", model="E2201",
        technologies=["Thread", "Matter", "Zigbee", "WiFi"], source="vendor-docs",
        notes="Matter-Bridge + Thread-Border-Router",
    ),
    DeviceRecord(
        id="apple_homepod_mini", name="HomePod mini", type="hub",
        category="SMART_HOME", vendor="Apple", model="A2374",
        technologies=["Thread", "Matter", "WiFi", "BLE"], source="vendor-docs",
        notes="Thread-Border-Router + Matter-Hub",
    ),
    DeviceRecord(
        id="apple_tv_4k_3g", name="Apple TV 4K (3. Gen)", type="hub",
        category="SMART_HOME", vendor="Apple", model="A2737",
        technologies=["Thread", "Matter", "WiFi"], source="vendor-docs",
        notes="Thread-Border-Router + Matter-Hub",
    ),
    DeviceRecord(
        id="google_tv_streamer", name="Google TV Streamer (4K)", type="hub",
        category="SMART_HOME", vendor="Google", model="GA05662",
        technologies=["Thread", "Matter", "WiFi"], source="vendor-docs",
        notes="Thread-Border-Router + Matter-Hub",
    ),
    DeviceRecord(
        id="google_nest_hub_2", name="Google Nest Hub (2. Gen)", type="hub",
        category="SMART_HOME", vendor="Google", model="AQC3",
        technologies=["Thread", "Matter", "WiFi"], source="vendor-docs",
        notes="Thread-Border-Router + Matter-Hub",
    ),
    DeviceRecord(
        id="amazon_echo_4g", name="Amazon Echo (4. Gen)", type="hub",
        category="SMART_HOME", vendor="Amazon", model="D9N29T",
        technologies=["Thread", "Matter", "Zigbee", "WiFi"], source="vendor-docs",
        notes="Thread-Border-Router + Zigbee-Hub",
    ),
    DeviceRecord(
        id="amazon_eero", name="Amazon eero", type="router",
        category="SMART_HOME", vendor="Amazon (eero)", model="K010001",
        technologies=["Thread", "Matter", "WiFi"], source="vendor-docs",
        notes="Thread-Border-Router",
    ),
    DeviceRecord(
        id="philips_hue_bridge", name="Hue Bridge (Matter)", type="hub",
        category="SMART_HOME", vendor="Signify (Philips)", model="BSB002",
        technologies=["Zigbee", "Matter", "WiFi"], source="vendor-docs",
        notes="Matter-Bridge für Hue-System (2023er Update)",
    ),
    DeviceRecord(
        id="sonoff_zb_bridge_ultra", name="SONOFF Zigbee Bridge Ultra", type="hub",
        category="SMART_HOME", vendor="SONOFF", model="ZB Bridge-U",
        technologies=["Zigbee", "Matter"], source="vendor-docs",
        notes="Matter-Bridge für Zigbee-Geräte",
    ),
    DeviceRecord(
        id="shelly_plug_s_gen3", name="Shelly Plug S Gen3 (Matter)", type="plug",
        category="SMART_HOME", vendor="Shelly", model="Plug S Gen3",
        technologies=["Matter", "WiFi"], source="vendor-docs",
        notes="Spec-Modellangabe „MTR“ unbestätigt — als Gen3-Baureihe geführt",
    ),
    DeviceRecord(
        id="tp_link_tapo_p110", name="Tapo P110 (Matter)", type="plug",
        category="SMART_HOME", vendor="TP-Link", model="P110M",
        technologies=["Matter", "WiFi"], source="vendor-docs",
        notes="Matter via WLAN; Energiemonitoring",
    ),
    DeviceRecord(
        id="tado_thermostat_x", name="Tado Thermostat X", type="thermostat",
        category="SMART_HOME", vendor="Tado", model="Tado X",
        technologies=["Thread", "Matter"], source="vendor-docs",
        notes="Matter-over-Thread (X-Serie)",
    ),
    DeviceRecord(
        id="wiz_light_matter", name="WiZ Smart Bulb (Matter)", type="light",
        category="SMART_HOME", vendor="WiZ (Signify)", model="A19 Matter",
        technologies=["Matter", "WiFi"], source="vendor-docs",
        notes="Matter via WLAN",
    ),
    DeviceRecord(
        id="yale_assure_lock_2_matter", name="Yale Assure Lock 2 (Matter)", type="lock",
        category="SMART_HOME", vendor="Yale", model="YRD410",
        technologies=["Thread", "Matter", "BLE"], source="vendor-docs",
        notes="Matter-Modul (Thread)",
    ),
    DeviceRecord(
        id="belkin_wemo_stage", name="Wemo Stage Scene Controller", type="remote",
        category="SMART_HOME", vendor="Belkin", model="WSC010",
        technologies=["Thread", "Matter"], source="vendor-docs",
        notes="Matter-over-Thread; Company-ID 0x005C (SIG)",
    ),
    DeviceRecord(
        id="level_lock_plus", name="Level Lock+", type="lock",
        category="SMART_HOME", vendor="Level Home", model="A0284",
        technologies=["Thread", "Matter", "BLE"], source="vendor-docs",
        notes="Matter-over-Thread; unsichtbares Einsteckschloss",
    ),
    DeviceRecord(
        id="ikea_myggspray", name="IKEA MYGGSPRAY", type="sensor",
        category="SMART_HOME", vendor="IKEA", model="MYGGSPRAY",
        technologies=["Thread", "Matter"], source="vendor-docs",
        notes="Bewegungssensor (innen/außen), Matter, Nachfolger Vallhorn; Marktstart Jan 2026",
    ),
    DeviceRecord(
        id="ikea_myggbett", name="IKEA MYGGBETT", type="sensor",
        category="SMART_HOME", vendor="IKEA", model="MYGGBETT",
        technologies=["Thread", "Matter"], source="vendor-docs",
        notes="Tür-/Fenstersensor, Matter, Nachfolger Parasoll; Marktstart Jan 2026",
    ),
    DeviceRecord(
        id="ikea_klippbok", name="IKEA KLIPPBOK", type="sensor",
        category="SMART_HOME", vendor="IKEA", model="KLIPPBOK",
        technologies=["Thread", "Matter"], source="vendor-docs",
        notes="Wasserleck-Sensor, Matter, Nachfolger Badring; Marktstart Jan 2026",
    ),
    DeviceRecord(
        id="ikea_timmerflotte", name="IKEA TIMMERFLOTTE", type="sensor",
        category="SMART_HOME", vendor="IKEA", model="TIMMERFLOTTE",
        technologies=["Thread", "Matter"], source="vendor-docs",
        notes="Temperatur-/Feuchtesensor mit Display, Matter; Marktstart Jan 2026",
    ),
    DeviceRecord(
        id="ikea_alpstuga", name="IKEA ALPSTUGA", type="sensor",
        category="SMART_HOME", vendor="IKEA", model="ALPSTUGA",
        technologies=["Thread", "Matter"], source="vendor-docs",
        notes="Luftqualitätsmonitor (CO₂, PM2.5, Temp., Feuchte), Matter; Marktstart Jan 2026",
    ),
    # ── LoRaWAN (TTN Device Repository: 1.104 Geräte/149 Hersteller, ─────────
    #    Spec-Zahlen 1.099/146 veraltet) — EU868-Auswahl
    DeviceRecord(
        id="dragino_lps8", name="Dragino LPS8 Indoor Gateway", type="gateway",
        category="LORAWAN", vendor="Dragino", model="LPS8",
        technologies=["LoRaWAN"], frequency_bands=["EU868"], source="ttn-device-repository",
        notes="8-Kanal-Indoor-Gateway, OpenWrt",
    ),
    DeviceRecord(
        id="dragino_lht65", name="Dragino LHT65", type="sensor",
        category="LORAWAN", vendor="Dragino", model="LHT65",
        technologies=["LoRaWAN"], frequency_bands=["EU868"], source="ttn-device-repository",
        notes="Temperatur/Feuchte, Batterie",
    ),
    DeviceRecord(
        id="rak_rak7268", name="RAKwireless RAK7268 LTE WisGate Edge Lite 2", type="gateway",
        category="LORAWAN", vendor="RAKwireless", model="RAK7268",
        technologies=["LoRaWAN"], frequency_bands=["EU868"], source="ttn-device-repository",
        notes="Indoor-Gateway mit LTE-Backhaul",
    ),
    DeviceRecord(
        id="rak_rak7205", name="RAKwireless RAK7205", type="tracker",
        category="LORAWAN", vendor="RAKwireless", model="RAK7205",
        technologies=["LoRaWAN"], frequency_bands=["EU868"], source="ttn-device-repository",
        notes="GPS-Tracker mit Beschleunigungssensor",
    ),
    DeviceRecord(
        id="minew_lsg01", name="Minew LSG01 Air Quality", type="sensor",
        category="LORAWAN", vendor="Minew", model="LSG01",
        technologies=["LoRaWAN"], frequency_bands=["EU868"], source="ttn-device-repository",
        notes="6-in-1-Luftqualitätssensor (Spec-Angabe, TTN-Repo)",
    ),
    DeviceRecord(
        id="minew_lsd01", name="Minew LSD01 Door Sensor", type="sensor",
        category="LORAWAN", vendor="Minew", model="LSD01",
        technologies=["LoRaWAN"], frequency_bands=["EU868"], source="ttn-device-repository",
        notes="Tür-/Fenstersensor (Spec-Angabe, TTN-Repo)",
    ),
    DeviceRecord(
        id="minew_ltb01g", name="Minew LTB01-G GPS Asset Tracker", type="tracker",
        category="LORAWAN", vendor="Minew", model="LTB01-G",
        technologies=["LoRaWAN"], frequency_bands=["EU868"], source="ttn-device-repository",
        notes="Asset-Tracker (Spec-Angabe, TTN-Repo)",
    ),
    DeviceRecord(
        id="elsist_em300_th", name="Elsist EM300-TH", type="sensor",
        category="LORAWAN", vendor="Elsist", model="EM300-TH",
        technologies=["LoRaWAN"], frequency_bands=["EU868"], source="ttn-device-repository",
        notes="Temperatur/Feuchte",
    ),
    DeviceRecord(
        id="elsist_em300_mcs", name="Elsist EM300-MCS", type="sensor",
        category="LORAWAN", vendor="Elsist", model="EM300-MCS",
        technologies=["LoRaWAN"], frequency_bands=["EU868"], source="ttn-device-repository",
        notes="Magnetkontakt (Tür/Fenster)",
    ),
    DeviceRecord(
        id="elsist_em300_sld", name="Elsist EM300-SLD", type="sensor",
        category="LORAWAN", vendor="Elsist", model="EM300-SLD",
        technologies=["LoRaWAN"], frequency_bands=["EU868"], source="ttn-device-repository",
        notes="Leckage-Erkennung",
    ),
    DeviceRecord(
        id="elsist_em300_di", name="Elsist EM300-DI", type="sensor",
        category="LORAWAN", vendor="Elsist", model="EM300-DI",
        technologies=["LoRaWAN"], frequency_bands=["EU868"], source="ttn-device-repository",
        notes="Impulszähler",
    ),
    DeviceRecord(
        id="imst_ioke868", name="IMST iOKE868 Smart Meter Reader", type="meter_reader",
        category="LORAWAN", vendor="IMST", model="iOKE868",
        technologies=["LoRaWAN"], frequency_bands=["EU868"], source="ttn-device-repository",
        notes="Zählerauslesung über LoRaWAN",
    ),
    DeviceRecord(
        id="wilsen_node", name="WILSEN.node", type="sensor",
        category="LORAWAN", vendor="Pepperl+Fuchs (WILSEN)", model="WILSEN.node",
        technologies=["LoRaWAN"], frequency_bands=["EU868"], source="ttn-device-repository",
        notes="Sensorknoten für Füllstand/Abstand",
    ),
    DeviceRecord(
        id="netvox_r718n37", name="Netvox R718N37", type="meter",
        category="LORAWAN", vendor="Netvox", model="R718N37",
        technologies=["LoRaWAN"], frequency_bands=["EU868"], source="ttn-device-repository",
        notes="Drehstromzähler-Messkopf (Spec-Angabe, TTN-Repo)",
    ),
    DeviceRecord(
        id="multitech_rbs3010", name="MultiTech RBS3010 Door/Window (EU868)", type="sensor",
        category="LORAWAN", vendor="MultiTech", model="RBS3010",
        technologies=["LoRaWAN"], frequency_bands=["EU868"], source="ttn-device-repository",
        notes="Radio-Bridge-Sensorlinie",
    ),
    DeviceRecord(
        id="m5stack_atom_dtu_lorawan", name="M5Stack ATOM DTU LoRaWAN (EU868)", type="dtu",
        category="LORAWAN", vendor="M5Stack", model="A152-EU868",
        technologies=["LoRaWAN"], frequency_bands=["EU868"], source="vendor-docs",
        notes="Entwicklungs-DTU, ATOM-Basis",
    ),
    DeviceRecord(
        id="zenner_iot_gateway_outdoor", name="ZENNER IoT Gateway Outdoor", type="gateway",
        category="LORAWAN", vendor="ZENNER", model="IoT Gateway outdoor",
        technologies=["LoRaWAN"], frequency_bands=["EU868"], source="spec",
        verified=False, notes="Gateway-Modellbezeichnung „16“ nicht verifiziert",
    ),
    # ── Wireless M-Bus / Smart Metering (OMS, 868 MHz) ───────────────────────
    DeviceRecord(
        id="zenner_wmbus_water_meter", name="ZENNER Wasserzähler (wM-Bus)", type="water_meter",
        category="METERING", vendor="ZENNER", model="EDC B.One (Modul)",
        technologies=["Wireless M-Bus"], frequency_bands=["868 MHz"], source="vendor-docs",
        notes="Ultraschall-Wasserzähler; EDC B.One-Modul wM-Bus/LoRaWAN wählbar",
    ),
    DeviceRecord(
        id="zenner_wmbus_heat_meter", name="ZENNER Wärmezähler (wM-Bus)", type="heat_meter",
        category="METERING", vendor="ZENNER", model="Heizko + wM-Bus",
        technologies=["Wireless M-Bus"], frequency_bands=["868 MHz"], source="vendor-docs",
        notes="Fernauslesung nach OMS",
    ),
    DeviceRecord(
        id="elvaco_cme_series", name="Elvaco CMe-Serie", type="meter_module",
        category="METERING", vendor="Elvaco", model="CMe",
        technologies=["Wireless M-Bus"], frequency_bands=["868 MHz"], source="vendor-docs",
        notes="Zähler-Kommunikationsmodule (wM-Bus)",
    ),
    DeviceRecord(
        id="weptech_myna", name="WEPTECH Myna", type="sensor",
        category="METERING", vendor="WEPTECH", model="Myna",
        technologies=["Wireless M-Bus"], frequency_bands=["868 MHz"], source="spec",
        verified=False, notes="Temperatursensor (Spec-Angabe, Modell unverifiziert)",
    ),
    DeviceRecord(
        id="weptech_munia", name="WEPTECH Munia", type="sensor",
        category="METERING", vendor="WEPTECH", model="Munia",
        technologies=["Wireless M-Bus"], frequency_bands=["868 MHz"], source="spec",
        verified=False, notes="Temperatur/Feuchte (Spec-Angabe, Modell unverifiziert)",
    ),
    DeviceRecord(
        id="solvimus_mbus_gewb", name="Solvimus MBUS-GEWB Gateway", type="gateway",
        category="METERING", vendor="Solvimus", model="MBUS-GEWB",
        technologies=["Wireless M-Bus"], frequency_bands=["868 MHz"], source="spec",
        verified=False, notes="wM-Bus-Gateway (Spec-Angabe, Modell unverifiziert)",
    ),
    DeviceRecord(
        id="solvimus_mbus_ge5b", name="Solvimus MBUS-GE5B Gateway", type="gateway",
        category="METERING", vendor="Solvimus", model="MBUS-GE5B",
        technologies=["Wireless M-Bus"], frequency_bands=["868 MHz"], source="spec",
        verified=False, notes="wM-Bus-Gateway (Spec-Angabe, Modell unverifiziert)",
    ),
    DeviceRecord(
        id="stackforce_wmbus_stack", name="Stackforce wM-Bus Protocol Stack", type="software_stack",
        category="METERING", vendor="Stackforce", model="wM-Bus Stack",
        technologies=["Wireless M-Bus"], frequency_bands=["868 MHz"], source="vendor-docs",
        notes="Protokoll-Stack für OMS/wM-Bus (EN 13757)",
    ),
    # ── ISM 433 MHz — generische Geräteklassen (SRD 433,05–434,79 MHz, ───────
    #    Europa/Region 1; ERC/ETSI-Dokumentation)
    DeviceRecord(
        id="ism433_garage_remote", name="Garagentor-Funkfernbedienung", type="remote_control",
        category="ISM_433", vendor="Generic (433 MHz ISM)", model="class",
        technologies=["ISM 433 MHz"], frequency_bands=["433.05–434.79 MHz"], source="regulation",
        notes="Generische Klasse — keine Erkennung ohne Hersteller-Signatur",
    ),
    DeviceRecord(
        id="ism433_shutter_remote", name="Rollladen-Funkfernbedienung", type="remote_control",
        category="ISM_433", vendor="Generic (433 MHz ISM)", model="class",
        technologies=["ISM 433 MHz"], frequency_bands=["433.05–434.79 MHz"], source="regulation",
        notes="Generische Klasse",
    ),
    DeviceRecord(
        id="ism433_door_sensor", name="Fenster-/Türsensor (433 MHz)", type="sensor",
        category="ISM_433", vendor="Generic (433 MHz ISM)", model="class",
        technologies=["ISM 433 MHz"], frequency_bands=["433.05–434.79 MHz"], source="regulation",
        notes="Generische Klasse",
    ),
    DeviceRecord(
        id="ism433_smoke_detector", name="Rauchmelder (433 MHz)", type="smoke_detector",
        category="ISM_433", vendor="Generic (433 MHz ISM)", model="class",
        technologies=["ISM 433 MHz"], frequency_bands=["433.05–434.79 MHz"], source="regulation",
        notes="Generische Klasse",
    ),
    DeviceRecord(
        id="ism433_baby_monitor", name="Babyphone (433 MHz)", type="audio_device",
        category="ISM_433", vendor="Generic (433 MHz ISM)", model="class",
        technologies=["ISM 433 MHz"], frequency_bands=["433.05–434.79 MHz"], source="regulation",
        notes="Generische Klasse (analoge/433-MHz-Modelle)",
    ),
    DeviceRecord(
        id="ism433_radar_motion", name="Radar-Bewegungsmelder (433 MHz)", type="motion_detector",
        category="ISM_433", vendor="Generic (433 MHz ISM)", model="class",
        technologies=["ISM 433 MHz"], frequency_bands=["433.05–434.79 MHz"], source="regulation",
        notes="Generische Klasse (CW-/Doppler-Radar)",
    ),
    # ── Medizinische BLE-Geräte (Herstellerdoku; Company-IDs verifiziert) ────
    DeviceRecord(
        id="dexcom_g7", name="Dexcom G7 CGM", type="cgm",
        category="MEDICAL", vendor="Dexcom", model="G7",
        technologies=["BLE"], source="vendor-docs",
        notes="Kontinuierliche Glukosemessung; Company-ID 0x00D0 (SIG)",
    ),
    DeviceRecord(
        id="abbott_freestyle_libre3", name="FreeStyle Libre 3", type="cgm",
        category="MEDICAL", vendor="Abbott", model="Libre 3",
        technologies=["BLE", "NFC"], source="vendor-docs",
        notes="Glukosesensor; Company-ID 0x03BB (SIG)",
    ),
    DeviceRecord(
        id="oura_ring_gen4", name="Oura Ring Gen4", type="smart_ring",
        category="MEDICAL", vendor="Oura Health", model="Gen4",
        technologies=["BLE"], source="vendor-docs",
        notes="Schlaf-/Aktivitätsring; Company-ID der Spec (0xFDB0) existiert nicht — nicht übernommen",
    ),
    DeviceRecord(
        id="whoop_40", name="WHOOP 4.0", type="wearable",
        category="MEDICAL", vendor="WHOOP", model="4.0",
        technologies=["BLE"], source="vendor-docs",
        notes="Fitness-/Recovery-Armband",
    ),
    DeviceRecord(
        id="biobeat_patch", name="BioBeat Brustpatch", type="vital_monitor",
        category="MEDICAL", vendor="BioBeat", model="BB-613WP",
        technologies=["BLE"], source="vendor-docs",
        notes="Vitaldaten-Monitoring (Patch)",
    ),
    DeviceRecord(
        id="empatica_embraceplus", name="Empatica EmbracePlus", type="vital_monitor",
        category="MEDICAL", vendor="Empatica", model="EmbracePlus",
        technologies=["BLE"], source="vendor-docs",
        notes="Medizinisches Wearable (FDA-zugelassen)",
    ),
    DeviceRecord(
        id="hearing_aid_le_audio", name="Bluetooth-Hörgerät (LE Audio/HAP)", type="hearing_aid",
        category="MEDICAL", vendor="Phonak/Signia/ReSound u. a.", model="class",
        technologies=["BLE"], source="vendor-docs",
        notes="Generische Klasse; LE Audio (HAP) nach Bluetooth 5.2+",
    ),
]
