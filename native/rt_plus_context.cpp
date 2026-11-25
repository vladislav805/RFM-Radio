#include "rt_plus_context.h"

#include <algorithm>
#include <cstring>
#include <iterator>

namespace {

std::span<const unsigned char> tag_bytes(std::span<const unsigned char> text, const RdsRtPlusTag &tag) {
    if (tag.start < 0 || tag.length <= 0 || tag.start > std::ssize(text) - tag.length) {
        return {};
    }
    return text.subspan(tag.start, tag.length);
}

}  // namespace

void RtPlusContext::reset() {
    raw_length_ = 0;
    set_tags({});
}

void RtPlusContext::set_text(const RdsTextPayload &text) {
    raw_length_ = std::clamp(text.raw_text_len, 0, static_cast<int>(sizeof(raw_text_)));
    std::memcpy(raw_text_, text.raw_text, raw_length_);
}

void RtPlusContext::set_tags(const RdsRtPlusPayload &payload) {
    tags_[0] = payload.tag_count > 0 ? payload.tags[0] : RdsRtPlusTag{};
    tags_[1] = payload.tag_count > 1 ? payload.tags[1] : RdsRtPlusTag{};
}

RdsRtPlusSlices RtPlusContext::slices() const {
    return extract_slices(std::span(raw_text_, raw_length_), tags_);
}

RdsRtPlusSlices RtPlusContext::extract_slices(
        std::span<const unsigned char> raw_text,
        std::span<const RdsRtPlusTag, 2> tags
) {
    RdsRtPlusSlices result;
    for (const auto &tag : tags) {
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
        const auto bytes = tag_bytes(raw_text, tag);
        if (!bytes.empty()) {
            convert_ebu_latin_to_utf8(bytes.data(), tag.length, field.data(), field.size());
        }
    }
    return result;
}

void RtPlusContext::log_slices() const {
    for (const auto &tag : tags_) {
        if (tag.content_type == kRdsRtpContentTypeUnused) {
            continue;
        }
        const auto bytes = tag_bytes(std::span(raw_text_, raw_length_), tag);
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
