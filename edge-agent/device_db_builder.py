"""Offline-Gerätedatenbank-Builder — Konsolidierung der Online-Quellen
(docs/DEVICE_DATABASE.md).

Lädt aus den öffentlichen Quellen und baut `data/device_db.json`:
- Zigbee2MQTT-Geräteliste (Koenkk/zigbee2mqtt.io, docgen-JSON),
- Bluetooth-Numbers-DB (NordicSemiconductor, 16-Bit-Service-UUIDs),
- Bluetooth-Numbers-DB (NordicSemiconductor, Company-IDs),
- MAC-OUI-Datenbank (mac-address-vendor-database, JSON-Export).

Hinweise:
- Der Builder muss auf einer Maschine **mit Netzwerkzugang** laufen
  (die Sandbox blockiert externen HTTPS); die Parser sind über lokale
  Dateipfade unit-testbar.
- Die URLs sind Konstanten und können überschrieben werden (Spiegel).
- Community-Kommandoquellen (Scooter/E-Bike-Frames) werden bewusst
  **nicht** automatisch importiert — sie sind Reverse-Engineering und
  werden nur mit `verified=false` + Referenz aufgenommen (s. Seed).
"""

from __future__ import annotations

import json
from typing import Any, Dict, List, Optional

import httpx

from device_db import (
    DeviceDatabase,
    DeviceRecord,
    GATT_STANDARD_SERVICES,
    OuiDatabase,
    normalize_uuid16,
)

DEFAULT_USER_AGENT = "3dxAgent-device-db-builder/1.0 (https://github.com/dang88bang-pixel/88P3dKart.-Art)"

# Quellen (öffentlich; URLs als Konstanten, überschreibbar)
Z2M_DEVICES_URL = "https://raw.githubusercontent.com/Koenkk/zigbee2mqtt.io/master/docgen/data/devices.json"
BT_NUMBERS_SERVICES_URL = "https://raw.githubusercontent.com/NordicSemiconductor/bluetooth-numbers-database/master/v1/service_uuids.json"
BT_NUMBERS_COMPANY_URL = "https://raw.githubusercontent.com/NordicSemiconductor/bluetooth-numbers-database/master/v1/company_ids.json"
MAC_VENDORS_URL = "https://raw.githubusercontent.com/nickoala/mac-address-vendor-database/main/mac-vendors-export.json"

# LoRaWAN Device Repository (TTN) wird NICHT automatisch importiert:
# vendor/<hersteller>/*.yaml (kein JSON-Index) bräuchte eine YAML-Abhängigkeit
# im Edge-Agent — die EU868-Auswahl ist kuratiert im Seed (⏳ Roadmap:
# Builder-Snapshot mit PyYAML auf einer Maschine mit Netzwerkzugang).


def fetch_json(url: str, timeout: float = 30.0, user_agent: str = DEFAULT_USER_AGENT) -> Any:
    response = httpx.get(url, headers={"User-Agent": user_agent}, timeout=timeout)
    response.raise_for_status()
    return response.json()


# ─── Parser (dateibasiert, unit-testbar) ───────────────────────────────────


def parse_oui_export(data: Any) -> OuiDatabase:
    """mac-address-vendor-database-Export → OuiDatabase.

    Unterstützt Listenform [{"macPrefix": "AA:BB:CC", "vendorName": "…"}]
    und Dict-Form {"AA:BB:CC": "Vendor"}.
    """
    db = OuiDatabase()
    entries = data.items() if isinstance(data, dict) else [
        (item.get("macPrefix"), item.get("vendorName")) for item in data if isinstance(item, dict)
    ]
    for prefix, vendor in entries:
        if prefix and vendor:
            db.add(str(prefix).upper(), str(vendor))
    return db


def parse_bluetooth_services(data: Any) -> Dict[str, str]:
    """Nordic bluetooth-numbers-database → {16-Bit-UUID: Name}.

    Struktur: {"service_uuids": [{"uuid": "180D", "name": "Heart Rate", …}]}.
    """
    items = data.get("service_uuids", data) if isinstance(data, dict) else data
    result: Dict[str, str] = {}
    for item in items:
        uuid = str(item.get("uuid", ""))
        name = str(item.get("name", ""))
        if re_short_uuid(uuid) and name:
            result[normalize_uuid16(uuid)] = name
    return result


def re_short_uuid(uuid: str) -> bool:
    cleaned = uuid.replace("0x", "").replace("0X", "")
    return len(cleaned) == 4 and all(c in "0123456789abcdefABCDEF" for c in cleaned)


def parse_company_ids(data: Any) -> Dict[int, str]:
    """Nordic company_ids.json → {Company-ID int: Herstellername}.

    Struktur: [{"code": 76, "name": "Apple, Inc."}, …] — Werte > 0xFFFF
    bzw. fehlende Felder werden übersprungen.
    """
    items = data if isinstance(data, list) else data.get("company_ids", [])
    result: Dict[int, str] = {}
    for item in items:
        if not isinstance(item, dict):
            continue
        code = item.get("code")
        name = str(item.get("name", "")).strip()
        if isinstance(code, int) and 0 <= code <= 0xFFFF and name:
            result[code] = name
    return result


