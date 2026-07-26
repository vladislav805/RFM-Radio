#pragma once

#include <stdint.h>

// Tracks a repeating deadline using an injected millisecond clock. Production
// uses monotonic_ms(); tests provide a deterministic fake clock.
class PeriodicDeadline {
public:
    using Clock = uint64_t (*)();

    // Schedules the first deadline one interval after the current clock value.
    PeriodicDeadline(uint64_t interval_ms, Clock clock);

    // Returns a poll()-compatible timeout, or zero when already due.
    int remaining_ms() const;

    // Includes the exact deadline boundary.
    bool is_due() const;

    // Starts a fresh interval from the current clock value instead of issuing
    // catch-up callbacks.
    void restart();

private:
    Clock clock_;
    uint64_t interval_ms_;
    uint64_t next_ms_;
};
