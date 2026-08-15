cmake_minimum_required(VERSION 3.20.0)

find_package(Zephyr REQUIRED HINTS $ENV{ZEPHYR_BASE})
project(3dxagent_ble_accessories)

# ─── Quellen ─────────────────────────────────────────────────
target_sources(app PRIVATE
    src/main.c
    src/common/battery.c
    src/common/button.c
    src/common/advertising.c
    src/common/gatt_custom.c
    src/profiles/token_profile.c
    src/profiles/sensor_profile.c
    src/profiles/wearable_profile.c
    src/profiles/asset_tag_profile.c
    src/profiles/remote_profile.c
    src/profiles/gateway_profile.c
)

# Includes
target_include_directories(app PRIVATE
    src/common
    src/profiles
    src
)

zephyr_library_include_directories(src)
