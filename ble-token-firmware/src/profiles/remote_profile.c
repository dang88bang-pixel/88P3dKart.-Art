#include <zephyr/logging/log.h>
#include "remote_profile.h"
#include "common/button.h"

LOG_MODULE_REGISTER(remote_profile, LOG_LEVEL_INF);

static uint8_t last_button_state = 0;

int remote_profile_init(void) {
    LOG_INF("Remote Controller Profile init");
    return 0;
}

void remote_profile_update(adv_payload_t *payload) {
    if (!payload) return;
    payload->accessory_type = 5; // REMOTE_CONTROLLER

    bool pressed = button_is_pressed();
    bool sos = button_is_sos_pattern();

    uint8_t btn = 0;
    if (pressed) btn |= 0x01;
    if (sos) btn |= 0x80;

    if (btn != last_button_state) {
        LOG_INF("Remote Button state 0x%02X -> 0x%02X", last_button_state, btn);
        last_button_state = btn;
    }

    payload->extra = btn;
    payload->flags = 0;
    if (pressed) payload->flags |= (1 << 1); // BUTTON_PRESSED
    if (sos) payload->flags |= (1 << 7); // SOS
}
