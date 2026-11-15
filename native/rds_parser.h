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
// Helium carries up to 200 bytes of AF entries, with one int32 frequency per entry.
constexpr int kMaxRdsAfCount = 50;

// HAL search-list callback struct has rel_freq[20]. Full-band HAL scan can
// collect more stations through scan-next callbacks, but this payload is capped.
constexpr int kMaxRdsSearchCount = 20;

struct RdsTextPayload {
    int pi = 0;
    int pty = 0;
    // UTF-8 byte length of text (converted from EBU Latin G0, see below).
    int text_len = 0;
    int block_count = 0;
    // Worst case: 64 RDS characters x 3 UTF-8 bytes (e.g. 0xA9 = U+20AC) + NUL.
    char text[256] = "";
    // Raw EBU Latin G0 bytes after terminator/padding trim, before the UTF-8
    // conversion. RT+ tag start/length indices refer to these bytes, NOT to
    // the UTF-8 text (load-bearing for RT+ slicing, issue #103).
    unsigned char raw_text[65] = {};
    int raw_text_len = 0;
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

// Converts raw RDS G0 (EBU Latin, EN 50067:1998 Annex E) bytes to UTF-8.
// Writes at most out_size - 1 bytes into out and always NUL-terminates.
// Returns the number of UTF-8 bytes written (excluding the NUL).
int convert_ebu_latin_to_utf8(const unsigned char *src, int len, char *out, int out_size);

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

bool decode_rds_country_iso(int ecc, int country_code, char country_iso[3]);
