"""Tests für die Offline-Gerätedatenbank (docs/DEVICE_DATABASE.md)."""

import pytest

from device_db import (
    COMPANY_IDS,
    GATT_STANDARD_SERVICES,
    TRACKER_PROFILES,
    DeviceDatabase,
    DeviceRecord,
    OuiDatabase,
    lookup_company,
    lookup_gatt_service,
    lookup_tracker,
    normalize_company_id,
    normalize_mac,
    normalize_uuid16,
    oui_prefix,
)
from device_db_builder import (
    build,
    parse_bluetooth_services,
    parse_company_ids,
    parse_oui_export,
    parse_z2m_devices,
)

# ─── Normalisierung ─────────────────────────────────────────────────────────


def test_mac_normalization_accepts_separators():
    assert normalize_mac("aa:bb:cc:dd:ee:ff") == "AA:BB:CC:DD:EE:FF"
    assert normalize_mac("AABB.CCDD.EEFF") == "AA:BB:CC:DD:EE:FF"
    assert normalize_mac("aabbccddeeff") == "AA:BB:CC:DD:EE:FF"
    with pytest.raises(ValueError):
        normalize_mac("aa:bb")


def test_uuid_normalization_16_and_128():
    assert normalize_uuid16("180d") == "0x180D"
    assert normalize_uuid16("0xFEED") == "0xFEED"
    assert normalize_uuid16("00001530-1212-efde-1523-785feabcd123") == \
        "0x000015301212EFDE1523785FEABCD123"


# ─── GATT ───────────────────────────────────────────────────────────────────


def test_gatt_standard_services_cover_spec_table():
    uuids = {s.uuid for s in GATT_STANDARD_SERVICES}
    for expected in ("0x1800", "0x1801", "0x180A", "0x180D", "0x180F", "0x1816", "0x1826", "0x183A"):
        assert expected in uuids
    hr = lookup_gatt_service("0x180D")
    assert hr.name == "Heart Rate"
    assert "0x2A37" in [c.uuid for c in hr.characteristics]
    battery = lookup_gatt_service("180f")
    assert battery.name == "Battery Service"
    assert lookup_gatt_service("0xFFFF") is None


# ─── OUI ────────────────────────────────────────────────────────────────────


def test_oui_lookup_prefers_longer_prefix():
    db = OuiDatabase({
        "00:1A:22": "Honeywell",
        "00:1A:22:33": "Honeywell (MA-M-Block)",
    })
    # 28-Bit-Block trifft → spezifischerer Eintrag gewinnt
    assert db.lookup("00:1A:22:33:44:55") == "Honeywell (MA-M-Block)"
    # 24-Bit-Basis greift bei anderem viertem Oktett
    assert db.lookup("00:1A:22:AA:BB:CC") == "Honeywell"
    assert db.lookup("00:1A:23:AA:BB:CC") is None
    assert oui_prefix("aa:bb:cc:dd:ee:ff") == "AA:BB:CC"
    assert oui_prefix("aa:bb:cc:dd:ee:ff", bits=16) == "AA:BB"


# ─── Tracker-Profile (mit Spec-Korrekturen) ────────────────────────────────


def test_tracker_profiles_correct_tile_uuids():
    tile = next(t for t in TRACKER_PROFILES if t.id == "tile")
    # Korrektur der Spec: 0xFEAA ist Eddystone (Google), nicht Tile
    assert "0xFEED" in tile.service_uuids
    assert "0xFEEC" in tile.service_uuids
    assert "0xFEAA" not in tile.service_uuids
    # Company-ID 0x0055 der Spec ist unbestätigt → None im Modell
    assert tile.company_id is None


def test_tracker_lookup_by_company_and_service():
    assert any(t.id == "apple_airtag" for t in lookup_tracker(company_id=0x004C))
    hits = lookup_tracker(service_uuids=["0xFEED"])
    assert any(t.id == "tile" for t in hits)
    google = lookup_tracker(company_id=0x00E0)
    assert any(t.id == "google_pixel_tag" for t in google)


def test_company_ids_verified_vendors():
    assert COMPANY_IDS[0x004C] == "Apple, Inc."
    assert COMPANY_IDS[0x0075].startswith("Samsung")
    assert COMPANY_IDS[0x00E0] == "Google"


