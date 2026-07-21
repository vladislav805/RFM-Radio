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

}  // namespace

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

    int text_len = kind == RdsTextPayloadKind::kProgramService
            ? unit_count * kPsBlockSize
            : unit_count;

    text_len = std::min(text_len, max_text_len);
    text_len = std::min(text_len, kMaxOutputTextLen);

    if (payload_len >= 0) {
        text_len = std::min(text_len, payload_len - kMetadataSize);
    }

    if (text_len < 0) {
        return false;
    }

    if (text_len > 0) {
        memcpy(out->text, payload + kMetadataSize, static_cast<size_t>(text_len));
    }
    out->text[text_len] = '\0';

    out->pi = (payload[2] << 8) | payload[3];
    out->pty = payload[1] & 0x1f;
    out->text_len = text_len;
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
