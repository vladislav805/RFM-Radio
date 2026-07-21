#pragma once

#include <string>

#include "ctl_server.h"

constexpr int kRadioJsonMaxAfCount = 50;

struct RadioStateJsonCache {
    int frequency_khz = RADIO_PATCH_ABSENT_INT;
    std::string ps;
    std::string rt;
    std::string pi;
    std::string country;
    int pty = RADIO_PATCH_ABSENT_INT;
    int af_khz[kRadioJsonMaxAfCount] = {};
    int af_count = RADIO_PATCH_ABSENT_INT;
    int stereo = RADIO_PATCH_ABSENT_INT;
};

std::string build_native_event_json(const char *type);
bool build_search_done_json(const int *frequencies_khz, int count, std::string *json);
bool build_radio_state_patch_json(
        const RadioStateJsonCache &cache,
        const radio_state_patch_t *patch,
        std::string *json
);
void apply_radio_state_patch(RadioStateJsonCache *cache, const radio_state_patch_t *patch);
