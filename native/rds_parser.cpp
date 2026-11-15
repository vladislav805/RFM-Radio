#include "rds_parser.h"

#include <algorithm>
#include <cstdint>
#include <cstring>

namespace {

// Shared RDS text payload header: [0]=count/length, [1]=PTY, [2..3]=PI, [4]=vendor/header byte.
constexpr int kMetadataSize = 5;

// AF list payload header: [0..3]=tuned frequency, [4..5]=PI, [6]=AF count.
constexpr int kAfMetadataSize = 7;

// AF list entries are little-endian int32 kHz frequencies after the AF header.
constexpr int kAfEntrySize = 4;

// Search-list entries are big-endian 16-bit values after the count byte.
constexpr int kSearchEntrySize = 2;

// Program Service names are carried as fixed 8-byte blocks after the shared header.
constexpr int kPsBlockSize = 8;

// Keep PS bounded to the standard 8-character station name exposed to the app.
constexpr int kMaxPsBlocks = 8;

// Max RDS RT length.
constexpr int kMaxRtTextLen = 64;

// Leave room for the terminating null in RdsTextPayload::text.
constexpr int kMaxOutputTextLen = sizeof(RdsTextPayload::text) - 1;

struct RdsCountryRow {
    int ecc;
    char iso_codes[31];
};

// IEC 62106 registered country identifiers, indexed by the country nibble in PI.
constexpr RdsCountryRow kRdsCountryRows[] = {
        {0xa0, "USUSUSUSUSUSUSUSUSUSUS--USUS--"},
        {0xa1, "--------------------CACACACAGL"},
        {0xa2, "AIAGECFKBBBZKYCRCUARBRBMANGPBS"},
        {0xa3, "BOCOJMMQGFPYNI--PADMDOCLGDTCGY"},
        {0xa4, "GTHNAW--MSTTPESRUYKNLCSVHTVE--"},
        {0xa5, "--------------------MXVCMXMXMX"},
        {0xa6, "----------------------------PM"},
        {0xd0, "CMCFDJMGMLAOGQGAGNZABFCGTGBJMW"},
        {0xd1, "NALRGHMRSTCVSNGMBI--BWKMTZETBG"},
        {0xd2, "SLZWMZUGSZKESONETDGWZRCITZZM--"},
        {0xd3, "----EH--RWLS--SC--MU--SD------"},
        {0xe0, "DEDZADILITBERUPSALATHUMTDE--EG"},
        {0xe1, "GRCYSMCHJOFILUBGDKGIIQGBLYROFR"},
        {0xe2, "MACZPLVASKSYTN--LIISMCLTRSESNO"},
        {0xe3, "MEIETRMK------NLLVLBAZHRKZSEBY"},
        {0xe4, "MDEEKG----UAKSPTSIAM--GE----BA"},
        {0xf0, "AUAUAUAUAUAUAUAUSAAFMMCNKPBHMY"},
        {0xf1, "KIBTBDPKFJOMNRIRNZSBBNLKTWKRHK"},
        {0xf2, "KWQAKHWSINMOVNPHJPSGMVIDAENPVU"},
        {0xf3, "LATHTO----------PG--YE----FMMN"},
};

// RDS G0 primary character set (EBU Latin), EN 50067:1998 Annex E /
// IEC 62106 Annex E. Each raw byte maps to a Unicode code point; UTF-8 is
// encoded at runtime. Code points mirrored from redsea src/text/rdsstring.cc
// (getRDSCharString, permissive license). Differences from redsea:
//  - 0x00-0x0C and 0x0E-0x1F are mapped as low-byte aliases of 0x80-0x9F
//    (some broken encoders transmit specials with the high bit stripped);
//  - 0x0D keeps its control meaning (RT string terminator) and is not
//    remapped to a printable character.
// 0x24, 0x5E, 0x60, 0x7E are NOT plain ASCII in this set: U+00A4, U+2015,
// U+2016, U+00AF. Do NOT confuse with the DAB ETSI "EBU Latin" variant.
constexpr uint16_t kEbuLatinG0CodePoints[256] = {
        // 0x00-0x0F: low-byte aliases of 0x80-0x8F, except 0x0D (terminator)
        0x00E1, 0x00E0, 0x00E9, 0x00E8, 0x00ED, 0x00EC, 0x00F3, 0x00F2,
        0x00FA, 0x00F9, 0x00D1, 0x00C7, 0x015E, 0x000D, 0x00A1, 0x0132,
        // 0x10-0x1F: low-byte aliases of 0x90-0x9F
        0x00E2, 0x00E4, 0x00EA, 0x00EB, 0x00EE, 0x00EF, 0x00F4, 0x00F6,
        0x00FB, 0x00FC, 0x00F1, 0x00E7, 0x015F, 0x01E7, 0x0131, 0x0133,
        // 0x20-0x2F (0x24 = U+00A4)
        0x0020, 0x0021, 0x0022, 0x0023, 0x00A4, 0x0025, 0x0026, 0x0027,
        0x0028, 0x0029, 0x002A, 0x002B, 0x002C, 0x002D, 0x002E, 0x002F,
        // 0x30-0x4F: plain ASCII
        0x0030, 0x0031, 0x0032, 0x0033, 0x0034, 0x0035, 0x0036, 0x0037,
        0x0038, 0x0039, 0x003A, 0x003B, 0x003C, 0x003D, 0x003E, 0x003F,
        0x0040, 0x0041, 0x0042, 0x0043, 0x0044, 0x0045, 0x0046, 0x0047,
        0x0048, 0x0049, 0x004A, 0x004B, 0x004C, 0x004D, 0x004E, 0x004F,
        // 0x50-0x5F (0x5E = U+2015)
        0x0050, 0x0051, 0x0052, 0x0053, 0x0054, 0x0055, 0x0056, 0x0057,
        0x0058, 0x0059, 0x005A, 0x005B, 0x005C, 0x005D, 0x2015, 0x005F,
        // 0x60-0x6F (0x60 = U+2016)
        0x2016, 0x0061, 0x0062, 0x0063, 0x0064, 0x0065, 0x0066, 0x0067,
        0x0068, 0x0069, 0x006A, 0x006B, 0x006C, 0x006D, 0x006E, 0x006F,
        // 0x70-0x7F (0x7E = U+00AF, 0x7F = space)
        0x0070, 0x0071, 0x0072, 0x0073, 0x0074, 0x0075, 0x0076, 0x0077,
        0x0078, 0x0079, 0x007A, 0x007B, 0x007C, 0x007D, 0x00AF, 0x0020,
        // 0x80-0x8F
        0x00E1, 0x00E0, 0x00E9, 0x00E8, 0x00ED, 0x00EC, 0x00F3, 0x00F2,
        0x00FA, 0x00F9, 0x00D1, 0x00C7, 0x015E, 0x03B2, 0x00A1, 0x0132,
        // 0x90-0x9F
        0x00E2, 0x00E4, 0x00EA, 0x00EB, 0x00EE, 0x00EF, 0x00F4, 0x00F6,
        0x00FB, 0x00FC, 0x00F1, 0x00E7, 0x015F, 0x01E7, 0x0131, 0x0133,
        // 0xA0-0xAF
        0x00AA, 0x03B1, 0x00A9, 0x2030, 0x01E6, 0x011B, 0x0148, 0x0151,
        0x03C0, 0x20AC, 0x00A3, 0x0024, 0x2190, 0x2191, 0x2192, 0x2193,
        // 0xB0-0xBF
        0x00BA, 0x00B9, 0x00B2, 0x00B3, 0x00B1, 0x0130, 0x0144, 0x0171,
        0x00B5, 0x00BF, 0x00F7, 0x00B0, 0x00BC, 0x00BD, 0x00BE, 0x00A7,
        // 0xC0-0xCF
        0x00C1, 0x00C0, 0x00C9, 0x00C8, 0x00CD, 0x00CC, 0x00D3, 0x00D2,
        0x00DA, 0x00D9, 0x0158, 0x010C, 0x0160, 0x017D, 0x00D0, 0x013F,
        // 0xD0-0xDF
        0x00C2, 0x00C4, 0x00CA, 0x00CB, 0x00CE, 0x00CF, 0x00D4, 0x00D6,
        0x00DB, 0x00DC, 0x0159, 0x010D, 0x0161, 0x017E, 0x0111, 0x0140,
        // 0xE0-0xEF
        0x00C3, 0x00C5, 0x00C6, 0x0152, 0x0177, 0x00DD, 0x00D5, 0x00D8,
        0x00DE, 0x014A, 0x0154, 0x0106, 0x015A, 0x0179, 0x0166, 0x00F0,
        // 0xF0-0xFF
        0x00E3, 0x00E5, 0x00E6, 0x0153, 0x0175, 0x00FD, 0x00F5, 0x00F8,
        0x00FE, 0x014B, 0x0155, 0x0107, 0x015B, 0x017A, 0x0167, 0x0020,
};

}  // namespace

