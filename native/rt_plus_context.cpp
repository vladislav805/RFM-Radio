#include "rt_plus_context.h"

#include <algorithm>
#include <string_view>

void RtPlusFields::write_patch(radio_state_patch_t &patch) const {
    for (size_t type = 1; type < values.size(); ++type) {
        patch.rt_plus[type] = values[type].c_str();
    }
}

void RtPlusContext::reset() {
    raw_text_.clear();
    clear_tags();
}

void RtPlusContext::clear_tags() {
    pending_tags_.clear();
    vendor_item_tags_ = {};
    item_toggle_.reset();
    decoded_item_from_text_ = false;
    waiting_for_item_text_ = false;
    fields_ = {};
}

void RtPlusContext::clear_item_fields() {
    // RT+ item classes occupy codes 1 through 11.
    std::fill(fields_.values.begin() + 1, fields_.values.begin() + kRdsRtpLastItemType + 1, "");
}

bool RtPlusContext::set_text(const RdsTextPayload &text) {
    const int length = std::clamp(text.raw_text_len, 0, static_cast<int>(sizeof(text.raw_text)));

    return set_text(std::span(text.raw_text, length));
}

bool RtPlusContext::set_text(std::span<const unsigned char> raw_text) {
    raw_text = raw_text.first(std::min(raw_text.size(), sizeof(RdsTextPayload::raw_text)));

    const std::string_view text = raw_text.empty()
            ? std::string_view{}
            : std::string_view(reinterpret_cast<const char *>(raw_text.data()), raw_text.size());

    if (raw_text_ == text) {
        return false;
    }

    const bool updating_vendor_text = !item_toggle_.has_value() && !raw_text_.empty();
    if (!item_toggle_.has_value()) {
        clear_item_fields();
    }

    raw_text_.assign(text);
    decoded_item_from_text_ = false;
    waiting_for_item_text_ = false;

    if (!raw_text_.empty()) {
        // Qualcomm can send a new RT while retaining the same item offsets.
        if (updating_vendor_text) {
            for (const auto &tag : std::span(vendor_item_tags_.tags, vendor_item_tags_.tag_count)) {
                decode_tag(tag);
            }
        }

        for (const auto &entry : pending_tags_) {
            decode_tag(entry.second);
        }

        pending_tags_.clear();
    }
    return true;
}

void RtPlusContext::set_tags(const RdsRtPlusPayload &payload, bool has_item_flags) {
    if (has_item_flags) {
        const bool changed_item = item_toggle_.has_value() && *item_toggle_ != payload.toggle;

        if (changed_item || !payload.running) {
            clear_item_fields();
            pending_tags_.erase(pending_tags_.begin(), pending_tags_.upper_bound(kRdsRtpLastItemType));
            waiting_for_item_text_ = decoded_item_from_text_;
        }

        item_toggle_ = payload.toggle;
    }

    bool has_tag = false;
    const int count = std::clamp(payload.tag_count, 0, static_cast<int>(std::size(payload.tags)));
    vendor_item_tags_ = {};

    for (const auto &tag : std::span(payload.tags, count)) {
        has_tag |= tag.content_type != kRdsRtpContentTypeUnused;

        if (!is_rt_plus_field(tag.content_type) || tag.start < 0 || tag.length <= 0) {
            continue;
        }

        const bool item = tag.content_type <= kRdsRtpLastItemType;

        if (!has_item_flags && item) {
            vendor_item_tags_.tags[vendor_item_tags_.tag_count++] = tag;
        }

        if (has_item_flags && item && !payload.running) {
            continue;
        }

        if (raw_text_.empty() || (item && waiting_for_item_text_)) {
            pending_tags_[tag.content_type] = tag;
        } else {
            decode_tag(tag);
        }
    }

    if (!has_item_flags && !has_tag) {
        clear_item_fields();
        pending_tags_.clear();
    }
}

void RtPlusContext::decode_tag(const RdsRtPlusTag &tag) {
    if (tag.start > static_cast<int>(raw_text_.size()) - tag.length) {
        log_("rds", "rt+ slice out of range ct=%d start=%d len=%d rt_len=%zu",
                tag.content_type, tag.start, tag.length, raw_text_.size());
        return;
    }

    char text[sizeof(RdsTextPayload::text)];
    convert_ebu_latin_to_utf8(
            reinterpret_cast<const unsigned char *>(raw_text_.data() + tag.start),
            tag.length,
            text,
            sizeof(text)
    );

    std::string &value = fields_.values[tag.content_type];
    value = text;

    if (value.find_first_not_of(' ') == std::string::npos) {
        value.clear();
    }

    if (tag.content_type <= kRdsRtpLastItemType) {
        decoded_item_from_text_ = true;
    }

    log_("rds", "rt+ slice %s=`%s`", get_rds_rt_plus_content_type_name(tag.content_type), text);
}
