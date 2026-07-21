#include "ctl_json.h"

#include <stdio.h>
#include <string.h>

namespace {

std::string json_escape(const char *value) {
    std::string out = "\"";
    if (value == nullptr) {
        out += "\"";
        return out;
    }

    for (const unsigned char *it = reinterpret_cast<const unsigned char *>(value); *it != '\0'; ++it) {
        switch (*it) {
            case '\\':
                out += "\\\\";
                break;
            case '"':
                out += "\\\"";
                break;
            case '\b':
                out += "\\b";
                break;
            case '\f':
                out += "\\f";
                break;
            case '\n':
                out += "\\n";
                break;
            case '\r':
                out += "\\r";
                break;
            case '\t':
                out += "\\t";
                break;
            default:
                if (*it < 0x20) {
                    char escaped[8];
                    snprintf(escaped, sizeof(escaped), "\\u%04x", *it);
                    out += escaped;
                } else {
                    out += static_cast<char>(*it);
                }
                break;
        }
    }

    out += "\"";
    return out;
}

void append_field(std::string &json, bool &first, const char *name, const std::string &value) {
    if (!first) {
        json += ',';
    }
    first = false;
    json += json_escape(name);
    json += ':';
    json += value;
}

bool af_equals_cache(const RadioStateJsonCache &cache, const int *af_khz, int af_count) {
    if (af_count != cache.af_count) {
        return false;
    }
    if (af_count <= 0) {
        return true;
    }
    if (af_khz == nullptr) {
        return false;
    }
    return memcmp(af_khz, cache.af_khz, static_cast<size_t>(af_count) * sizeof(af_khz[0])) == 0;
}

} // namespace

std::string build_native_event_json(const char *type) {
    if (type == nullptr || type[0] == '\0') {
        return "";
    }
    return "{\"type\":" + json_escape(type) + "}";
}

bool build_search_done_json(const int *frequencies_khz, int count, std::string *json) {
    if (json == nullptr || count < 0 || (count > 0 && frequencies_khz == nullptr)) {
        return false;
    }

    *json = "{\"type\":\"search_done\",\"stations\":[";
    for (int i = 0; i < count; ++i) {
        if (i > 0) {
            *json += ',';
        }
        *json += std::to_string(frequencies_khz[i]);
    }
    *json += "]}";
    return true;
}

bool build_radio_state_patch_json(
        const RadioStateJsonCache &cache,
        const radio_state_patch_t *patch,
        std::string *json
) {
    if (patch == nullptr || json == nullptr) {
        return false;
    }

    *json = "{\"type\":\"state\"";
    std::string rds;
    bool first_top = false;
    bool first_rds = true;
    bool changed = false;

    if (patch->frequency_khz != RADIO_PATCH_ABSENT_INT && patch->frequency_khz != cache.frequency_khz) {
        append_field(*json, first_top, "frequency", std::to_string(patch->frequency_khz));
        changed = true;
    }

    if (patch->stereo != RADIO_PATCH_ABSENT_INT && patch->stereo != cache.stereo) {
        append_field(*json, first_top, "stereo", patch->stereo == 1 ? "true" : "false");
        changed = true;
    }

    if (patch->ps != nullptr && cache.ps != patch->ps) {
        append_field(rds, first_rds, "ps", json_escape(patch->ps));
    }

    if (patch->rt != nullptr && cache.rt != patch->rt) {
        append_field(rds, first_rds, "rt", json_escape(patch->rt));
    }

    if (patch->pi != nullptr && cache.pi != patch->pi) {
        append_field(rds, first_rds, "pi", json_escape(patch->pi));
    }

    if (patch->country != nullptr && cache.country != patch->country) {
        append_field(rds, first_rds, "country", json_escape(patch->country));
    }

    if (patch->pty != RADIO_PATCH_ABSENT_INT && patch->pty != cache.pty) {
        append_field(rds, first_rds, "pty", std::to_string(patch->pty));
    }

    if (patch->af_count != RADIO_PATCH_ABSENT_INT && patch->af_count < 0) {
        return false;
    }

    if (patch->af_count > 0 && patch->af_khz == nullptr) {
        return false;
    }

    if (patch->af_count != RADIO_PATCH_ABSENT_INT && !af_equals_cache(cache, patch->af_khz, patch->af_count)) {
        const int count = patch->af_count > kRadioJsonMaxAfCount ? kRadioJsonMaxAfCount : patch->af_count;
        std::string af = "[";
        for (int i = 0; i < count; ++i) {
            if (i > 0) {
                af += ',';
            }
            af += std::to_string(patch->af_khz[i]);
        }
        af += ']';
        append_field(rds, first_rds, "af", af);
    }

    if (!rds.empty()) {
        append_field(*json, first_top, "rds", "{" + rds + "}");
        changed = true;
    }

    *json += '}';

    if (!changed) {
        json->clear();
        return false;
    }

    return true;
}

void apply_radio_state_patch(RadioStateJsonCache *cache, const radio_state_patch_t *patch) {
    if (cache == nullptr || patch == nullptr) {
        return;
    }

    if (patch->frequency_khz != RADIO_PATCH_ABSENT_INT) {
        cache->frequency_khz = patch->frequency_khz;
    }

    if (patch->ps != nullptr) {
        cache->ps = patch->ps;
    }

    if (patch->rt != nullptr) {
        cache->rt = patch->rt;
    }

    if (patch->pi != nullptr) {
        cache->pi = patch->pi;
    }

    if (patch->country != nullptr) {
        cache->country = patch->country;
    }

    if (patch->pty != RADIO_PATCH_ABSENT_INT) {
        cache->pty = patch->pty;
    }

    if (patch->af_count != RADIO_PATCH_ABSENT_INT) {
        const int count = patch->af_count > kRadioJsonMaxAfCount ? kRadioJsonMaxAfCount : patch->af_count;
        cache->af_count = count;
        if (count > 0 && patch->af_khz != nullptr) {
            memcpy(cache->af_khz, patch->af_khz, static_cast<size_t>(count) * sizeof(cache->af_khz[0]));
        }
    }

    if (patch->stereo != RADIO_PATCH_ABSENT_INT) {
        cache->stereo = patch->stereo;
    }
}