int convert_ebu_latin_to_utf8(
        const unsigned char *src,
        int len,
        char *out,
        int out_size
) {
    if (out == nullptr || out_size <= 0) {
        return 0;
    }

    int written = 0;
    if (src != nullptr) {
        for (int i = 0; i < len; ++i) {
            // All table code points are below U+10000, so at most 3 UTF-8 bytes.
            const uint32_t code_point = kEbuLatinG0CodePoints[src[i]];
            char encoded[3];
            int encoded_len;
            if (code_point < 0x80) {
                encoded[0] = static_cast<char>(code_point);
                encoded_len = 1;
            } else if (code_point < 0x800) {
                encoded[0] = static_cast<char>(0xC0 | (code_point >> 6));
                encoded[1] = static_cast<char>(0x80 | (code_point & 0x3F));
                encoded_len = 2;
            } else {
                encoded[0] = static_cast<char>(0xE0 | (code_point >> 12));
                encoded[1] = static_cast<char>(0x80 | ((code_point >> 6) & 0x3F));
                encoded[2] = static_cast<char>(0x80 | (code_point & 0x3F));
                encoded_len = 3;
            }

            if (written + encoded_len >= out_size) {
                break;
            }

            memcpy(out + written, encoded, static_cast<size_t>(encoded_len));
            written += encoded_len;
        }
    }

    out[written] = '\0';
    return written;
}

