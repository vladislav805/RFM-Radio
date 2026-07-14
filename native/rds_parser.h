#pragma once

enum class RdsTextPayloadKind {
    kProgramService,
    kRadioText,
};

constexpr int kUnknownRdsPayloadLen = -1;

struct RdsTextPayload {
    int pi = 0;
    int pty = 0;
    int text_len = 0;
    int block_count = 0;
    char text[120] = "";
};

bool parse_rds_text_payload(
        const unsigned char *payload,
        int payload_len,
        RdsTextPayloadKind kind,
        int max_text_len,
        RdsTextPayload *out
);
