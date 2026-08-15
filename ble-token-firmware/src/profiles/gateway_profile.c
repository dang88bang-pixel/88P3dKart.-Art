#include <zephyr/kernel.h>
#include <zephyr/bluetooth/bluetooth.h>
#include <zephyr/bluetooth/hci.h>
#include <zephyr/logging/log.h>
#include "gateway_profile.h"

LOG_MODULE_REGISTER(gateway_profile, LOG_LEVEL_INF);

// Gateway: Scannt im Hintergrund nach Tokens/Sensoren und leitet via UART/MQTT weiter
// Für nRF52840 als Observer + Peripheral gleichzeitig (Multi-Role)

int gateway_profile_init(void) {
    LOG_INF("Gateway Profile – Observer Rolle aktiviert");
    return 0;
}

void gateway_profile_loop(void) {
    // In echter Implementierung:
    // - bt_le_scan_start mit Filter auf Company ID 0x0059
    // - Decodiere Tokens und packe in JSON über UART
    // - Oder BLE→WiFi Bridge mit ESP-AT

    k_sleep(K_MSEC(1000));
}
