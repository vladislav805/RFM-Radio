#include <gtest/gtest.h>

#include "region_profile.h"

namespace {

TEST(RegionProfileTest, DefinesEuropeProfile) {
    const RegionProfile &profile = get_region_profile(StartupRegion::kEurope);

    EXPECT_EQ(profile.lower_frequency_khz, 87500u);
    EXPECT_EQ(profile.upper_frequency_khz, 108000u);
    EXPECT_EQ(profile.emphasis, kFmEmphasis50Microseconds);
    EXPECT_EQ(profile.rds_standard, kFmRdsStandardRds);
    EXPECT_EQ(profile.legacy_band, 1);
    EXPECT_EQ(profile.helium_region, 1);
}

TEST(RegionProfileTest, DefinesUnitedStatesProfile) {
    const RegionProfile &profile = get_region_profile(StartupRegion::kUnitedStates);

    EXPECT_EQ(profile.lower_frequency_khz, 87500u);
    EXPECT_EQ(profile.upper_frequency_khz, 108000u);
    EXPECT_EQ(profile.emphasis, kFmEmphasis75Microseconds);
    EXPECT_EQ(profile.rds_standard, kFmRdsStandardRbds);
    EXPECT_EQ(profile.legacy_band, 1);
    EXPECT_EQ(profile.helium_region, 0);
}

TEST(RegionProfileTest, DefinesJapanProfile) {
    const RegionProfile &profile = get_region_profile(StartupRegion::kJapan);

    EXPECT_EQ(profile.lower_frequency_khz, 76000u);
    EXPECT_EQ(profile.upper_frequency_khz, 95000u);
    EXPECT_EQ(profile.emphasis, kFmEmphasis50Microseconds);
    EXPECT_EQ(profile.rds_standard, kFmRdsStandardRds);
    EXPECT_EQ(profile.legacy_band, 2);
    EXPECT_EQ(profile.helium_region, 2);
}

TEST(RegionProfileTest, DefinesJapanWideProfile) {
    const RegionProfile &profile = get_region_profile(StartupRegion::kJapanWide);

    EXPECT_EQ(profile.lower_frequency_khz, 76000u);
    EXPECT_EQ(profile.upper_frequency_khz, 108000u);
    EXPECT_EQ(profile.emphasis, kFmEmphasis50Microseconds);
    EXPECT_EQ(profile.rds_standard, kFmRdsStandardRds);
    EXPECT_EQ(profile.legacy_band, 3);
    EXPECT_EQ(profile.helium_region, 3);
}

TEST(RegionProfileTest, ReturnsProtocolNames) {
    EXPECT_STREQ(get_region_name(StartupRegion::kEurope), "eu");
    EXPECT_STREQ(get_region_name(StartupRegion::kUnitedStates), "us");
    EXPECT_STREQ(get_region_name(StartupRegion::kJapan), "jp");
    EXPECT_STREQ(get_region_name(StartupRegion::kJapanWide), "jp_wide");
}

TEST(RegionProfileTest, ClampsFrequencyToRegionBand) {
    const RegionProfile &profile = get_region_profile(StartupRegion::kJapan);

    EXPECT_EQ(clamp_frequency_to_region(profile, 75000), 76000u);
    EXPECT_EQ(clamp_frequency_to_region(profile, 87500), 87500u);
    EXPECT_EQ(clamp_frequency_to_region(profile, 106300), 95000u);
}

}  // namespace
