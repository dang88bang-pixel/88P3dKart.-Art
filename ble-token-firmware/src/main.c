/*
 * 3dxAgent BLE Zubehör Firmware – Universal Firmware für nRF52840
 *
 * Unterstützt via Kconfig:
 * - Token Classic/Pro (BMI270 IMU + Batterie + Temperatur + Flags)
 * - Sensor Tag (BME280 / SHT4x – Temp/Feuchte/Druck/Luft)
 * - Wearable (HRM Mock + Steps)
 * - Asset Tag (iBeacon + Eddystone UID)
 * - Remote Controller (Button + SOS Long-Press)
 * - Gateway Bridge (Observer Scanner)
 *
 * Protokoll V2 (erweitert):
 * Manufacturer Data Layout (Company ID 0x0059):
 * [0..1] Company ID LE (0x0059)
 * [2]    Protocol Version (1 legacy, 2 extended)
 * [3]    Accessory Type (0=Token, 2=Sensor, 3=Wearable, 4=Asset, 5=Remote, 6=Gateway)
 * [4..5] ACCEL_X int16 /1000
 * [6..7] ACCEL_Y int16 /1000
 * [8..9] ACCEL_Z int16 /1000
 * [10]   Battery %
 * [11..12] Temperature int16 /100 C
 * [13]   Flags (bit0 MOVING, bit1 BUTTON, bit2 LOW_BAT, bit7 SOS)
 * [14..15] Extra – je nach Typ: humidity / HR / button_state
 * [16..17] Extra2 – pressure / steps / joystick
 *
 * Legacy V1 wird für Kompatibilität weiterhin vom CT45P-Parser unterstützt.
 *
 * Zusatz-Features:
 * - Adaptive Advertising Rate (200ms normal, 100ms moving, 50ms SOS)
 * - Battery Service + Device Info + Custom 3dx Service (Data/Config/Command)
 * - Button: Short Press = Button Flag, Long 3s = SOS Toggle
 * - Low Battery Flag automatisch
 * - GATT Notifications für Live-Daten
 */

#include <zephyr/kernel.h>
#include <zephyr/bluetooth/bluetooth.h>
#include <zephyr/drivers/sensor.h>
#include <zephyr/logging/log.h>

#include "common/battery.h"
#include "common/button.h"
#include "common/advertising.h"
#include "common/gatt_custom.h"

#include "profiles/token_profile.h"
#include "profiles/sensor_profile.h"
#include "profiles/wearable_profile.h"
#include "profiles/asset_tag_profile.h"
#include "profiles/remote_profile.h"
#include "profiles/gateway_profile.h"

LOG_MODULE_REGISTER(main, LOG_LEVEL_INF);

#define FLAG_MOVING      (1 << 0)
#define FLAG_BUTTON      (1 << 1)
#define FLAG_LOW_BAT     (1 << 2)
#define FLAG_TAMPER      (1 << 3)
#define FLAG_SOS         (1 << 7)

static adv_payload_t adv_payload = {
    .protocol_version = CONFIG_PROTOCOL_VERSION,
    .accessory_type = 0,
    .battery_pct = 100,
    .temperature_c100 = 2200,
    .flags = 0,
};

static const char *type_to_str(int type) {
    switch (type) {
        case 0: return "TOKEN";
        case 1: return "TOKEN_PRO";
        case 2: return "SENSOR_TAG";
        case 3: return "WEARABLE";
        case 4: return "ASSET_TAG";
        case 5: return "REMOTE";
        case 6: return "GATEWAY";
        default: return "UNKNOWN";
    }
}

static void button_event_cb(int button_id, bool pressed, uint32_t dur_ms) {
    LOG_INF("Button %d pressed=%d dur=%u", button_id, pressed, dur_ms);
    if (dur_ms >= 3000) {
        if (adv_payload.flags & FLAG_SOS) {
            adv_payload.flags &= ~FLAG_SOS;
            LOG_WRN("SOS DEAKTIVIERT via Button");
        } else {
            adv_payload.flags |= FLAG_SOS;
            LOG_WRN("SOS AKTIVIERT via Button – Long Press!");
        }
    } else {
        adv_payload.flags |= FLAG_BUTTON;
        k_sleep(K_MSEC(300));
        adv_payload.flags &= ~FLAG_BUTTON;
    }
}

