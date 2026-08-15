#include <zephyr/drivers/sensor.h>
#include <zephyr/logging/log.h>
#include "token_profile.h"

LOG_MODULE_REGISTER(token_profile, LOG_LEVEL_INF);

static const struct device *imu_dev = DEVICE_DT_GET(DT_NODELABEL(bmi270));

int token_profile_init(void) {
    if (!device_is_ready(imu_dev)) {
        LOG_WRN("BMI270 not ready – Token wird simuliert");
        return 0;
    }
    LOG_INF("Token Profile – BMI270 OK");
    return 0;
}

void token_profile_update(adv_payload_t *payload) {
    if (!payload) return;
    payload->accessory_type = 0; // TOKEN
    payload->protocol_version = CONFIG_PROTOCOL_VERSION;

    if (device_is_ready(imu_dev)) {
        struct sensor_value accel[3];
        if (sensor_sample_fetch(imu_dev) == 0) {
            sensor_channel_get(imu_dev, SENSOR_CHAN_ACCEL_X, &accel[0]);
            sensor_channel_get(imu_dev, SENSOR_CHAN_ACCEL_Y, &accel[1]);
            sensor_channel_get(imu_dev, SENSOR_CHAN_ACCEL_Z, &accel[2]);
            payload->accel_x = (int16_t)(sensor_value_to_double(&accel[0]) * 1000);
            payload->accel_y = (int16_t)(sensor_value_to_double(&accel[1]) * 1000);
            payload->accel_z = (int16_t)(sensor_value_to_double(&accel[2]) * 1000);
        }
    }
}