bool parse_rds_text_payload(
        const unsigned char *payload,
        int payload_len,
        RdsTextPayloadKind kind,
        int max_text_len,
        RdsTextPayload *out
) {
    if (out != nullptr) {
        *out = RdsTextPayload{};
    }

    if (payload == nullptr || out == nullptr || max_text_len < 0) {
        return false;
    }

    if (payload_len >= 0 && payload_len < kMetadataSize) {
        return false;
    }

    const int unit_count = kind == RdsTextPayloadKind::kProgramService
            ? (payload[0] & 0x0f)
            : (payload[0] & 0x7f);

    const int max_units = kind == RdsTextPayloadKind::kProgramService
            ? kMaxPsBlocks
            : kMaxRtTextLen;

    if (unit_count > max_units) {
        return false;
    }

    if (kind == RdsTextPayloadKind::kProgramService && unit_count == 0) {
        return false;
    }

    int raw_len = kind == RdsTextPayloadKind::kProgramService
            ? unit_count * kPsBlockSize
            : unit_count;

    // Raw text is inherently bounded: PS <= 8 * 8 and RT <= 64 units, which
    // fits raw_text. max_text_len and kMaxOutputTextLen are OUTPUT (UTF-8)
    // byte limits and are applied during the conversion below.
    raw_len = std::min(raw_len, static_cast<int>(sizeof(out->raw_text)));

    if (payload_len >= 0) {
        raw_len = std::min(raw_len, payload_len - kMetadataSize);
    }

    if (raw_len < 0) {
        return false;
    }

    if (raw_len > 0) {
        memcpy(out->raw_text, payload + kMetadataSize, static_cast<size_t>(raw_len));
    }

    // Raw-byte post-processing. The \r terminator truncation (RT only) and
    // the trailing padding trim operate on RAW EBU Latin bytes and MUST run
    // before the UTF-8 conversion: 0xff is vendor padding here, but after the
    // conversion a 0xff-prefixed byte sequence would be a UTF-8 lead byte.
    // NUL is trimmed as padding for BOTH kinds so NUL-padded PS/RT does not
    // surface as a run of 'a'-with-acute alias characters. PS keeps trailing
    // spaces: station names are legitimately space-padded.
    if (kind == RdsTextPayloadKind::kRadioText) {
        for (int i = 0; i < raw_len; ++i) {
            if (out->raw_text[i] == '\r') {
                raw_len = i;
                break;
            }
        }
    }
    while (raw_len > 0) {
        const unsigned char last = out->raw_text[raw_len - 1];
        const bool padding = last == 0x00 ||
                (kind == RdsTextPayloadKind::kRadioText && (last == ' ' || last == 0xff));
        if (!padding) {
            break;
        }
        --raw_len;
    }

    out->raw_text_len = raw_len;

    // Convert EBU Latin G0 to UTF-8. text_len is a UTF-8 byte length.
    const int max_output_bytes = std::min(max_text_len, kMaxOutputTextLen);
    out->text_len = convert_ebu_latin_to_utf8(
            out->raw_text,
            raw_len,
            out->text,
            max_output_bytes + 1
    );

    out->pi = (payload[2] << 8) | payload[3];
    out->pty = payload[1] & 0x1f;
    out->block_count = kind == RdsTextPayloadKind::kProgramService ? unit_count : 0;

    return true;
}