def parse_z2m_devices(data: Any, limit: Optional[int] = None) -> List[DeviceRecord]:
    """Zigbee2MQTT-docgen-JSON → DeviceRecords.

    Die docgen-Struktur ist eine Liste je Hersteller:
    [{"vendor": "IKEA", "models": [{"model": "E1603", "description": …,
    "supports": …}]}] — Abweichungen werden tolerant übersprungen.
    """
    records: List[DeviceRecord] = []
    if isinstance(data, dict):
        data = data.get("devices", data.get("vendors", []))
    if not isinstance(data, list):
        return records
    for vendor_block in data:
        if not isinstance(vendor_block, dict):
            continue
        vendor = str(vendor_block.get("vendor") or vendor_block.get("name") or "Unknown")
        models = vendor_block.get("models") or vendor_block.get("devices") or []
        for model in models if isinstance(models, list) else []:
            if not isinstance(model, dict):
                continue
            model_id = str(model.get("model") or "")
            description = str(model.get("description") or model.get("name") or model_id)
            if not model_id:
                continue
            device_id = f"z2m_{vendor.lower().replace(' ', '_')}_{model_id.lower()}"
            records.append(
                DeviceRecord(
                    id=device_id,
                    name=description,
                    type="zigbee_device",
                    category="SMART_HOME",
                    vendor=vendor,
                    model=model_id,
                    technologies=["Zigbee"],
                    source="zigbee2mqtt",
                    verified=True,
                )
            )
            if limit and len(records) >= limit:
                return records
    return records


# ─── Builder ───────────────────────────────────────────────────────────────


def build(
    z2m_json: Any = None,
    bt_numbers_json: Any = None,
    company_ids_json: Any = None,
    mac_vendors_json: Any = None,
    limit_z2m: Optional[int] = None,
) -> Dict[str, Any]:
    """Baut die konsolidierte Datenbank (Parser kombinieren)."""
    db = DeviceDatabase.seed()

    if z2m_json is not None:
        for record in parse_z2m_devices(z2m_json, limit=limit_z2m):
            db.upsert(record)

    oui_entries: Dict[str, str] = {}
    if mac_vendors_json is not None:
        oui = parse_oui_export(mac_vendors_json)
        oui_entries = oui.entries

    services: Dict[str, str] = {
        s.uuid: s.name for s in GATT_STANDARD_SERVICES
    }
    if bt_numbers_json is not None:
        services.update(parse_bluetooth_services(bt_numbers_json))

    company_ids: Dict[str, str] = {}
    if company_ids_json is not None:
        company_ids = {f"0x{code:04X}": name for code, name in
                       sorted(parse_company_ids(company_ids_json).items())}

    return {
        "records": db.to_dict()["records"],
        "oui": oui_entries,
        "gatt_services": services,
        "company_ids": company_ids,
    }


def build_from_network(
    output_path: str,
    z2m_url: str = Z2M_DEVICES_URL,
    bt_url: str = BT_NUMBERS_SERVICES_URL,
    company_url: str = BT_NUMBERS_COMPANY_URL,
    oui_url: str = MAC_VENDORS_URL,
    limit_z2m: Optional[int] = None,
) -> Dict[str, int]:
    """Lädt die Quellen live und schreibt `data/device_db.json`."""
    errors: List[str] = []
    payload: Dict[str, Any] = {"records": [], "oui": {}, "gatt_services": {}, "company_ids": {}}

    try:
        z2m = fetch_json(z2m_url)
    except Exception as exc:  # noqa: BLE001
        z2m, errors = None, errors + [f"zigbee2mqtt: {exc}"]
    try:
        bt = fetch_json(bt_url)
    except Exception as exc:  # noqa: BLE001
        bt, errors = errors, errors + [f"bluetooth-numbers: {exc}"]
    try:
        company = fetch_json(company_url)
    except Exception as exc:  # noqa: BLE001
        company, errors = None, errors + [f"company-ids: {exc}"]
    try:
        oui = fetch_json(oui_url)
    except Exception as exc:  # noqa: BLE001
        oui, errors = None, errors + [f"oui: {exc}"]

    payload = build(
        z2m_json=z2m, bt_numbers_json=bt, company_ids_json=company,
        mac_vendors_json=oui, limit_z2m=limit_z2m,
    )
    with open(output_path, "w", encoding="utf-8") as fh:
        json.dump(payload, fh, ensure_ascii=False, indent=2)

    return {
        "records": len(payload["records"]),
        "oui_entries": len(payload["oui"]),
        "gatt_services": len(payload["gatt_services"]),
        "company_ids": len(payload["company_ids"]),
        "errors": errors,
    }
