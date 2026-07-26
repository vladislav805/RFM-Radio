#include "periodic_deadline.h"

PeriodicDeadline::PeriodicDeadline(uint64_t interval_ms, Clock clock)
        : clock_(clock), interval_ms_(interval_ms), next_ms_(clock() + interval_ms) {
}

int PeriodicDeadline::remaining_ms() const {
    const uint64_t now_ms = clock_();

    return next_ms_ > now_ms ? static_cast<int>(next_ms_ - now_ms) : 0;
}

bool PeriodicDeadline::is_due() const {
    return clock_() >= next_ms_;
}

void PeriodicDeadline::restart() {
    next_ms_ = clock_() + interval_ms_;
}
