#include "periodic_deadline.h"

#include <gtest/gtest.h>

namespace {

uint64_t g_now_ms;

uint64_t fake_clock() {
    return g_now_ms;
}

}  // namespace

TEST(PeriodicDeadlineTest, StartsWithFullIntervalRemaining) {
    g_now_ms = 1000;
    const PeriodicDeadline deadline(2000, fake_clock);

    EXPECT_EQ(deadline.remaining_ms(), 2000);
    EXPECT_FALSE(deadline.is_due());
}

TEST(PeriodicDeadlineTest, CountsDownToDeadline) {
    g_now_ms = 1000;
    const PeriodicDeadline deadline(2000, fake_clock);

    g_now_ms = 1750;
    EXPECT_EQ(deadline.remaining_ms(), 1250);
    g_now_ms = 2999;
    EXPECT_FALSE(deadline.is_due());
    EXPECT_EQ(deadline.remaining_ms(), 1);
}

TEST(PeriodicDeadlineTest, IsDueAtBoundary) {
    g_now_ms = 1000;
    const PeriodicDeadline deadline(2000, fake_clock);

    g_now_ms = 3000;
    EXPECT_TRUE(deadline.is_due());
    EXPECT_EQ(deadline.remaining_ms(), 0);
}

TEST(PeriodicDeadlineTest, OverdueDeadlineHasZeroRemaining) {
    g_now_ms = 1000;
    const PeriodicDeadline deadline(2000, fake_clock);

    g_now_ms = 4500;
    EXPECT_TRUE(deadline.is_due());
    EXPECT_EQ(deadline.remaining_ms(), 0);
}

TEST(PeriodicDeadlineTest, RestartSchedulesFromCurrentTime) {
    g_now_ms = 1000;
    PeriodicDeadline deadline(2000, fake_clock);

    g_now_ms = 4500;
    deadline.restart();

    EXPECT_EQ(deadline.remaining_ms(), 2000);
    g_now_ms = 6499;
    EXPECT_FALSE(deadline.is_due());
    g_now_ms = 6500;
    EXPECT_TRUE(deadline.is_due());
}
