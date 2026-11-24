#include "backend/v4l2_qcom/v4l2_scan_state.h"

#include <gtest/gtest.h>

TEST(V4l2ScanStateTest, CollectsConfirmedStationsAndSortsResult) {
    V4l2ScanState state;
    ASSERT_TRUE(state.start());

    state.on_tune(101700);
    EXPECT_EQ(state.on_scan_next(101800), 101700);
    EXPECT_FALSE(state.on_tune(87950));
    EXPECT_EQ(state.on_scan_next(88000), 87950);

    ScanResult result;
    EXPECT_EQ(state.on_seek_complete(&result), V4l2ScanTerminal::kCompleted);
    ASSERT_EQ(result.count, 2);
    EXPECT_EQ(result.frequencies_khz[0], 87950);
    EXPECT_EQ(result.frequencies_khz[1], 101700);
}

TEST(V4l2ScanStateTest, FallsBackToFrequencyReadAtScanNext) {
    V4l2ScanState state;
    ASSERT_TRUE(state.start());

    EXPECT_EQ(state.on_scan_next(90600), 90600);

    ScanResult result;
    ASSERT_EQ(state.on_seek_complete(&result), V4l2ScanTerminal::kCompleted);
    ASSERT_EQ(result.count, 1);
    EXPECT_EQ(result.frequencies_khz[0], 90600);
}

TEST(V4l2ScanStateTest, DeduplicatesStations) {
    V4l2ScanState state;
    ASSERT_TRUE(state.start());

    state.on_tune(101700);
    state.on_scan_next(101700);
    state.on_tune(101700);
    state.on_scan_next(101700);

    ScanResult result;
    ASSERT_EQ(state.on_seek_complete(&result), V4l2ScanTerminal::kCompleted);
    EXPECT_EQ(result.count, 1);
}

TEST(V4l2ScanStateTest, CompletesEmptyScanOnlyOnce) {
    V4l2ScanState state;
    ASSERT_TRUE(state.start());

    ScanResult result;
    EXPECT_EQ(state.on_seek_complete(&result), V4l2ScanTerminal::kCompleted);
    EXPECT_EQ(result.count, 0);
    EXPECT_EQ(state.on_seek_complete(&result), V4l2ScanTerminal::kNone);
}

TEST(V4l2ScanStateTest, CancelSuppressesCompletion) {
    V4l2ScanState state;
    ASSERT_TRUE(state.start());
    state.on_tune(101700);
    state.on_scan_next(101700);
    ASSERT_TRUE(state.begin_cancel());

    ScanResult result;
    EXPECT_EQ(state.on_seek_complete(&result), V4l2ScanTerminal::kCancelled);
    EXPECT_EQ(state.on_seek_complete(&result), V4l2ScanTerminal::kNone);
    EXPECT_EQ(result.count, 0);
}

TEST(V4l2ScanStateTest, FailedCancelContinuesScan) {
    V4l2ScanState state;
    ASSERT_TRUE(state.start());
    ASSERT_TRUE(state.begin_cancel());
    state.cancel_failed();
    state.on_tune(96400);
    state.on_scan_next(96400);

    ScanResult result;
    ASSERT_EQ(state.on_seek_complete(&result), V4l2ScanTerminal::kCompleted);
    ASSERT_EQ(result.count, 1);
    EXPECT_EQ(result.frequencies_khz[0], 96400);
}

TEST(V4l2ScanStateTest, LimitsResultToProtocolCapacity) {
    V4l2ScanState state;
    ASSERT_TRUE(state.start());

    for (int i = 0; i < kMaxScanStations + 5; ++i) {
        state.on_tune(76000 + i * 50);
        state.on_scan_next(0);
    }

    ScanResult result;
    ASSERT_EQ(state.on_seek_complete(&result), V4l2ScanTerminal::kCompleted);
    EXPECT_EQ(result.count, kMaxScanStations);
}

TEST(V4l2ScanStateTest, RejectsOverlappingScans) {
    V4l2ScanState state;
    ASSERT_TRUE(state.start());
    EXPECT_FALSE(state.start());

    state.start_failed();
    EXPECT_TRUE(state.start());
}

TEST(V4l2ScanStateTest, SeekBlocksScanUntilTuneCompletesIt) {
    V4l2ScanState state;
    ASSERT_TRUE(state.start_seek());
    EXPECT_TRUE(state.busy());
    EXPECT_FALSE(state.start());

    ScanResult result;
    EXPECT_EQ(state.on_seek_complete(&result), V4l2ScanTerminal::kNone);
    EXPECT_TRUE(state.busy());

    EXPECT_TRUE(state.on_tune(101700));
    EXPECT_FALSE(state.on_tune(101700));
    EXPECT_FALSE(state.busy());
    EXPECT_TRUE(state.start());
}

