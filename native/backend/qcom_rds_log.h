#pragma once

#include "../backend_log.h"
#include "../hex_dump.h"
#include "../rds_parser.h"

// The processed PS/RT headers retain vendor bytes that the text parser masks or skips.
inline void log_qcom_rds_text(
        const Logger &log,
        const char *kind,
        const void *payload,
        const RdsTextPayload &text
) {
    char header[3 * kRdsTextHeaderSize + 1];
    format_hex_dump(static_cast<const char *>(payload), kRdsTextHeaderSize, header, sizeof(header));
    log(
            "rds",
            "%s pi=%04x pty=%d header=[%s] text=`%s` (len=%d)",
            kind,
            text.pi,
            text.pty,
            header,
            text.text,
            text.text_len
    );
}
