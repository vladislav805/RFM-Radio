#include "s610_rds_decoder.h"

#include "s610_fm_controls.h"
#include "../../rds_parser.h"

#include <cstdio>
#include <cstring>

namespace {

int trim_rds_trailing(const unsigned char *buf, int len) {
    for (int i = 0; i < len; ++i) {
        if (buf[i] == '\r' || buf[i] == '\0') {
            len = i;
            break;
        }
    }
    while (len > 0 && buf[len - 1] == ' ') {
        --len;
    }
    return len;
}

}  // namespace

void S610RdsDecoder::reset() {
    rds_ = RdsDecoderState{};
    rt_plus_.reset();
}

std::optional<radio_state_patch_t> S610RdsDecoder::process_block(
        const uint16_t data,
        const uint8_t block_type,
        const uint8_t errors
) {
    // Reject any block with error-correction bits to avoid false-positive CRC
    // passes on PI, ECC, and text segments, and reset the in-flight group.
    if (errors != 0) {
        std::memset(rds_.have_block, 0, sizeof(rds_.have_block));
        return std::nullopt;
    }

    if (block_type == kS610RdsBlkA) {
        std::memset(rds_.have_block, 0, sizeof(rds_.have_block));
        rds_.blocks[0] = data;
        rds_.have_block[0] = true;
        return std::nullopt;
    }

    if (!rds_.have_block[0]) {
        return std::nullopt;
    }

    if (block_type == kS610RdsBlkB) {
        rds_.blocks[1] = data;
        rds_.have_block[1] = true;
        return std::nullopt;
    }

    if (!rds_.have_block[1]) {
        std::memset(rds_.have_block, 0, sizeof(rds_.have_block));
        return std::nullopt;
    }

    if (block_type == kS610RdsBlkC || block_type == kS610RdsBlkCPrime) {
        rds_.blocks[2] = data;
        rds_.have_block[2] = true;
        return std::nullopt;
    }

    if (block_type == kS610RdsBlkD) {
        rds_.blocks[3] = data;
        rds_.have_block[3] = true;
        radio_state_patch_t patch = radio_state_patch_empty();
        const bool changed = decode_group(patch);
        std::memset(rds_.have_block, 0, sizeof(rds_.have_block));
        if (changed) {
            return patch;
        }
    }
    return std::nullopt;
}