def test_company_ids_spec_v17_corrections():
    """Spec v17: falsche Company-IDs korrigiert (Nordic/SIG-Verifikation)."""
    assert COMPANY_IDS[0x0000] == "Ericsson AB"          # Spec: „Ericsson Technology Licensing"
    assert COMPANY_IDS[0x017A] == "Telemonitor, Inc."    # Spec: „Telemontior" (Tippfehler)
    assert COMPANY_IDS[0x038F] == "Xiaomi Inc."          # Spec: 0xFDAB (existiert nicht)
    assert COMPANY_IDS[0x0065] == "HP, Inc."             # Spec: 0xFDB4 (existiert nicht)
    # 0xFDxx-Claims der Spec existieren nicht als Company-IDs
    for bogus in (0xFDAB, 0xFDB0, 0xFDB4, 0xFDB5):
        assert bogus not in COMPANY_IDS
    # Verifizierte Zusätze (Spec-Tabelle bestätigt)
    assert COMPANY_IDS[0x0093] == "Universal Electronics, Inc."
    assert COMPANY_IDS[0x00C4] == "LG Electronics"
    assert COMPANY_IDS[0x017B] == "taskit GmbH"
    assert COMPANY_IDS[0x017E] == "BluDotz Ltd"
    assert COMPANY_IDS[0x03D5] == "Wyzelink Systems Inc."
    assert COMPANY_IDS[0x0520] == "Target Corporation"
    assert COMPANY_IDS[0x0544] == "OrthoSensor, Inc."
    assert COMPANY_IDS[0x0568] == "Bodyport Inc."
    assert COMPANY_IDS[0x0739] == "Jiangsu Qinheng Co., Ltd."
    assert COMPANY_IDS[0x0A53] == "KKM COMPANY LIMITED"
    assert COMPANY_IDS[0x0A54] == "SQL Technologies Corp."


def test_normalize_and_lookup_company():
    assert normalize_company_id(76) == 0x004C
    assert normalize_company_id("76") == 0x004C
    assert normalize_company_id("0x004C") == 0x004C
    assert normalize_company_id("004c") == 0x004C
    assert normalize_company_id(" 0x00d0 ") == 0x00D0
    assert normalize_company_id("zz") is None
    assert normalize_company_id("") is None
    assert lookup_company("0x004C") == "Apple, Inc."
    assert lookup_company(0x00D0) == "Dexcom, Inc."
    assert lookup_company("0xFDAB") is None
    assert lookup_company("xyz") is None


# ─── DeviceDatabase ─────────────────────────────────────────────────────────


def test_seed_database_queries():
    db = DeviceDatabase.seed()
    assert len(db) >= 8
    # MAC-freie Records; Kategorie-Zählung
    categories = db.categories()
    assert categories.get("SMART_HOME", 0) >= 4
    assert categories.get("UWB", 0) >= 2
    # Suche
    assert any(r.id == "ikea_tradfri_e1603" for r in db.search("TRÅDFRI"))
    assert any(r.id == "qorvo_dwm3000" for r in db.search("dwm", category="UWB"))
    assert not db.search("dwm", category="SMART_HOME")
    # Service-Lookup
    assert any(r.id == "xiaomi_m365" for r in db.by_service("0xFFE0"))


def test_seed_extended_categories_v17():
    """Spec v17: Thread/Matter, LoRaWAN, wM-Bus, ISM 433, Medizin-BLE."""
    db = DeviceDatabase.seed()
    categories = db.categories()
    assert categories.get("LORAWAN", 0) >= 16
    assert categories.get("METERING", 0) >= 8
    assert categories.get("ISM_433", 0) >= 6
    assert categories.get("MEDICAL", 0) >= 7
    tech = db.technologies()
    assert tech.get("Thread", 0) >= 20
    assert tech.get("Matter", 0) >= 20
    assert tech.get("LoRaWAN", 0) >= 16
    assert tech.get("Wireless M-Bus", 0) >= 8
    assert tech.get("ISM 433 MHz", 0) >= 6


def test_search_by_technology():
    db = DeviceDatabase.seed()
    lorawan = db.search("", technology="LoRaWAN")
    assert lorawan and all("LoRaWAN" in r.technologies for r in lorawan)
    assert all(r.frequency_bands == ["EU868"] for r in lorawan)
    thread = db.search("", technology="thread")  # case-insensitiv
    assert thread and all("Thread" in r.technologies for r in thread)
    combined = db.search("", category="SMART_HOME", technology="Thread")
    assert combined and all(r.category == "SMART_HOME" for r in combined)
    assert not db.search("", category="MEDICAL", technology="LoRaWAN")
    assert any(r.id == "ikea_alpstuga" for r in db.search("alpstuga", technology="Matter"))


def test_seed_v17_specifics():
    db = DeviceDatabase.seed()
    # IKEA-2025-Sensoren (5 Stück, nicht „7" wie in der Spec)
    ikea2025 = [r.id for r in db.search("", category="SMART_HOME") if r.vendor == "IKEA"
                and r.model in ("MYGGSPRAY", "MYGGBETT", "KLIPPBOK", "TIMMERFLOTTE", "ALPSTUGA")]
    assert len(ikea2025) == 5
    # LoRaWAN-Modelle mit EU868-Band
    assert db.by_id("dragino_lps8").frequency_bands == ["EU868"]
    assert db.by_id("wilsen_node").vendor.startswith("Pepperl+Fuchs")
    # wM-Bus: verifizierte vs. Spec-only-Einträge
    assert db.by_id("zenner_wmbus_water_meter").verified is True
    assert db.by_id("weptech_myna").verified is False
    # Medizin: Company-IDs der Hersteller verifiziert
    assert db.by_id("dexcom_g7").category == "MEDICAL"
    assert db.by_id("abbott_freestyle_libre3").technologies == ["BLE", "NFC"]


