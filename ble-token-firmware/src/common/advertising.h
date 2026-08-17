#pragma once
#include <stdint.h>
#include <stdbool.h>

typedef struct {
    uint8_t protocol_version; // 1 legacy, 2 extended
    uint8_t accessory_type;   // 0=token, 1=sensor, 2=wearable, 3=asset, 4=remote, 5=gateway...
    int16_t accel_x, accel_y, accel_z; // /1000 g
    uint8_t battery_pct;
    int16_t temperature_c100; // /100 C
    uint8_t flags; // AccessoryFlags
    uint16_t extra; // je nach Typ: humidity, hr, button state...
    int16_t extra2;
} adv_payload_t;

int advertising_init(void);
int advertising_set_payload(const adv_payload_t *payload);
int advertising_set_ibeacon(const uint8_t uuid[16], uint16_t major, uint16_t minor, int8_t tx);
int advertising_set_eddystone_uid(const uint8_t ns[10], const uint8_t inst[6]);
int advertising_set_eddystone_url(const char *url);
int advertising_start(void);
int advertising_stop(void);
void advertising_set_interval(int interval_ms);
