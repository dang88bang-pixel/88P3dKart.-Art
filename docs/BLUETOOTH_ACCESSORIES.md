# 📡 Bluetooth-Zubehör – 3dxAgent Ökosystem

**Version:** 4.5.0-BT-Accessories · **Datum:** 14.08.2026

Das neue Bluetooth-Zubehör Ökosystem erweitert die bisherige BLE-Token Unterstützung (Company ID 0x0059, 9 Byte) auf ein vollständiges Geräte-Ökosystem mit 12+ Typen, GATT, Classic SPP, iBeacon/Eddystone und sicherem Pairing.

---

## 🎯 Übersicht Zubehörtypen

| Typ | Code | Frequ. | Batterie | GATT | Beschreibung | Sensorik | Nutzung im System |
|-----|------|--------|----------|------|--------------|----------|-------------------|
| `TOKEN_CLASSIC` | 0 | 1 Hz | Ja | Nein | nRF52840 legacy 9B | IMU, RSSI | Personen-Tracking via Triangulation |
| `TOKEN_PRO` | 1 | 2 Hz | Ja | Ja | V2 18B Payload | IMU, Temp, Flags, Batt | Erweitertes Tracking + SOS + Temp |
| `SENSOR_TAG` | 2 | 0.5 Hz | Ja | Ja | Umwelt Tag BME280/SHT4x | T/H/P/Luft | BIM Kontext, Evakuierung (Rauch/Temp) |
| `WEARABLE` | 3 | 10 Hz | Ja | Ja | Smartwatch | HR, Steps, HRV, IMU | Vitaldaten Avatare, Taktische Lage |
| `ASSET_TAG` | 4 | 1 Hz | Ja | Nein | iBeacon + Eddystone UID/URL/TLM | RSSI, TX | Asset Tracking, Ausrüstung |
| `REMOTE_CONTROLLER` | 5 | 20 Hz | Ja | Ja | Key-Fob / Gamepad | Buttons, Joystick | Szenario-Steuerung, SOS |
| `RELAY` | 6 | 5 Hz | Ja | Nein | Smartphone Bridge | BLE→MQTT | Externe Phones als BLE-Relay |
| `GATEWAY_BRIDGE` | 7 | 0.2 Hz | Nein | Ja | BLE↔WiFi/LoRa/Zigbee | Netzwerk | Protokoll-Übersetzer |
| `AUDIO_BEACON` | 8 | 1 Hz | Ja | Ja | LE Audio Auracast | Audio Broadcast | Sprachkommandos |
| `CLASSIC_SPP` | 9 | 10 Hz | Ja | Nein | HC-05/06 Classic | Serial Data | Legacy Sensoren |
| `HEADSET` | 10 | 1 Hz | Ja | Nein | BT Headset A2DP/HSP | Mikro/Audio | Sprachsteuerung |
| `HID` | 11 | 50 Hz | Ja | Nein | Tastatur/Presenter | HID | Präsentation |

---

## 📦 Protokoll V2 (erweitert)

### Manufacturer Data 0x0059 (Nordic – 3dxAgent)

```
[0..1]  Company ID LE 0x0059
[2]     Protocol Version (1=legacy, 2=extended)
[3]     Accessory Type (siehe oben)
[4..5]  Accel X Int16 /1000 g
[6..7]  Accel Y Int16 /1000 g
[8..9]  Accel Z Int16 /1000 g
[10]    Battery %
[11..12] Temperature Int16 /100 °C
[13]    Flags:
        bit0 MOVING, bit1 BUTTON, bit2 LOW_BAT, bit3 TAMPER, bit4 CALIB, bit5 OTA, bit6 FALL, bit7 SOS
[14..15] Extra: Jenach Typ – humidity % / HR bpm / button_state
[16..17] Extra2: pressure hPa*10 / steps / joystick
```

Backward-kompatibel: Parser unterstützt V1 legacy 9 Byte automatisch.

### iBeacon (Apple 0x004C) + Eddystone (0xFEAA)
- iBeacon: `02 15 UUID major minor tx`
- Eddystone: UID (0x00), URL (0x10), TLM (0x20) mit Battery + Temp

### GATT Custom Service
- Service UUID: `8d81e7c0-b7c8-4b26-b0ea-e8b10bc7f1e0`
- Chars: Data (Notify 0xc1), Config (Write 0xc2, JSON), Command (Write 0xc3)
- Standard: Battery Service 0x180F, Device Info 0x180A, Env Sensing 0x181A, HRM 0x180D

---

## 🤖 Android (Kotlin) – `com.example.agent.bluetooth`

