/*
 * 3dxAgent BLE-Token (nRF52840 + BMI270)
 *
 * Sendet Beschleunigungsdaten + Batteriestand als Manufacturer Data
 * (Company ID 0x0059) via BLE-Advertising. Der CT45P-Scanner extrahiert
 * daraus Bewegung (IMU) und RSSI für die Triangulation.
 *
 * Manufacturer-Data-Layout (9+ Bytes):
 *   [0..1] Company ID (0x0059, LE)
 *   [2..3] ACCEL_X (int16, /1000)
 *   [4..5] ACCEL_Y (int16, /1000)
 *   [6..7] ACCEL_Z (int16, /1000)
 *   [8]    Battery (%)
 */
#include <zephyr/kernel.h>
#include <zephyr/bluetooth/bluetooth.h>
#include <zephyr/bluetooth/hci.h>
#include <zephyr/drivers/sensor.h>
#include <zephyr/logging/log.h>

LOG_MODULE_REGISTER(ble_token, LOG_LEVEL_INF);

#define COMPANY_ID 0x0059

static struct bt_le_adv_param adv_param =
    BT_LE_ADV_PARAM_INIT(BT_LE_ADV_OPT_USE_IDENTITY,
                         BT_GAP_ADV_FAST_INT_MIN_2,
                         BT_GAP_ADV_FAST_INT_MAX_2,
                         NULL);

static const struct device *imu = DEVICE_DT_GET(DT_NODELABEL(bmi270));

void main(void)
{
    int err = bt_enable(NULL);
    if (err) {
        LOG_ERR("Bluetooth init failed (err %d)", err);
        return;
    }

    if (!device_is_ready(imu)) {
        LOG_ERR("BMI270 not ready");
    }

    LOG_INF("BLE-Token gestartet");

    while (1) {
        uint8_t mfg_data[9] = {
            COMPANY_ID & 0xFF, (COMPANY_ID >> 8) & 0xFF,
            0, 0, 0, 0, 0, 0,
            100, /* Batterie (Platzhalter) */
        };

        struct sensor_value accel[3];
        if (device_is_ready(imu) && sensor_sample_fetch(imu) == 0) {
            sensor_channel_get(imu, SENSOR_CHAN_ACCEL_X, &accel[0]);
            sensor_channel_get(imu, SENSOR_CHAN_ACCEL_Y, &accel[1]);
            sensor_channel_get(imu, SENSOR_CHAN_ACCEL_Z, &accel[2]);
            int16_t ax = (int16_t)(sensor_value_to_double(&accel[0]) * 1000);
            int16_t ay = (int16_t)(sensor_value_to_double(&accel[1]) * 1000);
            int16_t az = (int16_t)(sensor_value_to_double(&accel[2]) * 1000);
            mfg_data[2] = ax & 0xFF; mfg_data[3] = (ax >> 8) & 0xFF;
            mfg_data[4] = ay & 0xFF; mfg_data[5] = (ay >> 8) & 0xFF;
            mfg_data[6] = az & 0xFF; mfg_data[7] = (az >> 8) & 0xFF;
        }

        struct bt_data ad[] = {
            BT_DATA(BT_DATA_MANUFACTURER_DATA, mfg_data, sizeof(mfg_data)),
        };

        err = bt_le_adv_start(&adv_param, ad, ARRAY_SIZE(ad), NULL, 0);
        if (err) {
            LOG_ERR("Advertising failed (err %d)", err);
        }

        k_sleep(K_MSEC(200)); /* adaptive Advertising-Rate */
    }
}
