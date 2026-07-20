#pragma once

#include <stdint.h>

#include <string>
#include <string_view>
#include <vector>

enum class StartupRegion {
    kEurope,
    kUnitedStates,
    kJapan,
    kJapanWide,
};

struct StartupConfig {
    uint32_t frequency_khz = 0;
    StartupRegion region = StartupRegion::kEurope;
    int spacing_khz = 0;
    bool stereo = false;
    bool soft_mute = true;
    int antenna = 0;
    bool auto_af = false;
};

bool parse_startup_config(
        const std::vector<std::string> &args,
        StartupConfig *config,
        std::string *error = nullptr
);

bool parse_region_name(std::string_view value, StartupRegion *region);
bool parse_spacing_khz(std::string_view value, int *spacing_khz);
