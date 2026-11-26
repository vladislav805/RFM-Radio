#pragma once

#include "../../ctl_server.h"
#include "../../rt_plus_context.h"

#include <cstdint>
#include <optional>

class S610RdsDecoder {
public:
    explicit S610RdsDecoder(const Logger &log) : rt_plus_(log) {}

    void reset();

    // Patch strings refer to decoder storage until the next process_block() or reset().
    std::optional<radio_state_patch_t> process_block(uint16_t data, uint8_t block_type, uint8_t errors);

private:
    struct RdsDecoderState {
        uint16_t blocks[4] = {};
        bool have_block[4] = {};
        uint16_t pi = 0;
        char pi_text[5] = {};
        uint16_t pi_candidate = 0;
        int pi_hits = 0;
        bool has_pi = false;
        int pty = -1;
        int pty_candidate = -1;
        int pty_hits = 0;
        unsigned char ps_raw[8] = {};
        uint8_t ps_mask = 0;
        char ps_candidate[64] = {};
        int ps_hits = 0;
        char ps_utf8[64] = {};
        unsigned char rt_raw[64] = {};
        uint16_t rt_mask = 0;
        int rt_end_seg = -1;
        int rt_ab_flag = -1;
        char rt_utf8[256] = {};
        int rtplus_group = -1;
        bool rtplus_group_is_b = false;
        bool rtplus_ert = false;
        RtPlusFields rtplus_fields = {};
        char country[4] = {};
        char country_candidate[4] = {};
        int country_hits = 0;
    };

    bool decode_group(radio_state_patch_t &patch);
    void update_rt_plus_fields(radio_state_patch_t &patch, bool &dirty);

    RdsDecoderState rds_;
    RtPlusContext rt_plus_;
};
