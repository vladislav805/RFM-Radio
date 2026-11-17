#include <gtest/gtest.h>

#include "backend/v4l2_base/v4l2_backend_base.h"

TEST(V4l2BackendBaseTest, ConvertsKhzToV4l2FrequencyUnits) {
    // V4L2 tuner frequency unit is 62.5 Hz when V4L2_TUNER_CAP_LOW is set.
    // Multiplier is 16000 (1000000 Hz / 62.5 Hz).
    EXPECT_EQ(V4l2BackendBase::khz_to_freq(87500), 1400000u);
    EXPECT_EQ(V4l2BackendBase::khz_to_freq(108000), 1728000u);
    EXPECT_EQ(V4l2BackendBase::khz_to_freq(76000), 1216000u);
}

TEST(V4l2BackendBaseTest, ConvertsV4l2FrequencyUnitsToKhz) {
    EXPECT_EQ(V4l2BackendBase::freq_to_khz(1400000), 87500u);
    EXPECT_EQ(V4l2BackendBase::freq_to_khz(1728000), 108000u);
    EXPECT_EQ(V4l2BackendBase::freq_to_khz(1216000), 76000u);
}

TEST(V4l2BackendBaseTest, PerformsFrequencyConversionRoundtrip) {
    constexpr uint32_t sample_frequencies[] = {76000, 87500, 95000, 100000, 108000};
    for (uint32_t khz : sample_frequencies) {
        const uint32_t v4l2_units = V4l2BackendBase::khz_to_freq(khz);
        EXPECT_EQ(V4l2BackendBase::freq_to_khz(v4l2_units), khz);
    }
}
