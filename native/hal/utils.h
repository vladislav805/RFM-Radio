#pragma once

#include <stddef.h>

void hal_log(const char *scope, const char *fmt, ...);
void format_hex_dump(const char *payload, int len, char *dump, size_t dump_size);
