#pragma once
#include <stdbool.h>

typedef void (*button_cb_t)(int button_id, bool pressed, uint32_t duration_ms);

int button_init(button_cb_t cb);
bool button_is_pressed(void);
bool button_is_sos_pattern(void); // Long press 3s = SOS
void button_poll(void);
