#include <zephyr/logging/log.h>
#include <string.h>
#include "asset_tag_profile.h"
#include "advertising.h"

LOG_MODULE_REGISTER(asset_tag, LOG_LEVEL_INF);

// Beispiel UUID für 3dxAgent Asset Tracking – kann via Config überschrieben werden
static const uint8_t default_uuid[16] = {
    0x8d, 0x81, 0xe7, 0xc0, 0xb7, 0xc8, 0x4b, 0x26,
    0xb0, 0xea, 0xe8, 0xb1, 0x0b, 0xc7, 0xf1, 0xe0
};

static const uint8_t example_namespace[10] = {0x01,0x02,0x03,0x04,0x05,0x06,0x07,0x08,0x09,0x0A};
static const uint8_t example_instance[6]  = {0xAA,0xBB,0xCC,0xDD,0xEE,0xFF};

int asset_tag_profile_init(void) {
    LOG_INF("Asset Tag Profile init");
    return 0;
}

int asset_tag_profile_start_ibeacon(void) {
    // Major = Asset-Kategorie, Minor = Asset-ID
    return advertising_set_ibeacon(default_uuid, 0x0001, 0x00A5, -59);
}

int asset_tag_profile_start_eddystone(void) {
    return advertising_set_eddystone_uid(example_namespace, example_instance);
}
