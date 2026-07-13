#include "utils.h"

#include <stdarg.h>
#include <stdio.h>

void hal_log(const char *scope, const char *fmt, ...) {
    printf("hal/%-10s: ", scope);

    va_list args;
    va_start(args, fmt);
    vprintf(fmt, args);
    va_end(args);

    printf("\n");
}
