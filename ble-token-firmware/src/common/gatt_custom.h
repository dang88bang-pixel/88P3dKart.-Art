#pragma once
#include <stdint.h>
#include <zephyr/bluetooth/gatt.h>

int gatt_custom_init(void);
int gatt_custom_notify_data(const uint8_t *data, uint16_t len);
void gatt_custom_set_battery(uint8_t level);
void gatt_custom_set_firmware_rev(const char *rev);
