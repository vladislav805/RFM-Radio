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

enum class RdsRtPlusKind {
    kHelium,
    kV4l2Qcom,
};

struct RdsRtPlusTag {
    int content_type = 0;
    int start = 0;
    int length = 0;
};

struct RdsRtPlusPayload {
    bool toggle = false;
    bool running = false;
    bool ert = false;
    RdsRtPlusTag tags[2] = {};
    int tag_count = 0;
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

// HAL ext_country_code_cb payload (RDS group 1A), 12 bytes:
// [0]=len [1]=PTY [2..3]=PI [4]=TA/TP header byte [5]=1A variant
// [6..7]=flags/reserved [8..9]=slow labelling ([9]=ECC when variant == 0).
//
// The 1A variant lives at [5]; [4] carries the firmware TA/TP header byte
// and stays outside the variant gate.
bool parse_rds_ecc_payload(
        const unsigned char *payload,
        int payload_len,
        char country_iso[3]
);

// Parses standard V4L2 raw RDS blocks (3 bytes per block: lsb, msb, block_id)
// looking for RDS group 1A variant 0 and decoding the ISO country code.
bool parse_v4l2_raw_rds_ecc(
        const unsigned char *payload,
        int payload_len,
        char country_iso[3]
);

struct RdsRtPlusSlices {
    char title[256] = "";
    char artist[256] = "";
    char album[256] = "";
};

const char *get_rds_rt_plus_content_type_name(int content_type);

bool parse_rds_rt_plus_payload(
        const unsigned char *payload,
        int payload_len,
        RdsRtPlusKind kind,
        RdsRtPlusPayload *out
);

bool evaluate_rds_rt_plus_slices(
        bool tags_valid,
        const RdsRtPlusTag tags[2],
        const unsigned char *raw_rt,
        int raw_rt_len,
        RdsRtPlusSlices *out
);
