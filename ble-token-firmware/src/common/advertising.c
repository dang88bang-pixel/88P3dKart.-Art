/**
 * Advertising – 3dxAgent Custom Manufacturer Data + iBeacon + Eddystone
 * Verwaltet mehrere ADV Sets (Zephyr Extended Adv)
 */
#include <zephyr/kernel.h>
#include <zephyr/bluetooth/bluetooth.h>
#include <zephyr/bluetooth/hci.h>
#include <zephyr/logging/log.h>
#include "advertising.h"

LOG_MODULE_REGISTER(adv_mgr, LOG_LEVEL_INF);

#define COMPANY_ID 0x0059
#define APPLE_COMPANY_ID 0x004C

static struct bt_le_adv_param adv_param =
    BT_LE_ADV_PARAM_INIT(BT_LE_ADV_OPT_USE_IDENTITY | BT_LE_ADV_OPT_USE_NAME,
                         BT_GAP_ADV_FAST_INT_MIN_2, BT_GAP_ADV_FAST_INT_MAX_2, NULL);

static uint8_t mfg_data[26]; // Vergrößert für V2 extended
static struct bt_data ad_data[2];

static uint8_t ibeacon_data[25]; // 2+21+2+1
static struct bt_data ibeacon_ad[2];

static uint8_t eddystone_data[32];
static bool use_ibeacon = false;
static bool use_eddystone = false;
static int current_interval_ms = CONFIG_ADV_INTERVAL_MS;

int advertising_init(void) {
    LOG_INF("Advertising init – Company 0x%04X", COMPANY_ID);
    return 0;
}

void advertising_set_interval(int interval_ms) {
    current_interval_ms = interval_ms;
    // Zephyr kann Interval nur beim Start setzen, für simpel neu starten oder ignorieren
}

int advertising_set_payload(const adv_payload_t *p) {
    // Aufbau Manufacturer Data V2 extended:
    // [0..1] Company ID LE (Nordic 0x0059) – wird von Zephyr lib hinzugefügt oder manuell?
    // In Zephyr API BT_DATA_MANUFACTURER_DATA beinhaltet Company + Daten.
    // Wir packen: [0]=Company LSB, [1]=MSB, [2]=ver, [3]=type, [4..5]=ax, [6..7]=ay, [8..9]=az,
    // [10]=bat, [11..12]=temp, [13]=flags, [14]=extra LSB, [15]=extra MSB, [16..17]=extra2
    if (!p) return -1;

    mfg_data[0] = COMPANY_ID & 0xFF;
    mfg_data[1] = (COMPANY_ID >> 8) & 0xFF;
    mfg_data[2] = p->protocol_version;
    mfg_data[3] = p->accessory_type;
    mfg_data[4] = p->accel_x & 0xFF; mfg_data[5] = (p->accel_x >> 8) & 0xFF;
    mfg_data[6] = p->accel_y & 0xFF; mfg_data[7] = (p->accel_y >> 8) & 0xFF;
    mfg_data[8] = p->accel_z & 0xFF; mfg_data[9] = (p->accel_z >> 8) & 0xFF;
    mfg_data[10] = p->battery_pct;
    mfg_data[11] = p->temperature_c100 & 0xFF; mfg_data[12] = (p->temperature_c100 >> 8) & 0xFF;
    mfg_data[13] = p->flags;
    mfg_data[14] = p->extra & 0xFF; mfg_data[15] = (p->extra >> 8) & 0xFF;
    mfg_data[16] = p->extra2 & 0xFF; mfg_data[17] = (p->extra2 >> 8) & 0xFF;

    ad_data[0] = (struct bt_data)BT_DATA_BYTES(BT_DATA_FLAGS, (BT_LE_AD_GENERAL | BT_LE_AD_NO_BREDR));
    ad_data[1] = (struct bt_data)BT_DATA(BT_DATA_MANUFACTURER_DATA, mfg_data, 18);

    use_ibeacon = false;
    use_eddystone = false;

    LOG_DBG("Payload gesetzt type=%d bat=%d flags=0x%02X", p->accessory_type, p->battery_pct, p->flags);
    return 0;
}

int advertising_set_ibeacon(const uint8_t uuid[16], uint16_t major, uint16_t minor, int8_t tx) {
    // iBeacon: Manufacturer Data Apple [0x4C 0x00 0x02 0x15 UUID major minor tx]
    if (!uuid) return -1;
    ibeacon_data[0] = APPLE_COMPANY_ID & 0xFF;
    ibeacon_data[1] = (APPLE_COMPANY_ID >> 8) & 0xFF;
    ibeacon_data[2] = 0x02;
    ibeacon_data[3] = 0x15;
    memcpy(&ibeacon_data[4], uuid, 16);
    ibeacon_data[20] = (major >> 8) & 0xFF; ibeacon_data[21] = major & 0xFF;
    ibeacon_data[22] = (minor >> 8) & 0xFF; ibeacon_data[23] = minor & 0xFF;
    ibeacon_data[24] = (uint8_t)tx;

    ibeacon_ad[0] = (struct bt_data)BT_DATA_BYTES(BT_DATA_FLAGS, (BT_LE_AD_GENERAL | BT_LE_AD_NO_BREDR));
    ibeacon_ad[1] = (struct bt_data)BT_DATA(BT_DATA_MANUFACTURER_DATA, ibeacon_data, sizeof(ibeacon_data));

    use_ibeacon = true;
    use_eddystone = false;
    LOG_INF("iBeacon gesetzt %02X%02X... major=%d minor=%d", uuid[0], uuid[1], major, minor);
    return 0;
}

int advertising_set_eddystone_uid(const uint8_t ns[10], const uint8_t inst[6]) {
    if (!ns || !inst) return -1;
    // Service Data UUID 0xFEAA, Frame Type 0x00 UID
    eddystone_data[0] = 0x00; // UID frame
    eddystone_data[1] = -20; // Tx Power cal
    memcpy(&eddystone_data[2], ns, 10);
    memcpy(&eddystone_data[12], inst, 6);
    memset(&eddystone_data[18], 0, 2); // reserved RFU

    use_eddystone = true;
    use_ibeacon = false;
    LOG_INF("Eddystone UID gesetzt");
    return 0;
}

int advertising_set_eddystone_url(const char *url) {
    if (!url) return -1;
    size_t len = strlen(url);
    if (len > 17) len = 17;
    eddystone_data[0] = 0x10; // URL
    eddystone_data[1] = -20;
    eddystone_data[2] = 0x03; // https://
    memcpy(&eddystone_data[3], url, len);
    use_eddystone = true;
    use_ibeacon = false;
    return 0;
}

int advertising_start(void) {
    int err;
    if (use_ibeacon) {
        err = bt_le_adv_start(&adv_param, ibeacon_ad, ARRAY_SIZE(ibeacon_ad), NULL, 0);
    } else {
        err = bt_le_adv_start(&adv_param, ad_data, ARRAY_SIZE(ad_data), NULL, 0);
    }
    if (err) {
        LOG_ERR("Advertising start failed %d", err);
        return err;
    }
    LOG_INF("Advertising gestartet interval %d ms iBeacon=%d eddystone=%d", current_interval_ms, use_ibeacon, use_eddystone);
    return 0;
}

int advertising_stop(void) {
    int err = bt_le_adv_stop();
    if (err) LOG_WRN("Adv stop err %d", err);
    return err;
}
