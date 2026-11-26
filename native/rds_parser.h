#pragma once

#include "rt_plus_types.h"

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

// Qualcomm PS/RT header: [0]=count/length, [1]=PTY, [2..3]=PI, [4]=vendor byte.
constexpr int kRdsTextHeaderSize = 5;

// Low-nibble block count mask in Program Service header byte [0].
constexpr int kRdsPsCountMask = 0x0f;

// 7-bit character length mask in RadioText header byte [0].
constexpr int kRdsRtLengthMask = 0x7f;

// 5-bit Program Type (PTY) mask in RDS header byte [1].
constexpr int kRdsPtyMask = 0x1f;

// Bit shift to extract the 4-bit country nibble from a 16-bit PI code.
constexpr int kRdsPiCountryCodeShift = 12;

// Bit mask for the 4-bit country nibble in a 16-bit PI code.
constexpr int kRdsPiCountryCodeMask = 0x0f;

// Low 8-bit byte mask used for ECC and byte extraction.
constexpr int kRdsByteMask = 0xff;

// Bit width of a single byte when combining multi-byte RDS words.
constexpr int kRdsByteBits = 8;

// Shared header byte offsets for PS/RT/ECC payloads.
constexpr int kRdsTextPtyOffset = 1;

// Byte offset of the big-endian 16-bit PI code in shared PS/RT/ECC headers.
constexpr int kRdsTextPiOffset = 2;

// Frequency step size (kHz) used in hardware search list payloads.
constexpr int kRdsSearchChannelStepKhz = 50;

// Minimum valid 4-bit country nibble in IEC 62106 PI codes.
constexpr int kRdsMinPiCountryCode = 1;

// Maximum valid 4-bit country nibble in IEC 62106 PI codes.
constexpr int kRdsMaxPiCountryCode = 15;

// Two-character ISO country code stride in country lookup rows.
constexpr int kRdsIsoCountryCodeStride = 2;

// Byte offset of the big-endian PI code inside a HAL group 1A (ECC) payload.
constexpr int kRdsEccPiOffset = 2;

// Byte offset of the group 1A variant selector inside a HAL ECC payload.
constexpr int kRdsEccVariantOffset = 5;

// Group 1A variant 0 carrying Extended Country Code slow labelling.
constexpr int kRdsEccVariantSlowLabelling = 0;

// Byte offset of the low slow-labelling byte (ECC) inside a HAL ECC payload.
constexpr int kRdsEccValueOffset = 9;

// Byte length of a single V4L2 raw RDS block record ([0]=LSB, [1]=MSB, [2]=meta).
constexpr int kRdsRawTupleSize = 3;

// Bit mask selecting the block identifier (A/B/C/D) in V4L2 raw RDS metadata byte [2].
constexpr int kRdsRawBlockIdMask = 0x07;

// Error indicator bit in V4L2 raw RDS metadata byte [2] for uncorrectable blocks.
constexpr int kRdsRawBlockErrorBit = 0x80;

// V4L2 raw RDS block A index carrying PI.
constexpr int kRdsRawBlockA = 0;

// V4L2 raw RDS block B index carrying group type and version.
constexpr int kRdsRawBlockB = 1;

// V4L2 raw RDS block C index carrying group 1A variant and ECC slow labelling.
constexpr int kRdsRawBlockC = 2;

// Bit shift locating the 5-bit group-type+version field in block B.
constexpr int kRdsRawGroupTypeShift = 11;

// Bit mask isolating the 5-bit group-type+version field in block B.
constexpr int kRdsRawGroupTypeMask = 0x1f;

// RDS group number 1 (slow labelling and PIN).
constexpr int kRdsRawGroup1Number = 1;

// Version A bit (0) for RDS block B group coding.
constexpr int kRdsRawGroupVersionABit = 0;

// 5-bit block B group selector value for RDS group 1A ((1 << 1) | 0 = 0x02).
constexpr int kRdsRawGroup1A = (kRdsRawGroup1Number << 1) | kRdsRawGroupVersionABit;

// Bit shift locating the 3-bit slow-labelling variant in block C of group 1A.
constexpr int kRdsRawVariantShift = 12;

// Bit mask isolating the 3-bit slow-labelling variant in block C of group 1A.
constexpr int kRdsRawVariantMask = 0x07;

// Bit 0 in HAL RT+ flags byte [7] indicating item toggle state.
constexpr unsigned char kRdsHeliumRtPlusToggleBit = 0x01;

// Bit 1 in HAL RT+ flags byte [7] indicating item running state.
constexpr unsigned char kRdsHeliumRtPlusRunningBit = 0x02;

// Exclusive upper bound for 1-byte ASCII code points in UTF-8.
constexpr unsigned int kRdsUtf8OneByteLimit = 0x80;

// Exclusive upper bound for 2-byte BMP code points in UTF-8.
constexpr unsigned int kRdsUtf8TwoByteLimit = 0x800;

// Leading byte prefix for 2-byte UTF-8 sequences.
constexpr unsigned int kRdsUtf8TwoByteLeadPrefix = 0xC0;

// Leading byte prefix for 3-byte UTF-8 sequences.
constexpr unsigned int kRdsUtf8ThreeByteLeadPrefix = 0xE0;

// Continuation byte prefix for multi-byte UTF-8 sequences.
constexpr unsigned int kRdsUtf8ContinuationPrefix = 0x80;

// 6-bit payload mask for UTF-8 continuation bytes.
constexpr unsigned int kRdsUtf8ContinuationMask = 0x3F;

// Bit shift for the upper bits of a 2-byte UTF-8 sequence / middle 6 bits of 3-byte.
constexpr int kRdsUtf8ShiftMid = 6;

// Bit shift for the top 4 bits of a 3-byte UTF-8 sequence.
constexpr int kRdsUtf8ShiftHigh = 12;

// Extracts the 5-bit Program Type (PTY) from an RDS payload header byte.
inline int rds_decode_pty(unsigned char header_byte) {
    return header_byte & kRdsPtyMask;
}

// Extracts the 4-bit IEC 62106 country nibble from a 16-bit PI code.
inline int rds_decode_pi_country_code(int pi) {
    return (pi >> kRdsPiCountryCodeShift) & kRdsPiCountryCodeMask;
}

// Reads a big-endian 16-bit unsigned value from bytes[offset..offset+1].
inline int rds_read_be16(const unsigned char *bytes, int offset) {
    return (static_cast<int>(bytes[offset]) << kRdsByteBits) | static_cast<int>(bytes[offset + 1]);
}

// Reads a big-endian 16-bit unsigned value from a signed-char vendor payload at offset.
inline int rds_read_be16(const char *bytes, int offset) {
    return rds_read_be16(reinterpret_cast<const unsigned char *>(bytes), offset);
}

// Sentinel length passed when the vendor callback payload carries its own length byte.
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

const char *get_rds_rt_plus_content_type_name(int content_type);

bool parse_rds_rt_plus_payload(
        const unsigned char *payload,
        int payload_len,
        RdsRtPlusKind kind,
        RdsRtPlusPayload *out
);