void main(void) {
    int err;

    LOG_INF("=== 3dxAgent BLE Zubehör Firmware v2.0 ===");
    LOG_INF("Zubehör-Typ Kconfig: TOKEN=%d SENSOR=%d WEARABLE=%d ASSET=%d REMOTE=%d GATEWAY=%d",
            IS_ENABLED(CONFIG_ACCESSORY_TYPE_TOKEN),
            IS_ENABLED(CONFIG_ACCESSORY_TYPE_SENSOR_TAG),
            IS_ENABLED(CONFIG_ACCESSORY_TYPE_WEARABLE),
            IS_ENABLED(CONFIG_ACCESSORY_TYPE_ASSET_TAG),
            IS_ENABLED(CONFIG_ACCESSORY_TYPE_REMOTE),
            IS_ENABLED(CONFIG_ACCESSORY_TYPE_GATEWAY));

    err = bt_enable(NULL);
    if (err) {
        LOG_ERR("Bluetooth init failed %d", err);
        return;
    }
    LOG_INF("Bluetooth init OK");

    battery_init();
    advertising_init();
    gatt_custom_init();
    button_init(button_event_cb);

    // Profil-Init basierend auf Kconfig
#if defined(CONFIG_ACCESSORY_TYPE_TOKEN)
    token_profile_init();
    adv_payload.accessory_type = 0;
    LOG_INF("Profil: TOKEN");
#elif defined(CONFIG_ACCESSORY_TYPE_SENSOR_TAG)
    sensor_profile_init();
    adv_payload.accessory_type = 2;
    LOG_INF("Profil: SENSOR_TAG");
#elif defined(CONFIG_ACCESSORY_TYPE_WEARABLE)
    wearable_profile_init();
    adv_payload.accessory_type = 3;
    LOG_INF("Profil: WEARABLE");
#elif defined(CONFIG_ACCESSORY_TYPE_ASSET_TAG)
    asset_tag_profile_init();
    adv_payload.accessory_type = 4;
    LOG_INF("Profil: ASSET_TAG – starte iBeacon");
    asset_tag_profile_start_ibeacon();
    k_sleep(K_MSEC(100));
    // Für Asset Tag wechseln wir gleich auf iBeacon Advertising
    advertising_start();
    while (1) {
        button_poll();
        k_sleep(K_MSEC(200));
    }
#elif defined(CONFIG_ACCESSORY_TYPE_REMOTE)
    remote_profile_init();
    adv_payload.accessory_type = 5;
    LOG_INF("Profil: REMOTE");
#elif defined(CONFIG_ACCESSORY_TYPE_GATEWAY)
    gateway_profile_init();
    adv_payload.accessory_type = 6;
    LOG_INF("Profil: GATEWAY");
#else
    token_profile_init();
    adv_payload.accessory_type = 0;
    LOG_INF("Profil: TOKEN (default)");
#endif

    advertising_start();

    int iteration = 0;
    while (1) {
        // Batterie
        int bat = battery_get_percent();
        adv_payload.battery_pct = (uint8_t)bat;
        gatt_custom_set_battery((uint8_t)bat);

        if (battery_is_low()) {
            adv_payload.flags |= FLAG_LOW_BAT;
        } else {
            adv_payload.flags &= ~FLAG_LOW_BAT;
        }

        // Button Polling
        button_poll();
        if (button_is_sos_pattern()) {
            adv_payload.flags |= FLAG_SOS;
        }

        // Profil-spezifische Updates
#if defined(CONFIG_ACCESSORY_TYPE_TOKEN)
        token_profile_update(&adv_payload);
        // Bewegungsflag aus Accel Magnitude
        int abs_acc = abs(adv_payload.accel_x) + abs(adv_payload.accel_y) + abs(adv_payload.accel_z);
        if (abs_acc > 1200) { // >1.2g Bewegung
            adv_payload.flags |= FLAG_MOVING;
        } else {
            adv_payload.flags &= ~FLAG_MOVING;
        }
#elif defined(CONFIG_ACCESSORY_TYPE_SENSOR_TAG)
        sensor_profile_update(&adv_payload);
#elif defined(CONFIG_ACCESSORY_TYPE_WEARABLE)
        wearable_profile_update(&adv_payload);
#elif defined(CONFIG_ACCESSORY_TYPE_REMOTE)
        remote_profile_update(&adv_payload);
#elif defined(CONFIG_ACCESSORY_TYPE_GATEWAY)
        // Gateway hat eigenes Loop – hier kein Advertising Payload Update
        gateway_profile_loop();
#endif

        // Adaptive Advertising Rate
        int interval = CONFIG_ADV_INTERVAL_MS;
        if (adv_payload.flags & FLAG_SOS) {
            interval = CONFIG_ADV_INTERVAL_SOS_MS;
        } else if (adv_payload.flags & FLAG_MOVING) {
            interval = CONFIG_ADV_INTERVAL_MOVING_MS;
        }

#if !defined(CONFIG_ACCESSORY_TYPE_ASSET_TAG) && !defined(CONFIG_ACCESSORY_TYPE_GATEWAY)
        advertising_stop();
        advertising_set_payload(&adv_payload);
        advertising_set_interval(interval);
        advertising_start();

        // GATT Notify für verbundene CT45P Geräte
        uint8_t notify_data[18];
        notify_data[0] = 0x01; // accel
        memcpy(&notify_data[1], &adv_payload.accel_x, 2);
        memcpy(&notify_data[3], &adv_payload.accel_y, 2);
        memcpy(&notify_data[5], &adv_payload.accel_z, 2);
        gatt_custom_notify_data(notify_data, 7);

        if (iteration % 10 == 0) {
            LOG_INF("ADV %s ver=%d bat=%d%% flags=0x%02X ax=%d ay=%d az=%d temp=%d.%02d interval=%d",
                    type_to_str(adv_payload.accessory_type),
                    adv_payload.protocol_version,
                    adv_payload.battery_pct,
                    adv_payload.flags,
                    adv_payload.accel_x, adv_payload.accel_y, adv_payload.accel_z,
                    adv_payload.temperature_c100 / 100, adv_payload.temperature_c100 % 100,
                    interval);
        }
#endif

        k_sleep(K_MSEC(interval));
        iteration++;
    }
}