bool parse_rds_af_payload(
        const unsigned char *payload,
        int payload_len,
        RdsAfList *out
) {
    if (out != nullptr) {
        *out = RdsAfList{};
    }

    if (payload == nullptr || out == nullptr) {
        return false;
    }

    if (payload_len >= 0 && payload_len < kAfMetadataSize) {
        return false;
    }

    // AF list payload:
    // [0..3]=current frequency in kHz
    // [4..5]=PI
    // [6]=AF count,
    // [7 + index * 4..]=little-endian uint32 AF frequency in kHz.
    const int count = payload[6];

    if (count <= 0 || count > kMaxRdsAfCount) {
        return false;
    }

    if (payload_len >= 0 && payload_len < kAfMetadataSize + count * kAfEntrySize) {
        return false;
    }

    const uint32_t current_frequency_khz =
            static_cast<uint32_t>(payload[0]) |
            (static_cast<uint32_t>(payload[1]) << 8) |
            (static_cast<uint32_t>(payload[2]) << 16) |
            (static_cast<uint32_t>(payload[3]) << 24);

    out->current_frequency_khz = static_cast<int>(current_frequency_khz);

    for (int i = 0; i < count; ++i) {
        const int offset = kAfMetadataSize + i * kAfEntrySize;

        const uint32_t frequency_khz =
                static_cast<uint32_t>(payload[offset]) |
                (static_cast<uint32_t>(payload[offset + 1]) << 8) |
                (static_cast<uint32_t>(payload[offset + 2]) << 16) |
                (static_cast<uint32_t>(payload[offset + 3]) << 24);

        out->frequencies_khz[i] = static_cast<int>(frequency_khz);
    }

    out->count = count;

    return true;
}

bool parse_rds_search_list_payload(
        const unsigned char *payload,
        int payload_len,
        RdsSearchListKind kind,
        int lower_frequency_khz,
        RdsSearchList *out
) {
    if (out != nullptr) {
        *out = RdsSearchList{};
    }

    if (payload == nullptr || out == nullptr) {
        return false;
    }

    if (payload_len >= 0 && payload_len < 1) {
        return false;
    }

    const int reported_count = payload[0];
    int count = std::min(reported_count, kMaxRdsSearchCount);

    if (payload_len >= 0) {
        count = std::min(count, (payload_len - 1) / kSearchEntrySize);
    }

    // Search list payload:
    // [0]=count
    // [1 + index * 2..]=big-endian uint16.
    // HAL encodes values as 50 kHz offsets from the current lower band edge.
    // Legacy V4L2 encodes values as absolute 50 kHz channel numbers.
    for (int i = 0; i < count; ++i) {
        const int offset = 1 + i * kSearchEntrySize;

        const int raw = (static_cast<int>(payload[offset]) << 8) | payload[offset + 1];

        out->frequencies_khz[i] = kind == RdsSearchListKind::kRelativeOffsets
                ? lower_frequency_khz + raw * 50
                : raw * 50;
    }

    out->count = count;
    out->reported_count = reported_count;

    return true;
}

bool decode_rds_country_iso(int ecc, int country_code, char country_iso[3]) {
    if (country_iso == nullptr) {
        return false;
    }

    country_iso[0] = '\0';
    if (country_code < 1 || country_code > 15) {
        return false;
    }

    for (const RdsCountryRow &row : kRdsCountryRows) {
        if (row.ecc != ecc) {
            continue;
        }

        const int offset = (country_code - 1) * 2;
        if (
            row.iso_codes[offset] == '-' ||
            row.iso_codes[offset] == '\0' ||
            row.iso_codes[offset + 1] == '\0'
        ) {
            return false;
        }

        country_iso[0] = row.iso_codes[offset];
        country_iso[1] = row.iso_codes[offset + 1];
        country_iso[2] = '\0';
        return true;
    }

    return false;
}
