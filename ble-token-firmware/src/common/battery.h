#pragma once
#include <stdint.h>

int battery_init(void);
int battery_get_percent(void);
int battery_get_mv(void);
bool battery_is_low(void);
