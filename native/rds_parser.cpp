#include "rds_parser.h"

#include <algorithm>
#include <cstring>

namespace {

// Shared RDS text payload header: [0]=count/length, [1]=PTY, [2..3]=PI, [4]=vendor/header byte.
constexpr int kMetadataSize = 5;

// Program Service names are carried as fixed 8-byte blocks after the shared header.
constexpr int kPsBlockSize = 8;

// Keep PS bounded to the standard 8-character station name exposed to the app.
constexpr int kMaxPsBlocks = 8;

// Max RDS RT length.
constexpr int kMaxRtTextLen = 64;

// Leave room for the terminating null in RdsTextPayload::text.
constexpr int kMaxOutputTextLen = sizeof(RdsTextPayload::text) - 1;

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
