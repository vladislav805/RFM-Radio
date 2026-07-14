#pragma once

enum class RdsTextPayloadKind {
    kProgramService,
    kRadioText,
};

constexpr int kUnknownRdsPayloadLen = -1;
constexpr int kMaxRdsAfCount = 25;

struct RdsTextPayload {
    int pi = 0;
    int pty = 0;
    int text_len = 0;
    int block_count = 0;
    char text[120] = "";
};

struct RdsAfList {
    int count = 0;
    int current_frequency_khz = 0;
    int frequencies_khz[kMaxRdsAfCount] = {};
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
