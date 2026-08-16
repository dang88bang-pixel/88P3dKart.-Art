#include <zephyr/kernel.h>
#include <zephyr/drivers/gpio.h>
#include <zephyr/logging/log.h>
#include "button.h"

LOG_MODULE_REGISTER(button, LOG_LEVEL_INF);

static button_cb_t user_cb = NULL;
static int64_t press_start = 0;
static bool last_state = false;
static bool sos_active = false;

#define BUTTON_NODE DT_ALIAS(sw0)
#if DT_NODE_HAS_STATUS(BUTTON_NODE, okay)
static const struct gpio_dt_spec button_spec = GPIO_DT_SPEC_GET(BUTTON_NODE, gpios);
#else
static const struct gpio_dt_spec button_spec = {0};
#endif

int button_init(button_cb_t cb) {
    user_cb = cb;
    if (!DT_NODE_HAS_STATUS(BUTTON_NODE, okay)) {
        LOG_WRN("Kein Button im Devicetree – simuliere via Flags");
        return 0;
    }
    if (!device_is_ready(button_spec.port)) {
        LOG_ERR("Button GPIO nicht bereit");
        return -1;
    }
    gpio_pin_configure_dt(&button_spec, GPIO_INPUT);
    LOG_INF("Button init OK");
    return 0;
}

bool button_is_pressed(void) {
#if DT_NODE_HAS_STATUS(BUTTON_NODE, okay)
    return gpio_pin_get_dt(&button_spec) > 0;
#else
    return false;
#endif
}

bool button_is_sos_pattern(void) {
    return sos_active;
}

void button_poll(void) {
    bool cur = button_is_pressed();
    int64_t now = k_uptime_get();

    if (cur && !last_state) {
        press_start = now;
        LOG_DBG("Button pressed");
    } else if (!cur && last_state) {
        uint32_t dur = (uint32_t)(now - press_start);
        LOG_INF("Button released nach %u ms", dur);
        if (dur >= 3000) {
            sos_active = !sos_active;
            LOG_WRN("SOS Toggle: %s", sos_active ? "AKTIV" : "INAKTIV");
        }
        if (user_cb) user_cb(0, false, dur);
        if (dur >= 3000 && user_cb) user_cb(0, true, dur); // SOS als Spezial-Event
    }
    if (cur) {
        uint32_t held = (uint32_t)(now - press_start);
        if (held > 3000 && !sos_active) {
            // Early indicator
        }
    }
    last_state = cur;
}
