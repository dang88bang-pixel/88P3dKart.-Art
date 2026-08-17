# 📡 3dxAgent Bluetooth-Zubehör Firmware – nRF52840

Universal-Firmware für alle Bluetooth-Zubehörtypen im 3dxAgent Ökosystem.

## Zubehörtypen (Kconfig)

| Typ | Kconfig | Sensorik | Flags | Nutzung |
|-----|---------|----------|-------|---------|
| Token | `CONFIG_ACCESSORY_TYPE_TOKEN=y` | BMI270 IMU | MOVING, SOS, LOW_BAT | Personen-Tracking |
| Sensor Tag | `CONFIG_ACCESSORY_TYPE_SENSOR_TAG=y` | BME280/SHT4x | Temp/Feuchte/Druck | Umwelt |
| Wearable | `CONFIG_ACCESSORY_TYPE_WEARABLE=y` | HRM Mock + Steps + IMU | HR, FALL | Vitaldaten |
| Asset Tag | `CONFIG_ACCESSORY_TYPE_ASSET_TAG=y` | iBeacon + Eddystone | - | Asset Tracking |
| Remote | `CONFIG_ACCESSORY_TYPE_REMOTE=y` | Button + Joystick | BUTTON, SOS | Steuerung |
| Gateway | `CONFIG_ACCESSORY_TYPE_GATEWAY=y` | Observer Scanner | - | BLE→MQTT Bridge |

## Protokoll V2

Manufacturer Data 0x0059 – 18 Bytes:

```
[0..1] Company ID 0x0059 LE
[2] Ver (2)
[3] Type (0 Token, 2 Sensor, 3 Wearable, 4 Asset, 5 Remote, 6 Gateway)
[4..9] Accel XYZ int16 /1000
[10] Battery %
[11..12] Temp int16 /100
[13] Flags: bit0 MOVING, bit1 BUTTON, bit2 LOW_BAT, bit7 SOS
[14..15] Extra: humidity / HR / button
[16..17] Extra2: pressure*10 / steps
```

Legacy V1 (9 Byte) wird weiterhin vom CT45P Parser unterstützt.

## Build

```bash
west init -m https://github.com/zephyrproject-rtos/zephyr --mr v3.5.0
west update

cd 88P3dKart.-Art/ble-token-firmware

# Token Pro (Standard)
west build -b nrf52840dk_nrf52840

# Sensor Tag
west build -b nrf52840dk_nrf52840 -- -DCONFIG_ACCESSORY_TYPE_SENSOR_TAG=y -DCONFIG_ACCESSORY_TYPE_TOKEN=n

# Wearable
west build -b nrf52840dk_nrf52840 -- -DCONFIG_ACCESSORY_TYPE_WEARABLE=y -DCONFIG_ACCESSORY_TYPE_TOKEN=n

# Asset Tag (iBeacon)
west build -b nrf52840dk_nrf52840 -- -DCONFIG_ACCESSORY_TYPE_ASSET_TAG=y -DCONFIG_ACCESSORY_TYPE_TOKEN=n

# Remote
west build -b nrf52840dk_nrf52840 -- -DCONFIG_ACCESSORY_TYPE_REMOTE=y -DCONFIG_ACCESSORY_TYPE_TOKEN=n

west flash
west build -b nrf52840dk_nrf52840 -t rtt
```

## GATT Custom Service

- UUID `8d81e7c0-b7c8-4b26-b0ea-e8b10bc7f1e0`
- Data Notify `...e7c1` – Live Accel/Temp/Humidity
- Config Write `...e7c2` – JSON Config
- Command Write `...e7c3` – Binary Commands
- Battery Service `0x180F`, Device Info `0x180A`

## Button

- Short Press: FLAG BUTTON kurz setzen
- Long 3s: SOS Toggle (FLAG_SOS), im CT45P Log + WebSocket SOS Event

## Adaptive Advertising

- Normal 200 ms, Moving 100 ms, SOS 50 ms
- `advertising_set_interval()` steuert dynamisch

## Module

- `common/battery` – ADC LiPo Schätzung
- `common/button` – GPIO SW0 + SOS Pattern
- `common/advertising` – Manufacturer Data V2 + iBeacon + Eddystone
- `common/gatt_custom` – GATT Custom + BAS + DIS
- `profiles/*` – Typ-spezifische Logik
