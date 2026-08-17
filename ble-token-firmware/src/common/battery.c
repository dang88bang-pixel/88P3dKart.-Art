/**
 * Battery – LiPo / CR2032 Messung via ADC
 * Schätzt Prozent aus Spannung (einfache Kurve)
 */
#include <zephyr/kernel.h>
#include <zephyr/drivers/adc.h>
#include <zephyr/logging/log.h>
#include "battery.h"

LOG_MODULE_REGISTER(battery, LOG_LEVEL_INF);

static const struct device *adc_dev = DEVICE_DT_GET(DT_NODELABEL(adc));
static int cached_percent = 100;

int battery_init(void) {
    if (!device_is_ready(adc_dev)) {
        LOG_WRN("ADC nicht bereit – Batterie Dummy 100%%");
        return 0;
    }
    LOG_INF("Battery ADC init OK");
    return 0;
}

int battery_get_mv(void) {
    // Platzhalter – auf HW Board-Overlay ADC Channel definieren
    // Typische LiPo 3.0-4.2V, CR2032 2.0-3.0V
    return 3700; // Mock für Sandbox
}

int battery_get_percent(void) {
    int mv = battery_get_mv();
    int pct;
    // Einfache lineare Schätzung LiPo
    if (mv >= 4100) pct = 100;
    else if (mv <= 3200) pct = 0;
    else pct = (mv - 3200) * 100 / (4100 - 3200);
    cached_percent = pct;
    return pct;
}

bool battery_is_low(void) {
    return cached_percent < CONFIG_LOW_BATTERY_THRESHOLD;
}
