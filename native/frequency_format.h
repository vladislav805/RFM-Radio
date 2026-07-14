#pragma once

#include <stdio.h>
#include <string>

enum class FrequencyListFormat {
    kDebugKhz,
    kCompact100Khz,
};

template <typename Frequency>
std::string format_frequency_list_khz(
        const Frequency *frequencies,
        int count,
        FrequencyListFormat format = FrequencyListFormat::kDebugKhz
) {
    if (frequencies == nullptr || count <= 0) {
        return "";
    }

    std::string output;

    for (int i = 0; i < count; ++i) {
        char chunk[16];
        const int frequency_khz = static_cast<int>(frequencies[i]);

        if (format == FrequencyListFormat::kCompact100Khz) {
            snprintf(chunk, sizeof(chunk), "%04d", frequency_khz / 100);
        } else {
            snprintf(chunk, sizeof(chunk), "%s%d", i == 0 ? "" : " ", frequency_khz);
        }

        output += chunk;
    }

    return output;
}