### Module

| Datei | Zweck |
|-------|-------|
| `BluetoothAccessoryType.kt` | Enums + UUIDs + Flags + Company IDs |
| `BluetoothAccessory.kt` | Datenmodell + toClientRegistration() + Distanzschätzung |
| `BleAdvertisementParser.kt` | Parser für alle Advertising Formate (V1/V2/iBeacon/Eddystone) |
| `GattServiceManager.kt` | GATT Client – Connect, Service Discovery, Notify, Write Config/Command |
| `BluetoothClassicManager.kt` | Classic SPP – HC-05, Headset, HID Discovery, RFCOMM |
| `AccessoryBondingManager.kt` | Secure Pairing, LTK, API-Key Generierung, PIN Handling |
| `AccessoryHealthMonitor.kt` | Health Score – Qualität 40%, Batterie 25%, RSSI 20%, Distanz 15% |
| `BluetoothAccessoryManager.kt` | Zentraler Manager – Multi-Filter Scan, ADaptive Modi, Registry Integration |
| `BluetoothPermissions.kt` | Permission Checks |
| `BluetoothAccessoryScanService.kt` | Foreground Service für Dauer-Scan im BOS Einsatz |

### Scan Modi

- `LOW_POWER` – Hintergrund, selten, sparsamer Akku
- `BALANCED` – Standard (Nordic + Apple + Eddystone Filter)
- `HIGH_ACCURACY` – Aggressiv + alle Service UUIDs + Classic Discovery
- `OFFLINE_TRACKING` – Nur Tokens + Asset-Tags, höchste Priorität

### Nutzung

```kotlin
val manager = BluetoothAccessoryManager(context, clientRegistry)
manager.startScan(ScanMode.HIGH_ACCURACY)

manager.accessories.collect { list ->
    val tokens = list.filter { it.type == TOKEN_PRO }
    val sensors = list.filter { it.type == SENSOR_TAG }
}

manager.events.collect { event ->
    when(event) {
        is SosTriggered -> handleSos(event.accessory)
        is ButtonPressed -> handleButton(event.accessory)
    }
}
```

Legacy `BleTokenManager` ist nun Wrapper um `BluetoothAccessoryManager` – bestehende `tokenUpdates` Flow funktioniert weiter.

---

## 🐍 Edge-Agent (Python)

### `bluetooth_accessories.py`

- `BluetoothAccessoryType` Enum – identisch Kotlin
- `BluetoothAccessory` Dataclass – from_dict(), to_dict(), distance estimation (log-distance path loss n=2)
- `BluetoothAccessoryRegistry` – globale Singleton `global_accessory_registry`
  - `update_or_create()`, `update_batch()`, `get()`, `get_by_type()`
  - Health: `evaluate_health()`, `evaluate_all_health()`, `get_critical()`
  - Callbacks: `on_sos()`, `on_button()`
  - Stats: `stats()` – total, by_type, low_battery, sos_active, lost

### REST Endpunkte (neu)

| Methode | Endpoint | Beschreibung |
|---------|----------|--------------|
| GET | `/api/v1/bluetooth/accessories?type=TOKEN_PRO` | Liste, optional gefiltert |
| GET | `/api/v1/bluetooth/accessories/{mac}` | Detail + Health |
| DELETE | `/api/v1/bluetooth/accessories/{mac}` | Entfernen |
| POST | `/api/v1/bluetooth/accessories/update` | Batch Update von CT45P |
| GET | `/api/v1/bluetooth/health` | Health Übersicht |
| GET | `/api/v1/bluetooth/stats` | Statistik |
| POST | `/api/v1/bluetooth/cleanup?max_age_s=60` | Expired löschen |
| GET | `/api/v1/health` | Erweitert um `bluetooth` Stats |

### WebSocket Events (neu)

- `bluetooth_accessories` – CT45P → Edge fwd → Visualizer (alle Geräte 5s)
- `bluetooth_accessories_update` – Edge → Visualizer
- `accessory_event` – SOS / Button / Fall
- `sensor_tag_update`, `wearable_update` – Einzel-Updates via MQTT
- Legacy `ble` → auch in Registry

### MQTT Bridge

Abonniert jetzt:
- `ble/tokens/#` (legacy)
- `bluetooth/accessories/#`
- `bluetooth/sensors/#`
- `bluetooth/wearables/#`
- `bluetooth/events/#`

Payloads: Einzel-Dict oder Liste oder `{"accessories": [...]}`

---

## 📲 Firmware (nRF52840 Zephyr)

### Struktur