TEST(V4l2ScanStateTest, FailedSeekReleasesOperation) {
    V4l2ScanState state;
    ASSERT_TRUE(state.start_seek());
    state.seek_failed();

    EXPECT_FALSE(state.busy());
    EXPECT_TRUE(state.start());
}

TEST(V4l2ScanStateTest, DelayedSetfreqTuneBeforeOrAfterSeekStartDoesNotCompleteSeek) {
    V4l2ScanState state;

    // 1. Delayed tune arrives while seek ioctl is still starting (begin_seek -> on_tune -> start_seek).
    state.on_tune_requested();
    ASSERT_TRUE(state.begin_seek());
    EXPECT_TRUE(state.busy());
    EXPECT_FALSE(state.on_tune(101700));
    EXPECT_TRUE(state.busy());
    ASSERT_TRUE(state.start_seek());
    EXPECT_FALSE(state.start());

    // Real seek completes at the same frequency.
    EXPECT_TRUE(state.on_tune(101700));
    EXPECT_FALSE(state.busy());

    // 2. Delayed tune from previous setfreq arrives after start_seek() has already committed.
    state.on_tune_requested();
    ASSERT_TRUE(state.begin_seek());
    ASSERT_TRUE(state.start_seek());
    EXPECT_FALSE(state.on_tune(101700));
    EXPECT_TRUE(state.busy());
    EXPECT_FALSE(state.start());

    // Real seek completes at the same frequency and unblocks scan.
    EXPECT_TRUE(state.on_tune(101700));
    EXPECT_FALSE(state.busy());
    EXPECT_TRUE(state.start());
}

TEST(V4l2ScanStateTest, SeekCompleteClearsUnconsumedPendingSetfreqTune) {
    V4l2ScanState state;
    state.on_tune_requested();
    ASSERT_TRUE(state.begin_seek());
    ASSERT_TRUE(state.start_seek());

    ScanResult result;
    EXPECT_EQ(state.on_seek_complete(&result), V4l2ScanTerminal::kNone);
    EXPECT_TRUE(state.busy());

    // Even if the driver coalesced the earlier setfreq tune event, the tune event after
    // SEEK_COMPLETE belongs to the hardware seek and completes it.
    EXPECT_TRUE(state.on_tune(101700));
    EXPECT_FALSE(state.busy());
    EXPECT_TRUE(state.start());
}

TEST(V4l2ScanStateTest, EarlySeekTuneBeforeStartSeekCompletesOnCommit) {
    V4l2ScanState state;
    ASSERT_TRUE(state.begin_seek());
    EXPECT_TRUE(state.busy());

    // TUNE_SUCC arrives before start_seek() promotes kSeekStarting -> kSeeking.
    EXPECT_FALSE(state.on_tune(101700));
    EXPECT_TRUE(state.busy());

    ScanResult result;
    EXPECT_EQ(state.on_seek_complete(&result), V4l2ScanTerminal::kNone);

    bool completed = false;
    ASSERT_TRUE(state.start_seek(&completed));
    EXPECT_TRUE(completed);
    EXPECT_FALSE(state.busy());
    EXPECT_TRUE(state.start());
}

TEST(V4l2ScanStateTest, EarlyTuneDoesNotEmitCompletionWhenSeekIoctlFails) {
    V4l2ScanState state;
    ASSERT_TRUE(state.begin_seek());
    EXPECT_FALSE(state.on_tune(101700));

    state.seek_failed();
    EXPECT_FALSE(state.busy());

    // Subsequent seek must not inherit stale completion state.
    ASSERT_TRUE(state.begin_seek());
    bool completed = false;
    ASSERT_TRUE(state.start_seek(&completed));
    EXPECT_FALSE(completed);
    EXPECT_TRUE(state.busy());
}

TEST(V4l2ScanStateTest, EarlySeekCompleteClearsPendingManualTuneBeforeEarlySeekTune) {
    V4l2ScanState state;
    state.on_tune_requested();
    ASSERT_TRUE(state.begin_seek());

    ScanResult result;
    EXPECT_EQ(state.on_seek_complete(&result), V4l2ScanTerminal::kNone);
    EXPECT_FALSE(state.on_tune(101700));

    bool completed = false;
    ASSERT_TRUE(state.start_seek(&completed));
    EXPECT_TRUE(completed);
    EXPECT_FALSE(state.busy());
}