bool S610RdsDecoder::decode_group(radio_state_patch_t &patch) {
    const uint16_t a = rds_.blocks[0];
    const uint16_t b = rds_.blocks[1];
    const int group_type = (b >> 12) & 0x0F;
    const bool version_b = ((b >> 11) & 0x01) != 0;
    const int pty = (b >> 5) & 0x1F;

    char ps_buf[64] = {};
    char rt_buf[256] = {};
    char country_buf[4] = {};
    bool dirty = false;

    if (rds_.pi_candidate == a) {
        ++rds_.pi_hits;
    } else {
        rds_.pi_candidate = a;
        rds_.pi_hits = 1;
    }
    if (rds_.pi_hits >= 2 && (!rds_.has_pi || rds_.pi != a)) {
        rds_.pi = a;
        rds_.has_pi = true;
        rds_.last_tp = -1;
        rds_.last_ta_ms = -1;
        std::snprintf(rds_.pi_text, sizeof(rds_.pi_text), "%04X", a);
        patch.pi = rds_.pi_text;
        dirty = true;
    }

    if (rds_.pty_candidate == pty) {
        ++rds_.pty_hits;
    } else {
        rds_.pty_candidate = pty;
        rds_.pty_hits = 1;
    }
    if (rds_.pty_hits >= 2 && rds_.pty != pty) {
        rds_.pty = pty;
        patch.pty = pty;
        dirty = true;
    }

    // Wait for confirmed PI before trusting PS/RT/ECC so stale or stray blocks
    // from another frequency never poison the current station's state.
    if (!rds_.has_pi || rds_.pi != a) {
        return dirty;
    }

    const int tp = (b >> 10) & 1;
    log_station_flags(b, group_type, version_b, tp);
    if (tp != rds_.last_tp) {
        rds_.last_tp = tp;
        patch.tp = tp;
        dirty = true;
    }

    if (group_type == 0 && rds_.have_block[3]) {
        const int seg = b & 0x03;
        rds_.ps_raw[seg * 2] = static_cast<unsigned char>((rds_.blocks[3] >> 8) & 0xFF);
        rds_.ps_raw[seg * 2 + 1] = static_cast<unsigned char>(rds_.blocks[3] & 0xFF);
        rds_.ps_mask |= static_cast<uint8_t>(1U << seg);

        if (rds_.ps_mask == 0x0F) {
            const int trimmed = trim_rds_trailing(rds_.ps_raw, 8);
            convert_ebu_latin_to_utf8(rds_.ps_raw, trimmed, ps_buf, sizeof(ps_buf));
            // Reset mask after each complete 4-segment cycle so the next window
            // gathers all 4 segments cleanly.
            rds_.ps_mask = 0;

            if (std::strcmp(rds_.ps_candidate, ps_buf) == 0) {
                ++rds_.ps_hits;
            } else {
                std::snprintf(rds_.ps_candidate, sizeof(rds_.ps_candidate), "%s", ps_buf);
                rds_.ps_hits = 1;
            }

            // Require two consecutive identical 4-segment cycles so dynamic PS
            // words spliced mid-cycle (e.g. "106.RD" before "106.3") are suppressed.
            if (rds_.ps_hits >= 2 && std::strcmp(rds_.ps_utf8, ps_buf) != 0) {
                std::snprintf(rds_.ps_utf8, sizeof(rds_.ps_utf8), "%s", ps_buf);
                patch.ps = rds_.ps_utf8;
                dirty = true;
            }
        }
    } else if (group_type == 1 && !version_b && rds_.have_block[2]) {
        const uint16_t c = rds_.blocks[2];
        const int variant = (c >> 12) & 0x07;

        if (variant == 0) {
            const int ecc = static_cast<int>(c & 0xFF);
            const int country_code = (a >> 12) & 0x0F;

            if (decode_rds_country_iso(ecc, country_code, country_buf)) {
                if (std::strcmp(rds_.country_candidate, country_buf) == 0) {
                    ++rds_.country_hits;
                } else {
                    std::snprintf(rds_.country_candidate, sizeof(rds_.country_candidate), "%s", country_buf);
                    rds_.country_hits = 1;
                }

                if (rds_.country_hits >= 2 && std::strcmp(rds_.country, country_buf) != 0) {
                    std::snprintf(rds_.country, sizeof(rds_.country), "%s", country_buf);
                    patch.country = rds_.country;
                    dirty = true;
                }
            }
        }
    } else if (group_type == 2) {
        const int ab_flag = (b >> 4) & 0x01;
        if (rds_.rt_ab_flag != ab_flag) {
            rds_.rt_ab_flag = ab_flag;
            std::memset(rds_.rt_raw, ' ', sizeof(rds_.rt_raw));
            rds_.rt_mask = 0;
            rds_.rt_end_seg = -1;
            rt_plus_.set_text(std::span<const unsigned char>{});
        }

        const int seg = b & 0x0F;
        if (!version_b && rds_.have_block[2] && rds_.have_block[3]) {
            const int base = seg * 4;

            rds_.rt_raw[base] = static_cast<unsigned char>((rds_.blocks[2] >> 8) & 0xFF);
            rds_.rt_raw[base + 1] = static_cast<unsigned char>(rds_.blocks[2] & 0xFF);
            rds_.rt_raw[base + 2] = static_cast<unsigned char>((rds_.blocks[3] >> 8) & 0xFF);
            rds_.rt_raw[base + 3] = static_cast<unsigned char>(rds_.blocks[3] & 0xFF);
            rds_.rt_mask |= static_cast<uint16_t>(1U << seg);

            if (std::memchr(&rds_.rt_raw[base], '\r', 4) != nullptr) {
                if (rds_.rt_end_seg < 0 || seg < rds_.rt_end_seg) {
                    rds_.rt_end_seg = seg;
                }
            } else if (rds_.rt_end_seg == seg) {
                rds_.rt_end_seg = -1;
            }
        } else if (version_b && rds_.have_block[3]) {
            const int base = seg * 2;

            rds_.rt_raw[base] = static_cast<unsigned char>((rds_.blocks[3] >> 8) & 0xFF);
            rds_.rt_raw[base + 1] = static_cast<unsigned char>(rds_.blocks[3] & 0xFF);
            rds_.rt_mask |= static_cast<uint16_t>(1U << seg);

            if (std::memchr(&rds_.rt_raw[base], '\r', 2) != nullptr) {
                if (rds_.rt_end_seg < 0 || seg < rds_.rt_end_seg) {
                    rds_.rt_end_seg = seg;
                }
            } else if (rds_.rt_end_seg == seg) {
                rds_.rt_end_seg = -1;
            }
        }

        const uint16_t needed_mask = rds_.rt_end_seg >= 0
                ? static_cast<uint16_t>((1U << (rds_.rt_end_seg + 1)) - 1U)
                : static_cast<uint16_t>(0xFFFFU);

        if ((rds_.rt_mask & needed_mask) == needed_mask) {
            const int raw_len = version_b ? 32 : 64;
            const int trimmed = trim_rds_trailing(rds_.rt_raw, raw_len);

            convert_ebu_latin_to_utf8(rds_.rt_raw, trimmed, rt_buf, sizeof(rt_buf));

            rds_.rt_mask = 0;
            rds_.rt_end_seg = -1;

            rt_plus_.set_text(std::span(rds_.rt_raw, trimmed));
            std::memset(rds_.rt_raw, ' ', sizeof(rds_.rt_raw));

            if (std::strcmp(rds_.rt_utf8, rt_buf) != 0) {
                std::snprintf(rds_.rt_utf8, sizeof(rds_.rt_utf8), "%s", rt_buf);
                patch.rt = rds_.rt_utf8;
                dirty = true;
            }
            update_rt_plus_fields(patch, dirty);
        }
    } else if (
            group_type == 3 &&
            !version_b &&
            rds_.have_block[2] &&
            rds_.have_block[3]
    ) {
        constexpr uint16_t kAidRtPlus = 0x4BD7;
        const uint16_t aid = rds_.blocks[3];

        if (aid == kAidRtPlus) {
            const uint16_t c = rds_.blocks[2];
            const int oda_group = (b >> 1) & 0x0F;
            const bool oda_is_b = (b & 0x01) != 0;

            // RT+ ODA requires a Type A group (Blocks C and D carry the two 18-bit tag descriptors).
            if (!oda_is_b && oda_group >= 3) {
                rds_.rtplus_group = oda_group;
                rds_.rtplus_group_is_b = false;
                const bool ert = ((c >> 13) & 0x01) != 0;

                if (ert != rds_.rtplus_ert) {
                    rds_.rtplus_ert = ert;
                    rt_plus_.clear_tags();
                    update_rt_plus_fields(patch, dirty);
                }
            }
        }
    } else if (
            rds_.rtplus_group >= 0 &&
            group_type == rds_.rtplus_group &&
            version_b == rds_.rtplus_group_is_b &&
            rds_.have_block[2] &&
            rds_.have_block[3]
    ) {
        const uint16_t c = rds_.blocks[2];
        const uint16_t d = rds_.blocks[3];
        const int type1 = ((b & 0x07) << 3) | ((c >> 13) & 0x07);
        const int start1 = (c >> 7) & 0x3F;
        const int len1 = ((c >> 1) & 0x3F) + 1;
        const int type2 = ((c & 0x01) << 5) | ((d >> 11) & 0x1F);
        const int start2 = (d >> 5) & 0x3F;
        const int len2 = (d & 0x1F) + 1;

        if (rds_.rtplus_ert) {
            return dirty;
        }

        rt_plus_.set_tags({
                .toggle = (b & 0x10) != 0,
                .running = (b & 0x08) != 0,
                .tags = {{type1, start1, len1}, {type2, start2, len2}},
                .tag_count = 2
        }, true);

        update_rt_plus_fields(patch, dirty);
    }

    return dirty;
}

