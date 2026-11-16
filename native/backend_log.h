#pragma once

#include <stdio.h>
#include <stdarg.h>

static inline void backend_vlog(const char* prefix, const char* scope, const char* fmt, va_list args) {
    fprintf(stderr, "%s/%-7s: ", prefix, scope);
    vfprintf(stderr, fmt, args);
    fprintf(stderr, "\n");
}

static inline void backend_log(const char* prefix, const char* scope, const char* fmt, ...) {
    va_list args;
    va_start(args, fmt);
    backend_vlog(prefix, scope, fmt, args);
    va_end(args);
}
