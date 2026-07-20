#include <gtest/gtest.h>

#include "startup_config.h"

namespace {

TEST(StartupConfigTest, ParsesNamedParametersInAnyOrder) {
    const std::vector<std::string> args = {
            "enable", "af=1", "spacing=50", "freq=106300",
            "soft_mute=0", "antenna=0", "region=eu", "stereo=1",
    };
    StartupConfig config;
    std::string error;

    ASSERT_TRUE(parse_startup_config(args, &config, &error)) << error;
    EXPECT_EQ(config.frequency_khz, 106300u);
    EXPECT_EQ(config.region, StartupRegion::kEurope);
    EXPECT_EQ(config.spacing_khz, 50);
    EXPECT_TRUE(config.stereo);
    EXPECT_FALSE(config.soft_mute);
    EXPECT_EQ(config.antenna, 0);
    EXPECT_TRUE(config.auto_af);
}

TEST(StartupConfigTest, ParsesAllRegionsAndSpacingValues) {
    const std::vector<std::string> united_states = {
            "enable", "freq=87500", "region=us", "spacing=200",
            "stereo=1", "soft_mute=1", "antenna=0", "af=0",
    };
    const std::vector<std::string> japan = {
            "enable", "freq=76000", "region=jp", "spacing=100",
            "stereo=0", "soft_mute=0", "antenna=1", "af=0",
    };
    const std::vector<std::string> japan_wide = {
            "enable", "freq=108000", "region=jp_wide", "spacing=200",
            "stereo=1", "soft_mute=1", "antenna=2", "af=1",
    };
    StartupConfig config;

    ASSERT_TRUE(parse_startup_config(united_states, &config));
    EXPECT_EQ(config.region, StartupRegion::kUnitedStates);

    ASSERT_TRUE(parse_startup_config(japan, &config));
    EXPECT_EQ(config.region, StartupRegion::kJapan);
    EXPECT_EQ(config.spacing_khz, 100);
    EXPECT_FALSE(config.stereo);
    EXPECT_FALSE(config.soft_mute);
    EXPECT_EQ(config.antenna, 1);
    EXPECT_FALSE(config.auto_af);

    ASSERT_TRUE(parse_startup_config(japan_wide, &config));
    EXPECT_EQ(config.region, StartupRegion::kJapanWide);
    EXPECT_EQ(config.spacing_khz, 200);
}

TEST(StartupConfigTest, RejectsMissingParameter) {
    const std::vector<std::string> args = {
            "enable", "freq=106300", "region=eu", "spacing=100",
            "stereo=1", "soft_mute=1", "antenna=0",
    };
    StartupConfig config;

    EXPECT_FALSE(parse_startup_config(args, &config));
}

TEST(StartupConfigTest, RejectsUnknownParameter) {
    const std::vector<std::string> args = {
            "enable", "freq=106300", "region=eu", "spacing=100",
            "stereo=1", "soft_mute=1", "antenna=0", "rds=1",
    };
    StartupConfig config;

    EXPECT_FALSE(parse_startup_config(args, &config));
}

TEST(StartupConfigTest, RejectsDuplicateParameter) {
    const std::vector<std::string> args = {
            "enable", "freq=106300", "region=eu", "spacing=100",
            "stereo=1", "soft_mute=1", "antenna=0", "freq=87500",
    };
    StartupConfig config;

    EXPECT_FALSE(parse_startup_config(args, &config));
}

TEST(StartupConfigTest, RejectsMalformedAndOutOfRangeValues) {
    const std::vector<std::vector<std::string>> invalid = {
            {"enable", "freq=no", "region=eu", "spacing=100", "stereo=1", "soft_mute=1", "antenna=0", "af=0"},
            {"enable", "freq=75000", "region=eu", "spacing=100", "stereo=1", "soft_mute=1", "antenna=0", "af=0"},
            {"enable", "freq=106300", "region=other", "spacing=100", "stereo=1", "soft_mute=1", "antenna=0", "af=0"},
            {"enable", "freq=106300", "region=eu", "spacing=1", "stereo=1", "soft_mute=1", "antenna=0", "af=0"},
            {"enable", "freq=106300", "region=eu", "spacing=100", "stereo=true", "soft_mute=1", "antenna=0", "af=0"},
            {"enable", "freq=106300", "region=eu", "spacing=100", "stereo=1", "soft_mute=2", "antenna=0", "af=0"},
            {"enable", "freq=106300", "region=eu", "spacing=100", "stereo=1", "soft_mute=1", "antenna=-1", "af=0"},
            {"enable", "freq=106300", "region=eu", "spacing=100", "stereo=1", "soft_mute=1", "antenna=256", "af=0"},
            {"enable", "freq=106300", "region=eu", "spacing=100", "stereo=1", "soft_mute=1", "antenna=0", "af=2"},
    };
    StartupConfig config;

    for (const auto &args : invalid) {
        EXPECT_FALSE(parse_startup_config(args, &config));
    }
}

TEST(StartupConfigTest, RejectsPositionalParameters) {
    const std::vector<std::string> args = {
            "enable", "106300", "eu", "100", "1", "1", "0", "1",
    };
    StartupConfig config;

    EXPECT_FALSE(parse_startup_config(args, &config));
}

TEST(StartupConfigTest, RejectsFrequencyOutsideRegion) {
    const std::vector<std::vector<std::string>> invalid = {
            {"enable", "freq=76000", "region=eu", "spacing=100", "stereo=1", "soft_mute=1", "antenna=0", "af=0"},
            {"enable", "freq=76000", "region=us", "spacing=200", "stereo=1", "soft_mute=1", "antenna=0", "af=0"},
            {"enable", "freq=108000", "region=jp", "spacing=100", "stereo=1", "soft_mute=1", "antenna=0", "af=0"},
    };
    StartupConfig config;

    for (const auto &args : invalid) {
        EXPECT_FALSE(parse_startup_config(args, &config));
    }
}

TEST(StartupConfigTest, ParsesRuntimeRegionAndSpacingValues) {
    StartupRegion region;
    int spacing_khz = 0;

    EXPECT_TRUE(parse_region_name("eu", &region));
    EXPECT_EQ(region, StartupRegion::kEurope);
    EXPECT_TRUE(parse_region_name("us", &region));
    EXPECT_EQ(region, StartupRegion::kUnitedStates);
    EXPECT_TRUE(parse_region_name("jp", &region));
    EXPECT_EQ(region, StartupRegion::kJapan);
    EXPECT_TRUE(parse_region_name("jp_wide", &region));
    EXPECT_EQ(region, StartupRegion::kJapanWide);
    EXPECT_FALSE(parse_region_name("1", &region));

    EXPECT_TRUE(parse_spacing_khz("50", &spacing_khz));
    EXPECT_EQ(spacing_khz, 50);
    EXPECT_TRUE(parse_spacing_khz("100", &spacing_khz));
    EXPECT_EQ(spacing_khz, 100);
    EXPECT_TRUE(parse_spacing_khz("200", &spacing_khz));
    EXPECT_EQ(spacing_khz, 200);
    EXPECT_FALSE(parse_spacing_khz("2", &spacing_khz));
}

}  // namespace
