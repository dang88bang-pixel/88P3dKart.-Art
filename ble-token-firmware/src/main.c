/*
 * 3dxAgent BLE token (nRF52840 + BMI270)
 *
 * Versioned manufacturer data, little-endian:
 *   0..1   Bluetooth company ID (0x0059; removed by Android ScanRecord)
 *   2      protocol version (1)
 *   3      flags: bit 0 battery valid, bit 1 IMU valid
 *   4..5   sequence (uint16)
 *   6..7   acceleration X (int16, milli-m/s²)
 *   8..9   acceleration Y (int16, milli-m/s²)
 *   10..11 acceleration Z (int16, milli-m/s²)
 *   12     battery percent, or 0xFF when unavailable
 *
 * Battery telemetry is explicitly unavailable until a board overlay defines a
 * calibrated battery-divider ADC channel. The firmware never advertises a
 * fabricated percentage.
 */
#include <limits.h>
#include <stdint.h>

#include <zephyr/bluetooth/bluetooth.h>
#include <zephyr/bluetooth/hci.h>
#include <zephyr/drivers/sensor.h>
#include <zephyr/kernel.h>
#include <zephyr/logging/log.h>
#include <zephyr/sys/util.h>

LOG_MODULE_REGISTER(ble_token, LOG_LEVEL_INF);

#define COMPANY_ID 0x0059
#define PROTOCOL_VERSION 1
#define FLAG_BATTERY_VALID BIT(0)
#define FLAG_IMU_VALID BIT(1)
#define BATTERY_UNKNOWN 0xFF
#define ADV_UPDATE_INTERVAL K_MSEC(200)

struct __packed token_payload {
    uint8_t company_id_le[2];
    uint8_t version;
    uint8_t flags;
    uint8_t sequence_le[2];
    uint8_t accel_x_le[2];
    uint8_t accel_y_le[2];
    uint8_t accel_z_le[2];
    uint8_t battery_percent;
};

BUILD_ASSERT(sizeof(struct token_payload) == 13,
             "token manufacturer payload has changed");

static struct bt_le_adv_param adv_param =
    BT_LE_ADV_PARAM_INIT(BT_LE_ADV_OPT_USE_IDENTITY,
                         BT_GAP_ADV_FAST_INT_MIN_2,
                         BT_GAP_ADV_FAST_INT_MAX_2,
                         NULL);

#if DT_NODE_EXISTS(DT_NODELABEL(bmi270))
static const struct device *imu = DEVICE_DT_GET(DT_NODELABEL(bmi270));
#else
static const struct device *imu;
#endif

static void put_u16_le(uint8_t destination[2], uint16_t value)
{
    destination[0] = (uint8_t)(value & 0xff);
    destination[1] = (uint8_t)(value >> 8);
}

static int16_t acceleration_milli_ms2(const struct sensor_value *value)
{
    double scaled = sensor_value_to_double(value) * 1000.0;

    if (scaled > INT16_MAX) {
        return INT16_MAX;
    }
    if (scaled < INT16_MIN) {
        return INT16_MIN;
    }
    return (int16_t)scaled;
}

static bool read_acceleration(struct token_payload *payload)
{
    struct sensor_value accel[3];

    if (imu == NULL || !device_is_ready(imu) || sensor_sample_fetch(imu) != 0 ||
        sensor_channel_get(imu, SENSOR_CHAN_ACCEL_X, &accel[0]) != 0 ||
        sensor_channel_get(imu, SENSOR_CHAN_ACCEL_Y, &accel[1]) != 0 ||
        sensor_channel_get(imu, SENSOR_CHAN_ACCEL_Z, &accel[2]) != 0) {
        return false;
    }

    put_u16_le(payload->accel_x_le,
               (uint16_t)acceleration_milli_ms2(&accel[0]));
    put_u16_le(payload->accel_y_le,
               (uint16_t)acceleration_milli_ms2(&accel[1]));
    put_u16_le(payload->accel_z_le,
               (uint16_t)acceleration_milli_ms2(&accel[2]));
    return true;
}

int main(void)
{
    int err;
    uint16_t sequence = 0;
    struct token_payload payload = {
        .company_id_le = { COMPANY_ID & 0xff, COMPANY_ID >> 8 },
        .version = PROTOCOL_VERSION,
        .battery_percent = BATTERY_UNKNOWN,
    };
    struct bt_data advertising_data[] = {
        BT_DATA(BT_DATA_MANUFACTURER_DATA, &payload, sizeof(payload)),
    };

    err = bt_enable(NULL);
    if (err != 0) {
        LOG_ERR("Bluetooth init failed (err %d)", err);
        return err;
    }

    if (imu == NULL || !device_is_ready(imu)) {
        LOG_WRN("BMI270 unavailable; IMU-valid flag will remain clear");
    }

    /* Start once. Subsequent samples update the active advertising set. */
    err = bt_le_adv_start(&adv_param, advertising_data,
                          ARRAY_SIZE(advertising_data), NULL, 0);
    if (err != 0) {
        LOG_ERR("Advertising start failed (err %d)", err);
        return err;
    }

    LOG_INF("BLE token advertising protocol v%d", PROTOCOL_VERSION);

    while (true) {
        payload.flags = 0;
        put_u16_le(payload.sequence_le, sequence++);
        put_u16_le(payload.accel_x_le, 0);
        put_u16_le(payload.accel_y_le, 0);
        put_u16_le(payload.accel_z_le, 0);
        payload.battery_percent = BATTERY_UNKNOWN;

        if (read_acceleration(&payload)) {
            payload.flags |= FLAG_IMU_VALID;
        }

        err = bt_le_adv_update_data(advertising_data,
                                    ARRAY_SIZE(advertising_data), NULL, 0);
        if (err != 0) {
            LOG_WRN("Advertising update failed (err %d)", err);
        }

        k_sleep(ADV_UPDATE_INTERVAL);
    }

    return 0;
}
