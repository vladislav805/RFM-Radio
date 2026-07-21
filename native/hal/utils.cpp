#include "utils.h"

#include <stdarg.h>
#include <stdio.h>
#include <string.h>

void hal_log(const char *scope, const char *fmt, ...) {
    printf("hal/%-7s: ", scope);

    va_list args;
    va_start(args, fmt);
    vprintf(fmt, args);
    va_end(args);

    printf("\n");
}

void format_hex_dump(const char *payload, int len, char *dump, size_t dump_size) {
    if (dump_size == 0) {
        return;
    }

    dump[0] = '\0';

    const int max_bytes_by_buffer = dump_size > 1 ? static_cast<int>((dump_size - 1) / 3) : 0;
    const int dump_len = len > max_bytes_by_buffer ? max_bytes_by_buffer : len;

    for (int i = 0; i < dump_len; ++i) {
        char chunk[4];
        snprintf(chunk, sizeof(chunk), "%s%02x", i == 0 ? "" : " ", static_cast<unsigned char>(payload[i]));
        strncat(dump, chunk, dump_size - strlen(dump) - 1);
    }
}
