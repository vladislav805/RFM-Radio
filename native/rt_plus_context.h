#pragma once

#include "backend_log.h"
#include "ctl_server.h"
#include "rds_parser.h"

#include <array>
#include <map>
#include <optional>
#include <span>
#include <string>

struct RtPlusFields {
    std::array<std::string, kRdsRtpContentTypeTableSize> values{};
    void write_patch(radio_state_patch_t &patch) const;
};

// A group carries two offsets. Decoded values are indexed by their content type.
class RtPlusContext {
public:
    explicit RtPlusContext(const Logger &log) : log_(log) {}

    void reset();
    void clear_tags();
    bool set_text(const RdsTextPayload &text);
    bool set_text(std::span<const unsigned char> raw_text);
    void set_tags(const RdsRtPlusPayload &payload, bool has_item_flags = false);
    const RtPlusFields &fields() const { return fields_; }

private:
    void decode_tag(const RdsRtPlusTag &tag);
    void clear_item_fields();

    const Logger &log_;
    std::string raw_text_;
    // Offsets received before their RadioText, including a toggle preceding the next text.
    std::map<int, RdsRtPlusTag> pending_tags_;
    // Qualcomm delivers item offsets and updated RT in independent callbacks.
    RdsRtPlusPayload vendor_item_tags_;
    std::optional<bool> item_toggle_;
    bool decoded_item_from_text_ = false;
    bool waiting_for_item_text_ = false;
    RtPlusFields fields_;
};
