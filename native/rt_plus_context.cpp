#include "rt_plus_context.h"

#include <algorithm>
#include <iterator>

std::span<const unsigned char> RtPlusContext::tag_bytes(const RdsRtPlusTag &tag) const {
    if (tag.start < 0 || tag.length <= 0 || tag.start > raw_length_ - tag.length) {
        return {};
    }
    return std::span(raw_text_ + tag.start, tag.length);
}

void RtPlusContext::reset() {
    raw_length_ = 0;
    tag_count_ = 0;
}

void RtPlusContext::set_text(const RdsTextPayload &text) {
    const int length = std::clamp(text.raw_text_len, 0, static_cast<int>(sizeof(text.raw_text)));
    set_text(std::span(text.raw_text, length));
}

void RtPlusContext::set_text(std::span<const unsigned char> raw_text) {
    raw_length_ = static_cast<int>(std::min(raw_text.size(), sizeof(raw_text_)));
    std::copy_n(raw_text.begin(), raw_length_, raw_text_);
}

void RtPlusContext::set_tags(const RdsRtPlusPayload &payload) {
    tag_count_ = std::clamp(payload.tag_count, 0, static_cast<int>(std::size(tags_)));
    std::copy_n(payload.tags, tag_count_, tags_);
}

RdsRtPlusSlices RtPlusContext::slices() const {
    RdsRtPlusSlices result;
    for (const auto &tag : std::span(tags_, tag_count_)) {
        std::span<char> field;
        switch (tag.content_type) {
            case kRdsRtpContentTypeTitle:
                field = result.title;
                break;
            case kRdsRtpContentTypeArtist:
                field = result.artist;
                break;
            case kRdsRtpContentTypeAlbum:
                field = result.album;
                break;
            default:
                continue;
        }
        const auto bytes = tag_bytes(tag);
        if (!bytes.empty()) {
            convert_ebu_latin_to_utf8(bytes.data(), tag.length, field.data(), field.size());
        }
    }
    return result;
}

void RtPlusContext::log_slices() const {
    for (const auto &tag : std::span(tags_, tag_count_)) {
        if (tag.content_type == kRdsRtpContentTypeUnused) {
            continue;
        }
        const auto bytes = tag_bytes(tag);
        if (bytes.empty()) {
            log_(
                    "rds",
                    "rt+ slice out of range ct=%d start=%d len=%d rt_len=%d",
                    tag.content_type,
                    tag.start,
                    tag.length,
                    raw_length_
            );
            continue;
        }
        char text[sizeof(RdsTextPayload::text)];
        convert_ebu_latin_to_utf8(bytes.data(), tag.length, text, sizeof(text));
        log_(
                "rds",
                "rt+ slice %s=`%s`",
                get_rds_rt_plus_content_type_name(tag.content_type),
                text
        );
    }
}
