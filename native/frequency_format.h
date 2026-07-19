#pragma once

#include <stdio.h>
#include <string>

template <typename Frequency>
std::string format_frequency_list_khz(const Frequency *frequencies, int count) {
    if (frequencies == nullptr || count <= 0) {
        return "";
    }

    std::string output;

    for (int i = 0; i < count; ++i) {
        char chunk[16];
        const int frequency_khz = static_cast<int>(frequencies[i]);
        snprintf(chunk, sizeof(chunk), "%s%d", i == 0 ? "" : " ", frequency_khz);

        output += chunk;
    }

    return output;
}
