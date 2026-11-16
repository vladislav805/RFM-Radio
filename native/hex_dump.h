#include "backend_log.h"
#pragma once

#include <stddef.h>

void format_hex_dump(const char *payload, int len, char *dump, size_t dump_size);