void S610RdsDecoder::log_station_flags(uint16_t b, int group_type, bool version_b, int tp) {
    const char version = version_b ? 'B' : 'A';
    if (group_type == 0 || (group_type == 15 && version_b)) {
        // TA and legacy M/S occupy adjacent bits in these groups.
        const int ta_ms = (b >> 3) & 3;
        if (tp != rds_.last_tp || ta_ms != rds_.last_ta_ms) {
            log_(
                    "rds",
                    "flags pi=%04x group=%d%c B=%04x TP=%d TA=%d MS=%d",
                    rds_.pi,
                    group_type,
                    version,
                    b,
                    tp,
                    ta_ms >> 1,
                    ta_ms & 1
            );
            rds_.last_ta_ms = ta_ms;
        }
    } else if (tp != rds_.last_tp) {
        log_(
                "rds",
                "flags pi=%04x group=%d%c B=%04x TP=%d",
                rds_.pi,
                group_type,
                version,
                b,
                tp
        );
    }
}

void S610RdsDecoder::update_rt_plus_fields(radio_state_patch_t &patch, bool &dirty) {
    const auto &values = rt_plus_.fields().values;

    for (size_t type = 1; type < values.size(); ++type) {
        auto &published = rds_.rtplus_fields.values[type];

        if (published != values[type]) {
            published = values[type];
            patch.rt_plus[type] = published.c_str();
            dirty = true;
        }
    }
}
