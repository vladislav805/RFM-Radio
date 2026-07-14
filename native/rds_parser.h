#pragma once

enum class RdsTextPayloadKind {
    kProgramService,
    kRadioText,
};

// Both backends expose search-list entries as big-endian uint16 values, but
//  - HAL reports 50 kHz offsets from the band lower edge;
//  - legacy reports absolute 50 kHz channel numbers.
enum class RdsSearchListKind {
    kRelativeOffsets,
    kAbsoluteChannels,
};

constexpr int kUnknownRdsPayloadLen = -1;
constexpr int kMaxRdsAfCount = 25;

// HAL search-list callback struct has rel_freq[20]. Full-band HAL scan can
// collect more stations through scan-next callbacks, but this payload is capped.
constexpr int kMaxRdsSearchCount = 20;

struct RdsTextPayload {
    int pi = 0;
    int pty = 0;
    int text_len = 0;
    int block_count = 0;
    char text[120] = "";
};

struct RdsAfList {
    // Number of valid entries in frequencies_khz.
    int count = 0;

    // Current tuned frequency from the AF payload header, in kHz.
    int current_frequency_khz = 0;

    // Alternative frequencies decoded from the payload, in kHz.
    int frequencies_khz[kMaxRdsAfCount] = {};
};

struct RdsSearchList {
    // Number of parsed entries in frequencies_khz, capped to kMaxRdsSearchCount.
    int count = 0;

    // Raw station count reported by the payload before parser-side capping.
    int reported_count = 0;

    // Found station frequencies decoded to absolute kHz values.
    int frequencies_khz[kMaxRdsSearchCount] = {};
};

bool parse_rds_text_payload(
        const unsigned char *payload,
        int payload_len,
        RdsTextPayloadKind kind,
        int max_text_len,
        RdsTextPayload *out
);

bool parse_rds_af_payload(
        const unsigned char *payload,
        int payload_len,
        RdsAfList *out
);

bool parse_rds_search_list_payload(
        const unsigned char *payload,
        int payload_len,
        RdsSearchListKind kind,
        int lower_frequency_khz,
        RdsSearchList *out
);
