#include <zephyr/logging/log.h>
#include <zephyr/kernel.h>
#include "wearable_profile.h"

LOG_MODULE_REGISTER(wearable_profile, LOG_LEVEL_INF);

static uint8_t simulated_hr = 72;
static uint16_t simulated_steps = 1243;
static int64_t last_step_inc = 0;

int wearable_profile_init(void) {
    last_step_inc = k_uptime_get();
    LOG_INF("Wearable Profile – HRM Mock init");
    return 0;
}

void wearable_profile_update(adv_payload_t *payload) {
    if (!payload) return;
    payload->accessory_type = 3; // WEARABLE

    // Simuliere leichte HR Variation
    int64_t now = k_uptime_get();
    if ((now - last_step_inc) > 1000) {
        simulated_steps += 1 + (now % 3);
        simulated_hr = 68 + (now % 25); // 68-93
        last_step_inc = now;
    }

    payload->extra = simulated_hr; // HR in extra byte
    payload->extra2 = (int16_t)simulated_steps;

    // Temperatur als Hauttemperatur
    payload->temperature_c100 = 3650; // 36.5C
}
