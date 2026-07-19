#pragma once

#include <stdint.h>

#include "startup_config.h"

constexpr int kFmEmphasis75Microseconds = 0;
constexpr int kFmEmphasis50Microseconds = 1;

constexpr int kFmRdsStandardRbds = 0;
constexpr int kFmRdsStandardRds = 1;

struct RegionProfile {
    uint32_t lower_frequency_khz;
    uint32_t upper_frequency_khz;
    int emphasis;
    int rds_standard;
    int legacy_band;
    int helium_region;
};

const RegionProfile &get_region_profile(StartupRegion region);
const char *get_region_name(StartupRegion region);
uint32_t clamp_frequency_to_region(const RegionProfile &profile, uint32_t frequency_khz);
