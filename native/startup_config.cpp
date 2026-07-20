#include "startup_config.h"

#include "region_profile.h"

#include <charconv>
#include <string_view>

namespace {

bool fail(std::string *error, const char *message) {
    if (error != nullptr) {
        *error = message;
    }
    return false;
}

bool parse_int(std::string_view value, int *result) {
    if (value.empty() || result == nullptr) {
        return false;
    }

    int parsed = 0;
    const char *begin = value.data();
    const char *end = begin + value.size();
    const auto conversion = std::from_chars(begin, end, parsed);
    if (conversion.ec != std::errc() || conversion.ptr != end) {
        return false;
    }

    *result = parsed;
    return true;
}

bool parse_bool(std::string_view value, bool *result) {
    int parsed = 0;
    if (!parse_int(value, &parsed) || (parsed != 0 && parsed != 1)) {
        return false;
    }
    *result = parsed == 1;
    return true;
}

}  // namespace

bool parse_region_name(std::string_view value, StartupRegion *region) {
    if (region == nullptr) {
        return false;
    }

    if (value == "eu") {
        *region = StartupRegion::kEurope;
    } else if (value == "us") {
        *region = StartupRegion::kUnitedStates;
    } else if (value == "jp") {
        *region = StartupRegion::kJapan;
    } else if (value == "jp_wide") {
        *region = StartupRegion::kJapanWide;
    } else {
        return false;
    }
    return true;
}

bool parse_spacing_khz(std::string_view value, int *spacing_khz) {
    return parse_int(value, spacing_khz) && (
        *spacing_khz == 50 ||
        *spacing_khz == 100 ||
        *spacing_khz == 200
    );
}

bool parse_startup_config(
        const std::vector<std::string> &args,
        StartupConfig *config,
        std::string *error
) {
    if (config == nullptr) {
        return fail(error, "missing output config");
    }

    if (args.size() != 8 || args[0] != "enable") {
        return fail(error, "enable requires seven named parameters");
    }

    StartupConfig parsed;
    bool has_frequency = false;
    bool has_region = false;
    bool has_spacing = false;
    bool has_stereo = false;
    bool has_soft_mute = false;
    bool has_antenna = false;
    bool has_af = false;

    for (size_t i = 1; i < args.size(); ++i) {
        const std::string &arg = args[i];
        const size_t separator = arg.find('=');

        if (separator == std::string::npos || separator == 0 || separator + 1 >= arg.size()) {
            return fail(error, "expected name=value parameter");
        }

        const std::string_view name(arg.data(), separator);
        const std::string_view value(arg.data() + separator + 1, arg.size() - separator - 1);

        if (name == "freq") {
            int frequency = 0;

            if (has_frequency) {
                return fail(error, "duplicate freq");
            }

            if (!parse_int(value, &frequency) || frequency < 76000 || frequency > 108000) {
                return fail(error, "invalid freq");
            }

            parsed.frequency_khz = static_cast<uint32_t>(frequency);
            has_frequency = true;
        } else if (name == "region") {
            if (has_region) {
                return fail(error, "duplicate region");
            }

            if (!parse_region_name(value, &parsed.region)) {
                return fail(error, "invalid region");
            }

            has_region = true;
        } else if (name == "spacing") {
            if (has_spacing) {
                return fail(error, "duplicate spacing");
            }

            if (!parse_spacing_khz(value, &parsed.spacing_khz)) {
                return fail(error, "invalid spacing");
            }

            has_spacing = true;
        } else if (name == "stereo") {
            if (has_stereo) {
                return fail(error, "duplicate stereo");
            }

            if (!parse_bool(value, &parsed.stereo)) {
                return fail(error, "invalid stereo");
            }

            has_stereo = true;
        } else if (name == "soft_mute") {
            if (has_soft_mute) {
                return fail(error, "duplicate soft_mute");
            }

            if (!parse_bool(value, &parsed.soft_mute)) {
                return fail(error, "invalid soft_mute");
            }

            has_soft_mute = true;
        } else if (name == "antenna") {
            if (has_antenna) {
                return fail(error, "duplicate antenna");
            }

            if (!parse_int(value, &parsed.antenna) || parsed.antenna < 0 || parsed.antenna > 255) {
                return fail(error, "invalid antenna");
            }

            has_antenna = true;
        } else if (name == "af") {
            if (has_af) {
                return fail(error, "duplicate af");
            }

            if (!parse_bool(value, &parsed.auto_af)) {
                return fail(error, "invalid af");
            }

            has_af = true;
        } else {
            return fail(error, "unknown parameter");
        }
    }

    if (!has_frequency || !has_region || !has_spacing || !has_stereo || !has_soft_mute || !has_antenna || !has_af) {
        return fail(error, "missing required parameter");
    }

    const RegionProfile &profile = get_region_profile(parsed.region);

    if (
        parsed.frequency_khz < profile.lower_frequency_khz ||
        parsed.frequency_khz > profile.upper_frequency_khz
    ) {
        return fail(error, "freq outside region");
    }

    *config = parsed;

    if (error != nullptr) {
        error->clear();
    }

    return true;
}
