#pragma once

#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <time.h>

#include "../types.h"

static inline const char *fm2_short_file(const char *path) {
    const char *slash = strrchr(path, '/');
    return slash ? slash + 1 : path;
}

#define FM2_LOG_BASE(level, fmt, ...)                                                      \
    do {                                                                                   \
        struct timespec __ts;                                                              \
        clock_gettime(CLOCK_REALTIME, &__ts);                                              \
        fprintf(stderr, "[fm2][%s][%lld.%03lld] %s:%d " fmt "\n",                          \
                level,                                                                     \
                (long long)__ts.tv_sec,                                                    \
                (long long)(__ts.tv_nsec / 1000000),                                       \
                fm2_short_file(__FILE__),                                                  \
                __LINE__,                                                                  \
                ##__VA_ARGS__);                                                            \
        fflush(stderr);                                                                    \
    } while (0)

#define FM2_LOGI(fmt, ...) FM2_LOG_BASE("I", fmt, ##__VA_ARGS__)
#define FM2_LOGW(fmt, ...) FM2_LOG_BASE("W", fmt, ##__VA_ARGS__)
#define FM2_LOGE(fmt, ...) FM2_LOG_BASE("E", fmt, ##__VA_ARGS__)

#define FM2_PERROR(prefix) FM2_LOGE("%s: errno=%d (%s)", prefix, errno, strerror(errno))
