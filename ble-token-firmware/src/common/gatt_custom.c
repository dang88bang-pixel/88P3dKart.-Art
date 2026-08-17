/**
 * Custom 3dxAgent GATT Service – UUID 8d81e7c0-b7c8-4b26-b0ea-e8b10bc7f1e0
 * Chars: Data (Notify), Config (Write), Command (Write)
 * Zusätzlich: Environmental Sensing und Battery spoofen für Standard Clients
 */
#include <zephyr/bluetooth/gatt.h>
#include <zephyr/logging/log.h>
#include <string.h>
#include "gatt_custom.h"

LOG_MODULE_REGISTER(gatt_custom, LOG_LEVEL_INF);

static uint8_t battery_level = 100;
static char fw_rev_str[24] = "2.0.0-BT-Accessories";

static uint8_t custom_data_value[64] = {0};
static uint8_t custom_config_value[128] = {0};
static uint8_t custom_cmd_value[32] = {0};

static ssize_t read_battery(struct bt_conn *conn, const struct bt_gatt_attr *attr,
                            void *buf, uint16_t len, uint16_t offset) {
    return bt_gatt_attr_read(conn, attr, buf, len, offset, &battery_level, sizeof(battery_level));
}

static ssize_t read_fw_rev(struct bt_conn *conn, const struct bt_gatt_attr *attr,
                           void *buf, uint16_t len, uint16_t offset) {
    return bt_gatt_attr_read(conn, attr, buf, len, offset, fw_rev_str, strlen(fw_rev_str));
}

static ssize_t write_config(struct bt_conn *conn, const struct bt_gatt_attr *attr,
                            const void *buf, uint16_t len, uint16_t offset, uint8_t flags) {
    if (len > sizeof(custom_config_value)) return BT_GATT_ERR(BT_ATT_ERR_INVALID_ATTRIBUTE_LEN);
    memcpy(custom_config_value, buf, len);
    LOG_INF("Config geschrieben %d bytes: %.*s", len, len, (char*)buf);
    return len;
}

static ssize_t write_command(struct bt_conn *conn, const struct bt_gatt_attr *attr,
                             const void *buf, uint16_t len, uint16_t offset, uint8_t flags) {
    if (len > sizeof(custom_cmd_value)) return BT_GATT_ERR(BT_ATT_ERR_INVALID_ATTRIBUTE_LEN);
    memcpy(custom_cmd_value, buf, len);
    LOG_INF("Command empfangen %d bytes", len);
    return len;
}

static void data_ccc_cfg_changed(const struct bt_gatt_attr *attr, uint16_t value) {
    LOG_INF("Data notify %s", value == BT_GATT_CCC_NOTIFY ? "enabled" : "disabled");
}

// UUIDs
static struct bt_uuid_128 custom_svc_uuid = BT_UUID_INIT_128(
    0xe0, 0xf1, 0xc7, 0x0b, 0xb1, 0xe8, 0xea, 0xb0,
    0x26, 0x4b, 0xc8, 0xb7, 0xc0, 0xe7, 0x81, 0x8d);

static struct bt_uuid_128 data_char_uuid = BT_UUID_INIT_128(
    0xe1, 0xf1, 0xc7, 0x0b, 0xb1, 0xe8, 0xea, 0xb0,
    0x26, 0x4b, 0xc8, 0xb7, 0xc1, 0xe7, 0x81, 0x8d);

static struct bt_uuid_128 config_char_uuid = BT_UUID_INIT_128(
    0xe2, 0xf1, 0xc7, 0x0b, 0xb1, 0xe8, 0xea, 0xb0,
    0x26, 0x4b, 0xc8, 0xb7, 0xc2, 0xe7, 0x81, 0x8d);

static struct bt_uuid_128 cmd_char_uuid = BT_UUID_INIT_128(
    0xe3, 0xf1, 0xc7, 0x0b, 0xb1, 0xe8, 0xea, 0xb0,
    0x26, 0x4b, 0xc8, 0xb7, 0xc3, 0xe7, 0x81, 0x8d);

BT_GATT_SERVICE_DEFINE(custom_svc,
    BT_GATT_PRIMARY_SERVICE(&custom_svc_uuid),
    BT_GATT_CHARACTERISTIC(&data_char_uuid.uuid,
                           BT_GATT_CHRC_READ | BT_GATT_CHRC_NOTIFY,
                           BT_GATT_PERM_READ, NULL, NULL, custom_data_value),
    BT_GATT_CCC(data_ccc_cfg_changed, BT_GATT_PERM_READ | BT_GATT_PERM_WRITE),
    BT_GATT_CHARACTERISTIC(&config_char_uuid.uuid,
                           BT_GATT_CHRC_WRITE | BT_GATT_CHRC_READ,
                           BT_GATT_PERM_WRITE | BT_GATT_PERM_READ,
                           NULL, write_config, custom_config_value),
    BT_GATT_CHARACTERISTIC(&cmd_char_uuid.uuid,
                           BT_GATT_CHRC_WRITE,
                           BT_GATT_PERM_WRITE,
                           NULL, write_command, custom_cmd_value),
);

static struct bt_gatt_service bas_svc = BT_GATT_SERVICE({
    BT_GATT_PRIMARY_SERVICE(BT_UUID_BAS),
    BT_GATT_CHARACTERISTIC(BT_UUID_BAS_BATTERY_LEVEL,
                           BT_GATT_CHRC_READ | BT_GATT_CHRC_NOTIFY,
                           BT_GATT_PERM_READ,
                           read_battery, NULL, &battery_level),
    BT_GATT_CCC(NULL, BT_GATT_PERM_READ | BT_GATT_PERM_WRITE),
});

static struct bt_gatt_service dis_svc = BT_GATT_SERVICE({
    BT_GATT_PRIMARY_SERVICE(BT_UUID_DIS),
    BT_GATT_CHARACTERISTIC(BT_UUID_DIS_FIRMWARE_REVISION,
                           BT_GATT_CHRC_READ,
                           BT_GATT_PERM_READ,
                           read_fw_rev, NULL, fw_rev_str),
});

int gatt_custom_init(void) {
    bt_gatt_service_register(&bas_svc);
    bt_gatt_service_register(&dis_svc);
    LOG_INF("GATT Custom + BAS + DIS registriert");
    return 0;
}

void gatt_custom_set_battery(uint8_t level) {
    battery_level = level;
    bt_gatt_notify(NULL, &bas_svc.attrs[1], &battery_level, sizeof(battery_level));
}

void gatt_custom_set_firmware_rev(const char *rev) {
    if (!rev) return;
    strncpy(fw_rev_str, rev, sizeof(fw_rev_str)-1);
}

int gatt_custom_notify_data(const uint8_t *data, uint16_t len) {
    if (!data || len == 0) return -1;
    if (len > sizeof(custom_data_value)) len = sizeof(custom_data_value);
    memcpy(custom_data_value, data, len);
    return bt_gatt_notify(NULL, &custom_svc.attrs[1], data, len);
}
