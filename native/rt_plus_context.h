#pragma once

#include "backend_log.h"
#include "rds_parser.h"

#include <span>

struct RdsRtPlusSlices {
    char title[256] = "";
    char artist[256] = "";
    char album[256] = "";
};

// RadioText and RT+ tags arrive in separate events, in either order.
class RtPlusContext {
public:
    explicit RtPlusContext(const Logger &log) : log_(log) {}

    void reset();
    void set_text(const RdsTextPayload &text);
    void set_text(std::span<const unsigned char> raw_text);
    void set_tags(const RdsRtPlusPayload &payload);
    bool has_text_and_tags() const { return raw_length_ > 0 && tag_count_ > 0; }
    RdsRtPlusSlices slices() const;
    void log_slices() const;

private:
    std::span<const unsigned char> tag_bytes(const RdsRtPlusTag &tag) const;

    const Logger &log_;
    unsigned char raw_text_[sizeof(RdsTextPayload::raw_text)] = {};
    int raw_length_ = 0;
    RdsRtPlusTag tags_[2] = {};
    int tag_count_ = 0;
};