```
ble-token-firmware/
├── Kconfig               # Choice Accessory Typ + Parameter
├── prj.conf              # Aktiviert BT Observer + Central + GATT + Sensoren
├── CMakeLists.txt        # modular sources
└── src/
    ├── main.c            # Universal Firmware, select via Kconfig
    ├── common/
    │   ├── battery.[ch]  # ADC LiPo / CR2032 Schätzung
    │   ├── button.[ch]   # SW0 Button + SOS Long 3s
    │   ├── advertising.[ch] # Manufacturer Data V2 + iBeacon + Eddystone
    │   └── gatt_custom.[ch] # Custom Service + BAS + DIS
    └── profiles/
        ├── token_profile   # BMI270 IMU
        ├── sensor_profile  # BME280/SHT4x
        ├── wearable_profile # HRM Mock + Steps
        ├── asset_tag_profile # iBeacon + Eddystone UID
        ├── remote_profile  # Button + SOS Flags
        └── gateway_profile # Observer Scanner
```

### Build

```bash
west build -b nrf52840dk_nrf52840 -- -DCONFIG_ACCESSORY_TYPE_SENSOR_TAG=y
west flash

# Weitere Typen:
west build -b nrf52840dk_nrf52840 -- -DCONFIG_ACCESSORY_TYPE_TOKEN=y
west build -b nrf52840dk_nrf52840 -- -DCONFIG_ACCESSORY_TYPE_WEARABLE=y
west build -b nrf52840dk_nrf52840 -- -DCONFIG_ACCESSORY_TYPE_ASSET_TAG=y
west build -b nrf52840dk_nrf52840 -- -DCONFIG_ACCESSORY_TYPE_REMOTE=y
```

### Adaptive Advertising

- Normal 200 ms
- Moving (Accel >1.2g) 100 ms
- SOS (Button Long) 50 ms – höchste Auffindbarkeit

### GATT

- Verbunden CT45P kann `gatt_custom_notify_data()` Live-Daten lesen
- Config Schreiben: JSON `{"adv_interval":100,"tx_power":-4}`
- Command: z.B. `0x01 DFU_START`, `0x02 SOS_CLEAR`

---

## 🌐 Web-Visualizer

Erweitert um Bluetooth Panel:

- Rechte Seite: Filter Buttons All/Tokens/Sensoren/Wearables/Assets/Remote
- Liste: MAC, RSSI, Distanz, Batterie, Flags (MOVING, SOS, LOW_BAT)
- Health Summary: 🟢/🟡/🔴/⚪
- 3D: Jedes Zubehör als farbcodiertes Mesh (Token grün, Sensor blau, Wearable pink, Asset gelb, Remote orange, Gateway cyan)
- SOS: rotes Blinken + Scenario Status Banner
- REST Polling Fallback alle 5s + WebSocket Live Updates

---

## 🔐 Sicherheit

- LE Secure Connections (Just Works + Passkey optional)
- API-Key per Gerät: `hash(MAC + Type + Salt)-ble`
- Bonding Manager: Auto-Bond für bekannte 3dxAgent Accessories (Manufacturer 0x0059)
- Flags: Tamper Detection (IMU Schock >4g), Fall (IMU Freifall), SOS
- JWT optional für höhere Sicherheit (mTLS im CT45P `offline/LocalApiServer`)

---

## 🧪 Test Szenarien

1. **Token Live**: 1x TOKEN_PRO + 1x ASSET_TAG → Distanzschätzung im Visualizer prüfen
2. **Sensor Netzwerk**: 3x SENSOR_TAG mit BME280 → Temperatur/Humidity im Panel + MQTT Topic `bluetooth/sensors/{mac}`
3. **Wearable SOS**: WEARABLE + Button Long 3s → SOS Event im WS + CT45P Log + Visualizer rotes Banner
4. **Remote Steuerung**: REMOTE_CONTROLLER Button → Szenario Start (Evakuierung) via CT45P
5. **Gateway**: GATEWAY nRF52840 scannt Tokens und leitet via UART JSON → Test mit MQTT Mosquitto
6. **Health**: Batterie <15% → Warning LOW_BATTERY, CRITICAL, WebSocket Badge

---

## 📋 Zukünftige Erweiterungen

- [ ] DFU OTA via GATT DFU Service 0x1530
- [ ] AoA/AoD Richtungserkennung (nRF52833 Antenna Array)
- [ ] UWB Token (IEEE 802.15.4z) für cm-genaue Ortung
- [ ] Mesh Networking (Zephyr BT Mesh)
- [ ] ML on Edge – Bewegungsklassifikation direkt auf nRF52 (TinyML)
