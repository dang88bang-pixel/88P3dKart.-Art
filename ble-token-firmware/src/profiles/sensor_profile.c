#include <zephyr/drivers/sensor.h>
#include <zephyr/logging/log.h>
#include "sensor_profile.h"

LOG_MODULE_REGISTER(sensor_profile, LOG_LEVEL_INF);

static const struct device *bme_dev = DEVICE_DT_GET(DT_NODELABEL(bme280));

int sensor_profile_init(void) {
    if (!device_is_ready(bme_dev)) {
        LOG_WRN("BME280 nicht bereit – nutze Mock Daten");
        return 0;
    }
    LOG_INF("Sensor Profile – BME280 OK");
    return 0;
}

void sensor_profile_update(adv_payload_t *payload) {
    if (!payload) return;
    payload->accessory_type = 2; // SENSOR_TAG
    payload->protocol_version = 2;

    int16_t temp_c100 = 2235; // 22.35C Mock
    uint16_t hum_pct = 55;
    uint16_t press_hpa10 = 10132; // 1013.2 hPa *10

    if (device_is_ready(bme_dev)) {
        struct sensor_value temp, press, hum;
        if (sensor_sample_fetch(bme_dev) == 0) {
            sensor_channel_get(bme_dev, SENSOR_CHAN_AMBIENT_TEMP, &temp);
            sensor_channel_get(bme_dev, SENSOR_CHAN_PRESS, &press);
            // BME280 has humidity via SENSOR_CHAN_HUMIDITY
            if (sensor_channel_get(bme_dev, SENSOR_CHAN_HUMIDITY, &hum) == 0) {
                hum_pct = (uint16_t)sensor_value_to_double(&hum);
            }
            temp_c100 = (int16_t)(sensor_value_to_double(&temp) * 100);
            press_hpa10 = (uint16_t)(sensor_value_to_double(&press) * 10);
        }
    }

    payload->temperature_c100 = temp_c100;
    payload->extra = hum_pct; // humidity
    payload->extra2 = (int16_t)press_hpa10;
}
