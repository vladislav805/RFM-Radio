#include "scan_result.h"

#include <gtest/gtest.h>

TEST(ScanResultCollectorTest, AddsUniqueFrequenciesAndSortsResult) {
    ScanResultCollector collector;
    EXPECT_EQ(collector.add(101700), ScanAddResult::kAdded);
    EXPECT_EQ(collector.add(87950), ScanAddResult::kAdded);
    EXPECT_EQ(collector.add(101700), ScanAddResult::kDuplicate);

    ScanResult result;
    collector.copy_sorted(&result);

    ASSERT_EQ(result.count, 2);
    EXPECT_EQ(result.frequencies_khz[0], 87950);
    EXPECT_EQ(result.frequencies_khz[1], 101700);
}

TEST(ScanResultCollectorTest, RejectsInvalidFrequencies) {
    ScanResultCollector collector;
    EXPECT_EQ(collector.add(0), ScanAddResult::kInvalid);
    EXPECT_EQ(collector.add(-1), ScanAddResult::kInvalid);

    ScanResult result;
    collector.copy_sorted(&result);
    EXPECT_EQ(result.count, 0);
}

TEST(ScanResultCollectorTest, LimitsResultToUdpSafeCapacity) {
    ScanResultCollector collector;
    for (int i = 0; i < kMaxScanStations; ++i) {
        EXPECT_EQ(collector.add(76000 + i * 50), ScanAddResult::kAdded);
    }
    EXPECT_EQ(collector.add(108000), ScanAddResult::kFull);

    ScanResult result;
    collector.copy_sorted(&result);
    EXPECT_EQ(result.count, kMaxScanStations);
}

TEST(ScanResultCollectorTest, ResetClearsResult) {
    ScanResultCollector collector;
    ASSERT_EQ(collector.add(101700), ScanAddResult::kAdded);
    collector.reset();

    ScanResult result;
    collector.copy_sorted(&result);
    EXPECT_EQ(result.count, 0);
}
