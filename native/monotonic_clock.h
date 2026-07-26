#pragma once

#include <stdint.h>
#include <time.h>

// Returns elapsed monotonic milliseconds for interval and deadline calculations.
// This is not a wall-clock timestamp and is unaffected by system time corrections.
inline uint64_t monotonic_ms() {
    struct timespec ts;

    clock_gettime(CLOCK_MONOTONIC, &ts);

    return static_cast<uint64_t>(ts.tv_sec) * 1000ULL +
            static_cast<uint64_t>(ts.tv_nsec / 1000000ULL);
}
