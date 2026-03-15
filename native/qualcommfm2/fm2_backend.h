#pragma once

#include <stdbool.h>
#include <stdint.h>

#include "common.h"

bool fm2_backend_init();
bool fm2_backend_enable();
bool fm2_backend_disable();
bool fm2_backend_wait_enabled(int timeout_ms);
bool fm2_backend_set_frequency(uint32_t frequency_khz);
uint32_t fm2_backend_get_frequency();
bool fm2_backend_jump(int direction, uint32_t *new_frequency);
bool fm2_backend_seek(int direction);
bool fm2_backend_search();
bool fm2_backend_cancel_search();
bool fm2_backend_set_rds(bool enabled);
bool fm2_backend_set_stereo(bool enabled);
bool fm2_backend_set_spacing_app_value(int app_spacing);
bool fm2_backend_set_region_app_value(int app_region);
bool fm2_backend_set_antenna(int antenna);
bool fm2_backend_set_power_mode(bool low_power);
bool fm2_backend_set_auto_af(bool enabled);
bool fm2_backend_set_slimbus(bool enabled);
bool fm2_backend_raw_set(int id, int value);
bool fm2_backend_raw_get(int id, int *value);
bool fm2_backend_log_snapshot(const char *reason);
const char *fm2_backend_last_error();
