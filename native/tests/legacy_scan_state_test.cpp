#include "legacy/scan_state.h"

#include <gtest/gtest.h>

TEST(LegacyScanStateTest, CollectsConfirmedStationsAndSortsResult) {
    LegacyScanState state;
    ASSERT_TRUE(state.start());

    state.on_tune(101700);
    EXPECT_EQ(state.on_scan_next(101800), 101700);
    state.on_tune(87950);
    EXPECT_EQ(state.on_scan_next(88000), 87950);

    LegacyScanResult result;
    EXPECT_EQ(state.on_seek_complete(&result), LegacyScanTerminal::kCompleted);
    ASSERT_EQ(result.count, 2);
    EXPECT_EQ(result.frequencies_khz[0], 87950);
    EXPECT_EQ(result.frequencies_khz[1], 101700);
}

TEST(LegacyScanStateTest, FallsBackToFrequencyReadAtScanNext) {
    LegacyScanState state;
    ASSERT_TRUE(state.start());

    EXPECT_EQ(state.on_scan_next(90600), 90600);

    LegacyScanResult result;
    ASSERT_EQ(state.on_seek_complete(&result), LegacyScanTerminal::kCompleted);
    ASSERT_EQ(result.count, 1);
    EXPECT_EQ(result.frequencies_khz[0], 90600);
}

TEST(LegacyScanStateTest, DeduplicatesStations) {
    LegacyScanState state;
    ASSERT_TRUE(state.start());

    state.on_tune(101700);
    state.on_scan_next(101700);
    state.on_tune(101700);
    state.on_scan_next(101700);

    LegacyScanResult result;
    ASSERT_EQ(state.on_seek_complete(&result), LegacyScanTerminal::kCompleted);
    EXPECT_EQ(result.count, 1);
}

TEST(LegacyScanStateTest, CompletesEmptyScanOnlyOnce) {
    LegacyScanState state;
    ASSERT_TRUE(state.start());

    LegacyScanResult result;
    EXPECT_EQ(state.on_seek_complete(&result), LegacyScanTerminal::kCompleted);
    EXPECT_EQ(result.count, 0);
    EXPECT_EQ(state.on_seek_complete(&result), LegacyScanTerminal::kNone);
}

TEST(LegacyScanStateTest, CancelSuppressesCompletion) {
    LegacyScanState state;
    ASSERT_TRUE(state.start());
    state.on_tune(101700);
    state.on_scan_next(101700);
    ASSERT_TRUE(state.begin_cancel());

    LegacyScanResult result;
    EXPECT_EQ(state.on_seek_complete(&result), LegacyScanTerminal::kCancelled);
    EXPECT_EQ(state.on_seek_complete(&result), LegacyScanTerminal::kNone);
    EXPECT_EQ(result.count, 0);
}

TEST(LegacyScanStateTest, FailedCancelContinuesScan) {
    LegacyScanState state;
    ASSERT_TRUE(state.start());
    ASSERT_TRUE(state.begin_cancel());
    state.cancel_failed();
    state.on_tune(96400);
    state.on_scan_next(96400);

    LegacyScanResult result;
    ASSERT_EQ(state.on_seek_complete(&result), LegacyScanTerminal::kCompleted);
    ASSERT_EQ(result.count, 1);
    EXPECT_EQ(result.frequencies_khz[0], 96400);
}

TEST(LegacyScanStateTest, LimitsResultToUdpSafeCapacity) {
    LegacyScanState state;
    ASSERT_TRUE(state.start());

    for (int i = 0; i < kLegacyMaxScanStations + 5; ++i) {
        state.on_tune(76000 + i * 50);
        state.on_scan_next(0);
    }

    LegacyScanResult result;
    ASSERT_EQ(state.on_seek_complete(&result), LegacyScanTerminal::kCompleted);
    EXPECT_EQ(result.count, kLegacyMaxScanStations);
}

TEST(LegacyScanStateTest, RejectsOverlappingScans) {
    LegacyScanState state;
    ASSERT_TRUE(state.start());
    EXPECT_FALSE(state.start());

    state.start_failed();
    EXPECT_TRUE(state.start());
}

TEST(LegacyScanStateTest, SeekBlocksScanUntilTuneCompletesIt) {
    LegacyScanState state;
    ASSERT_TRUE(state.start_seek());
    EXPECT_TRUE(state.busy());
    EXPECT_FALSE(state.start());

    LegacyScanResult result;
    EXPECT_EQ(state.on_seek_complete(&result), LegacyScanTerminal::kNone);
    EXPECT_TRUE(state.busy());

    state.on_tune(101700);
    EXPECT_FALSE(state.busy());
    EXPECT_TRUE(state.start());
}

TEST(LegacyScanStateTest, FailedSeekReleasesOperation) {
    LegacyScanState state;
    ASSERT_TRUE(state.start_seek());
    state.seek_failed();

    EXPECT_FALSE(state.busy());
    EXPECT_TRUE(state.start());
}