def test_frequency_bands_roundtrip():
    record = DeviceRecord(
        id="demo_lorawan", name="Demo", type="gateway", category="LORAWAN",
        vendor="V", technologies=["LoRaWAN"], frequency_bands=["EU868"],
    )
    data = record.to_dict()
    assert data["frequency_bands"] == ["EU868"]
    restored = DeviceRecord.from_dict(data)
    assert restored.frequency_bands == ["EU868"]
    assert restored.technologies == ["LoRaWAN"]


def test_database_json_roundtrip(tmp_path):
    db = DeviceDatabase.seed()
    path = tmp_path / "device_db.json"
    db.save(str(path))
    loaded = DeviceDatabase.load(str(path))
    assert len(loaded) == len(db)
    assert loaded.by_id("sonoff_s31").vendor == "SONOFF"
    assert loaded.by_id("xiaomi_m365").verified is False  # Community-Quelle bleibt markiert


def test_database_rejects_duplicate_ids():
    a = DeviceRecord(id="dup", name="X", type="t", category="OTHER", vendor="V")
    b = DeviceRecord(id="dup", name="Y", type="t", category="OTHER", vendor="V")
    with pytest.raises(ValueError):
        DeviceDatabase.validate_list([a, b])
    # Pflichtfelder
    with pytest.raises(ValueError):
        DeviceDatabase.validate_list([DeviceRecord(id="x", name="", type="t", category="OTHER", vendor="V")])


def test_by_mac_uses_prefix():
    db = DeviceDatabase([
        DeviceRecord(id="hw", name="Honeywell Gerät", type="scanner",
                     category="OTHER", vendor="Honeywell", mac_prefix="00:1A:22"),
    ])
    assert len(db.by_mac("00:1A:22:AA:BB:CC")) == 1
    assert db.by_mac("00:1A:23:AA:BB:CC") == []


# ─── Builder-Parser (Fixtures) ─────────────────────────────────────────────


def test_parse_oui_export_list_and_dict():
    export = [
        {"macPrefix": "00:1A:22", "vendorName": "Honeywell"},
        {"macPrefix": "00:50:56", "vendorName": "VMware, Inc."},
    ]
    db = parse_oui_export(export)
    assert db.lookup("00:1A:22:00:00:01") == "Honeywell"
    assert db.lookup("00:50:56:00:00:01") == "VMware, Inc."
    db2 = parse_oui_export({"00:11:22": "Dell"})
    assert db2.lookup("00:11:22:33:44:55") == "Dell"


def test_parse_bluetooth_services():
    data = {"service_uuids": [
        {"uuid": "180D", "name": "Heart Rate"},
        {"uuid": "180F", "name": "Battery Service"},
        {"uuid": "00001530-1212-efde-1523-785feabcd123", "name": "Ignoriert (128-Bit)"},
    ]}
    services = parse_bluetooth_services(data)
    assert services == {"0x180D": "Heart Rate", "0x180F": "Battery Service"}


def test_parse_z2m_devices():
    data = [
        {"vendor": "IKEA", "models": [
            {"model": "E1603", "description": "TRÅDFRI bulb"},
            {"model": "E1743", "description": "TRÅDFRI dimmer"},
        ]},
        {"vendor": "Signify", "models": [
            {"model": "9290012573", "description": "Hue White"},
        ]},
    ]
    records = parse_z2m_devices(data)
    assert len(records) == 3
    ikea = next(r for r in records if r.model == "E1603")
    assert ikea.vendor == "IKEA"
    assert ikea.source == "zigbee2mqtt"
    assert ikea.technologies == ["Zigbee"]


def test_parse_company_ids():
    data = [
        {"code": 76, "name": "Apple, Inc."},
        {"code": 911, "name": "Xiaomi Inc."},
        {"code": 65536, "name": "Ungültig (> 0xFFFF)"},
        {"code": 117, "name": ""},
        {"name": "Ohne Code"},
    ]
    company_ids = parse_company_ids(data)
    assert company_ids == {76: "Apple, Inc.", 911: "Xiaomi Inc."}


def test_build_combines_sources():
    result = build(
        z2m_json=[{"vendor": "IKEA", "models": [{"model": "E1603", "description": "Bulb"}]}],
        bt_numbers_json={"service_uuids": [{"uuid": "180D", "name": "Heart Rate"}]},
        company_ids_json=[{"code": 76, "name": "Apple, Inc."}, {"code": 911, "name": "Xiaomi Inc."}],
        mac_vendors_json=[{"macPrefix": "00:1A:22", "vendorName": "Honeywell"}],
        limit_z2m=10,
    )
    assert len(result["records"]) >= 55  # Seed (inkl. Spec-17-Kategorien) + Z2M
    assert any(r["id"] == "z2m_ikea_e1603" for r in result["records"])
    assert result["oui"]["00:1A:22"] == "Honeywell"
    assert result["gatt_services"]["0x180D"] == "Heart Rate"
    assert result["company_ids"] == {"0x004C": "Apple, Inc.", "0x038F": "Xiaomi Inc."}
    # Frequenzbänder der Spec-17-Records bleiben im Build erhalten
    lorawan = next(r for r in result["records"] if r["id"] == "dragino_lps8")
    assert lorawan["frequency_bands"] == ["EU868"]
