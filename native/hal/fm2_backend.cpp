#include "fm2_backend.h"

#include <dlfcn.h>
#include <errno.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <algorithm>

#include "../ctl_server.h"
#include "../fm_v4l2_controls.h"
#include "../rds_parser.h"
#include "fm2_vendor_iface.h"
#include "utils.h"

namespace {

constexpr const char *kFmLibraryName = "fm_helium.so";
constexpr const char *kFmLibrarySymbol = "FM_HELIUM_LIB_INTERFACE";
constexpr int kFmRxState = 1;
constexpr int kSearchModeSeek = 0;
constexpr int kSearchModeScan = 1;
constexpr int kSearchModeStrongList = 2;
constexpr int kDefaultListSize = 20;
constexpr int kMaxScanStations = 64;
constexpr int kEnableWaitTimeoutMs = 2000;

// FM_RX_RSSI_LEVEL_VERY_WEAK   = -105;
// FM_RX_RSSI_LEVEL_WEAK        = -100;
// FM_RX_RSSI_LEVEL_STRONG      = -96;
// FM_RX_RSSI_LEVEL_VERY_STRONG = -90;
// set_ctrl(V4L2_CID_PRIVATE_IRIS_SIGNAL_TH, kDefaultSignalThreshold - 105)
constexpr int kDefaultSignalThreshold = 0x40;
// sweeping across the entire frequency range, it stops at each station found and pauses for N seconds
constexpr int kDefaultSeekDwell = 2;
constexpr int kDefaultRdsGroupProcMask = 0xEF;
constexpr int kDefaultRawRdsGroupMask = 40; // 64;

struct RtpTag {
    int content_type = 0;
    int start = 0;
    int length = 0;
};

struct AudioState {
    bool stereo = true;
    bool mode_valid = false;
    bool mode_stereo = true;
    bool slimbus_enabled = false;
};

struct ScanState {
    bool active = false;
    bool wrapped = false;
    int start_frequency_khz = 0;
    int last_frequency_khz = 0;
    int found[kMaxScanStations] = {};
    int found_count = 0;
};

struct RuntimeState {
    void *lib_handle = nullptr;
    fm_interface_t *vendor = nullptr;
    bool initialized = false;
    bool enabled = false;
    bool rds_enabled = true;
    AudioState audio;
    bool low_power = false;
    bool auto_af = false;
    int antenna = 0;
    int app_band = 1;
    int vendor_region = 0;
    int lower_band_khz = 87500;
    int upper_band_khz = 108000;
    int app_spacing = 2;
    int vendor_spacing = 1;
    int current_frequency_khz = 87500;
    uint64_t last_enabled_cb_ms = 0;
    uint64_t last_tune_cb_ms = 0;
    uint64_t last_seek_cb_ms = 0;
    bool last_search_payload_valid = false;
    char last_search_payload[5 * 20 + 1] = "";
    ScanState scan;
    bool last_rt_plus_valid = false;
    unsigned char last_rt_plus[15] = {};
    bool last_ecc_valid = false;
    unsigned char last_ecc[12] = {};
    char current_rt[65] = "";
    int current_rt_len = 0;
    bool rt_plus_tags_valid = false;
    RtpTag rt_plus_tags[2];
    char last_error[128] = "";
    pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
    pthread_cond_t cond = PTHREAD_COND_INITIALIZER;
} g_state;

void set_error_locked(const char *message) {
    const char *safe_message = message ? message : "unknown error";
    hal_log("error", "%s", safe_message);
    snprintf(g_state.last_error, sizeof(g_state.last_error), "%s", safe_message);
}

void clear_error_locked() {
    g_state.last_error[0] = '\0';
}

void reset_search_result_locked() {
    g_state.last_search_payload_valid = false;
    g_state.last_search_payload[0] = '\0';
}

void reset_scan_locked() {
    g_state.scan.active = false;
    g_state.scan.wrapped = false;
    g_state.scan.start_frequency_khz = 0;
    g_state.scan.last_frequency_khz = 0;
    g_state.scan.found_count = 0;
}

void add_scan_frequency_locked(int freq) {
    if (freq <= 0) {
        return;
    }

    for (int i = 0; i < g_state.scan.found_count; ++i) {
        if (g_state.scan.found[i] == freq) {
            return;
        }
    }

    if (g_state.scan.found_count >= kMaxScanStations) {
        hal_log("search", "scan result full, dropping frequency=%d", freq);
        return;
    }

    g_state.scan.found[g_state.scan.found_count++] = freq;
}

void reset_rds_dedup_locked() {
    g_state.last_rt_plus_valid = false;
    g_state.last_ecc_valid = false;
    g_state.current_rt[0] = '\0';
    g_state.current_rt_len = 0;
    g_state.rt_plus_tags_valid = false;
}

bool is_duplicate_payload_locked(bool *valid, unsigned char *last, size_t last_size, const char *payload, int len) {
    if (len <= 0 || static_cast<size_t>(len) > last_size) {
        *valid = false;
        return false;
    }

    const bool duplicate = *valid && memcmp(last, payload, static_cast<size_t>(len)) == 0;
    if (!duplicate) {
        memcpy(last, payload, static_cast<size_t>(len));
        *valid = true;
    }
    return duplicate;
}

void log_length_prefixed_payload(const char *scope, const char *name, const char *payload, int extra_len = 0) {
    if (payload == nullptr) {
        hal_log(scope, "%s got null", name);
        return;
    }

    const int len = static_cast<unsigned char>(payload[0]) + extra_len;
    char dump[3 * 32 + 1];
    format_hex_dump(payload, len, dump, sizeof(dump));
    hal_log(scope, "%s len=%d data=%s%s", name, len, dump, len > 32 ? " ..." : "");
}

const char *rtp_content_type_name(int content_type) {
    switch (content_type) {
        case 1:
            return "ITEM.TITLE";
        case 4:
            return "ITEM.ARTIST";
        case 12:
            return "INFO.NEWS";
        case 28:
            return "INFO.ADVERT";
        case 31:
            return "STATIONNAME.SHORT";
        case 32:
            return "STATIONNAME.LONG";
        case 33:
            return "PROGRAMME.NOW";
        default:
            return "UNKNOWN";
    }
}

void log_rt_plus_slices_locked() {
    if (!g_state.rt_plus_tags_valid || g_state.current_rt_len <= 0) {
        return;
    }

    for (const RtpTag &tag : g_state.rt_plus_tags) {
        if (tag.content_type == 0) {
            continue;
        }
        if (tag.start < 0 || tag.length <= 0 || tag.start + tag.length > g_state.current_rt_len) {
            hal_log("rds", "rt+ slice out of range ct=%d start=%d len=%d rt_len=%d",
                    tag.content_type, tag.start, tag.length, g_state.current_rt_len);
            continue;
        }

        char text[65];
        memcpy(text, g_state.current_rt + tag.start, static_cast<size_t>(tag.length));
        text[tag.length] = '\0';
        hal_log("rds", "rt+ slice %s=`%s`", rtp_content_type_name(tag.content_type), text);
    }
}

uint64_t now_ms() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<uint64_t>(ts.tv_sec) * 1000ULL +
           static_cast<uint64_t>(ts.tv_nsec / 1000000ULL);
}

bool vendor_set(int id, int value, const char *message) {
    pthread_mutex_lock(&g_state.lock);
    if (g_state.vendor == nullptr) {
        set_error_locked("vendor interface is null");
        pthread_mutex_unlock(&g_state.lock);
        return false;
    }

    errno = 0;
    const int ret = g_state.vendor->set_fm_ctrl(id, value);
    if (ret < 0) {
        const int saved_errno = errno;
        set_error_locked(message);
        hal_log("vendor", "set id=0x%x value=%d failed, ret=%d errno=%d (%s)",
                id, value, ret, saved_errno, saved_errno ? strerror(saved_errno) : "none");
        pthread_mutex_unlock(&g_state.lock);
        return false;
    }

    clear_error_locked();
    pthread_mutex_unlock(&g_state.lock);
    return true;
}

bool vendor_get(int id, int *value, const char *message) {
    pthread_mutex_lock(&g_state.lock);
    if (g_state.vendor == nullptr) {
        set_error_locked("vendor interface is null");
        pthread_mutex_unlock(&g_state.lock);
        return false;
    }

    errno = 0;
    const int ret = g_state.vendor->get_fm_ctrl(id, value);
    if (ret < 0) {
        const int saved_errno = errno;
        set_error_locked(message);
        hal_log("vendor", "get id=0x%x failed, ret=%d errno=%d (%s)",
                id, ret, saved_errno, saved_errno ? strerror(saved_errno) : "none");
        pthread_mutex_unlock(&g_state.lock);
        return false;
    }

    clear_error_locked();
    pthread_mutex_unlock(&g_state.lock);
    return true;
}

bool apply_signal_threshold() {
    return vendor_set(kV4l2CtrlSignalThreshold, kDefaultSignalThreshold, "failed to set signal threshold");
}

bool apply_raw_rds_group_mask() {
    return vendor_set(kV4l2CtrlRdsGroupMask, kDefaultRawRdsGroupMask, "failed to set raw rds group mask");
}

bool apply_processed_rds_config(bool auto_af) {
    if (!vendor_set(kV4l2CtrlRdsOn, 1, "failed to set rds on")) {
        return false;
    }
    if (!vendor_set(kV4l2CtrlRdsGroupProc, kDefaultRdsGroupProcMask, "failed to set rds groups")) {
        vendor_set(kV4l2CtrlRdsOn, 0, "failed to roll back rds on");
        return false;
    }
    if (!vendor_set(kV4l2CtrlAfJump, auto_af ? 1 : 0, "failed to set af jump")) {
        vendor_set(kV4l2CtrlRdsGroupProc, 0, "failed to roll back rds groups");
        vendor_set(kV4l2CtrlRdsOn, 0, "failed to roll back rds on");
        return false;
    }

    return true;
}

bool disable_processed_rds_config() {
    bool ok = true;
    ok &= vendor_set(kV4l2CtrlAfJump, 0, "failed to clear af jump");
    ok &= vendor_set(kV4l2CtrlRdsGroupProc, 0, "failed to clear rds groups");
    ok &= vendor_set(kV4l2CtrlRdsOn, 0, "failed to disable rds");
    return ok;
}

bool apply_post_enable_config() {
    bool rds_enabled = false;
    bool auto_af = false;
    bool low_power = false;

    pthread_mutex_lock(&g_state.lock);
    rds_enabled = g_state.rds_enabled;
    auto_af = g_state.auto_af;
    low_power = g_state.low_power;
    pthread_mutex_unlock(&g_state.lock);

    if (!apply_raw_rds_group_mask()) {
        return false;
    }

    if (!vendor_set(kV4l2CtrlLowPowerMode, low_power ? 1 : 0, "failed to set power mode")) {
        return false;
    }

    if (rds_enabled) {
        return apply_processed_rds_config(auto_af);
    }

    return disable_processed_rds_config();
}

int current_frequency_locked() {
    return g_state.current_frequency_khz;
}

int map_app_spacing_to_vendor(int app_spacing) {
    switch (app_spacing) {
        case 1:
            return 2;
        case 3:
            return 0;
        case 2:
        default:
            return 1;
    }
}

void apply_band_config_locked(int app_region) {
    g_state.app_band = app_region;
    switch (app_region) {
        case 2:
            g_state.vendor_region = 2;
            g_state.lower_band_khz = 76000;
            g_state.upper_band_khz = 95000;
            break;
        case 3:
            g_state.vendor_region = 3;
            g_state.lower_band_khz = 76000;
            g_state.upper_band_khz = 108000;
            break;
        case 1:
        default:
            g_state.vendor_region = 4;
            g_state.lower_band_khz = 87500;
            g_state.upper_band_khz = 108000;
            break;
    }
}

int spacing_step_khz_locked() {
    switch (g_state.app_spacing) {
        case 1:
            return 50;
        case 3:
            return 200;
        case 2:
        default:
            return 100;
    }
}

void send_frequency_event(int event_id, int frequency_khz) {
    char buf[32];
    snprintf(buf, sizeof(buf), "%d", frequency_khz);
    send_interruption_info(event_id, buf);
}

void send_string_event(int event_id, const char *value) {
    send_interruption_info(event_id, value == nullptr ? "" : value);
}

void send_int_event(int event_id, int value) {
    char buf[32];
    snprintf(buf, sizeof(buf), "%d", value);
    send_interruption_info(event_id, buf);
}

void send_hex_event(int event_id, int value) {
    char buf[32];
    snprintf(buf, sizeof(buf), "%x", value);
    send_interruption_info(event_id, buf);
}

void log_scan_next_cb() {
    int freq = 0;
    if (vendor_get(kV4l2CtrlIrisFreq, &freq, "failed to read scan-next frequency")) {
        hal_log("search", "scan next frequency=%d", freq);
    } else {
        hal_log("search", "scan next");
        return;
    }

    int found[kMaxScanStations];
    int found_count = 0;
    bool complete = false;

    pthread_mutex_lock(&g_state.lock);
    if (g_state.scan.active) {
        add_scan_frequency_locked(freq);
        if (g_state.scan.last_frequency_khz > 0 && freq < g_state.scan.last_frequency_khz) {
            g_state.scan.wrapped = true;
        }
        g_state.scan.last_frequency_khz = freq;

        if (g_state.scan.wrapped && freq >= g_state.scan.start_frequency_khz) {
            complete = true;
            g_state.scan.active = false;
            found_count = g_state.scan.found_count;
            memcpy(found, g_state.scan.found, static_cast<size_t>(found_count) * sizeof(found[0]));
        }
    }
    pthread_mutex_unlock(&g_state.lock);

    if (!complete) {
        return;
    }

    vendor_set(kV4l2CtrlSearchOn, 0, "failed to stop completed scan");
    std::sort(found, found + found_count);

    char payload[5 * kMaxScanStations + 1];
    payload[0] = '\0';
    char frequencies[7 * kMaxScanStations + 1];
    frequencies[0] = '\0';
    for (int i = 0; i < found_count; ++i) {
        char chunk[8];
        snprintf(chunk, sizeof(chunk), "%04d", found[i] / 100);
        strncat(payload, chunk, sizeof(payload) - strlen(payload) - 1);

        char frequency_chunk[8];
        snprintf(frequency_chunk, sizeof(frequency_chunk), "%s%d", i == 0 ? "" : " ", found[i]);
        strncat(frequencies, frequency_chunk, sizeof(frequencies) - strlen(frequencies) - 1);
    }

    hal_log("search", "scan complete count=%d frequencies=%s", found_count, frequencies);
    send_string_event(EVT_SEARCH_DONE, payload);
}

void log_thread_evt_cb(unsigned int evt) {
    hal_log("event", "thread event=%u", evt);
}

void oda_update_cb() {
    hal_log("rds", "oda update");
}

/*
 * RT+: [0]=payload length, [1]=PTY, [2..3]=PI, [4..7]=flags/toggle,
 *      [8..10]=tag 1 (content type, start, length), [11..13]=tag 2, [14]=padding.
 *
 * RT+ tag length is encoded as length-1.
 * Example:
 * RT="Bravo;Valeriy Syutkin - Stil'nyy oranjevyy galstuk"
 * RT+ raw=0f 07 77 56 01 0c 00 01 01 18 19 04 00 14 00
 * tag1 TITLE=(ct=1,start=24,len=25) -> 26 chars
 * tag2 ARTIST=(ct=4,start=0,len=20) -> 21 chars.
 * Byte [5] is the ODA group number (for example 0x0c = 12A); byte [14] can contain stale padding.
 */
void rt_plus_update_cb(char *payload) {
    if (payload == nullptr) {
        hal_log("rds", "rt_plus_update_cb got null");
        return;
    }

    const int len = static_cast<unsigned char>(payload[0]);
    pthread_mutex_lock(&g_state.lock);
    const bool duplicate = is_duplicate_payload_locked(
            &g_state.last_rt_plus_valid, g_state.last_rt_plus, sizeof(g_state.last_rt_plus), payload, len);
    pthread_mutex_unlock(&g_state.lock);
    if (duplicate) {
        return;
    }

    char dump[3 * 32 + 1];
    format_hex_dump(payload, len, dump, sizeof(dump));

    if (len >= 15) {
        const int pty = static_cast<unsigned char>(payload[1]) & 0x1f;
        const int pi = (static_cast<unsigned char>(payload[2]) << 8) | static_cast<unsigned char>(payload[3]);
        const int flags = (static_cast<unsigned char>(payload[4]) << 24) |
                (static_cast<unsigned char>(payload[5]) << 16) |
                (static_cast<unsigned char>(payload[6]) << 8) |
                static_cast<unsigned char>(payload[7]);
        const int tag1_type = static_cast<unsigned char>(payload[8]);
        const int tag1_start = static_cast<unsigned char>(payload[9]);
        const int tag1_len = static_cast<unsigned char>(payload[10]);
        const int tag2_type = static_cast<unsigned char>(payload[11]);
        const int tag2_start = static_cast<unsigned char>(payload[12]);
        const int tag2_len = static_cast<unsigned char>(payload[13]);

        pthread_mutex_lock(&g_state.lock);
        g_state.rt_plus_tags[0] = {tag1_type, tag1_start, tag1_type == 0 ? 0 : tag1_len + 1};
        g_state.rt_plus_tags[1] = {tag2_type, tag2_start, tag2_type == 0 ? 0 : tag2_len + 1};
        g_state.rt_plus_tags_valid = true;
        log_rt_plus_slices_locked();
        pthread_mutex_unlock(&g_state.lock);

        hal_log("rds", "rt+ len=%d pi=0x%04x pty=0x%02x flags=0x%08x tag1=(ct=%d,start=%d,len=%d) tag2=(ct=%d,start=%d,len=%d) data=%s%s",
                len, pi, pty, flags,
                tag1_type, tag1_start, tag1_type == 0 ? 0 : tag1_len + 1,
                tag2_type, tag2_start, tag2_type == 0 ? 0 : tag2_len + 1,
                dump, len > 32 ? " ..." : "");
        return;
    }

    if (len >= 4) {
        const int pty = static_cast<unsigned char>(payload[1]) & 0x1f;
        const int pi = (static_cast<unsigned char>(payload[2]) << 8) | static_cast<unsigned char>(payload[3]);
        hal_log("rds", "rt+ len=%d pi=0x%04x pty=0x%02x data=%s%s",
                len, pi, pty, dump, len > 32 ? " ..." : "");
        return;
    }

    hal_log("rds", "rt+ len=%d data=%s%s", len, dump, len > 32 ? " ..." : "");
}

void ert_update_cb(char *payload) {
    log_length_prefixed_payload("rds", "ert_update_cb", payload, 3);
}

void rds_grp_cntrs_rsp_cb(char *payload) {
    log_length_prefixed_payload("rds", "rds_grp_cntrs_rsp_cb", payload);
}

void rds_grp_cntrs_ext_rsp_cb(char *payload) {
    log_length_prefixed_payload("rds", "rds_grp_cntrs_ext_rsp_cb", payload);
}

void fm_peek_rsp_cb(char *payload) {
    log_length_prefixed_payload("vendor", "fm_peek_rsp_cb", payload);
}

void fm_ssbi_peek_rsp_cb(char *payload) {
    log_length_prefixed_payload("vendor", "fm_ssbi_peek_rsp_cb", payload);
}

void fm_agc_gain_rsp_cb(char *payload) {
    log_length_prefixed_payload("vendor", "fm_agc_gain_rsp_cb", payload);
}

void fm_ch_det_th_rsp_cb(char *payload) {
    log_length_prefixed_payload("vendor", "fm_ch_det_th_rsp_cb", payload);
}

/*
 * ECC callback carries RDS group 1A:
 * [0]=12
 * [1]=PTY
 * [2..3]=PI
 * [4..5]=variant
 * [6..7]=flags/reserved
 * [8..9]=slow labelling
 * [10..11]=block D.
 *
 * The slow-labelling low byte is ECC only when variant == 0. Other variants
 * are broadcaster/vendor data and are ignored to avoid log spam.
 */
void ext_country_code_cb(char *payload) {
    if (payload == nullptr) {
        hal_log("rds", "ext_country_code_cb got null");
        return;
    }

    const int len = static_cast<unsigned char>(payload[0]);
    pthread_mutex_lock(&g_state.lock);
    const bool duplicate = is_duplicate_payload_locked(
            &g_state.last_ecc_valid, g_state.last_ecc, sizeof(g_state.last_ecc), payload, len);
    pthread_mutex_unlock(&g_state.lock);
    if (duplicate) {
        return;
    }

    char dump[3 * 32 + 1];
    format_hex_dump(payload, len, dump, sizeof(dump));

    if (len >= 12) {
        const int pty = static_cast<unsigned char>(payload[1]) & 0x1f;
        const int pi = (static_cast<unsigned char>(payload[2]) << 8) | static_cast<unsigned char>(payload[3]);
        const int variant = (static_cast<unsigned char>(payload[4]) << 8) | static_cast<unsigned char>(payload[5]);
        const int slow_labelling =
                (static_cast<unsigned char>(payload[8]) << 8) | static_cast<unsigned char>(payload[9]);
        const int block_d = (static_cast<unsigned char>(payload[10]) << 8) | static_cast<unsigned char>(payload[11]);

        if (variant == 0) {
            const int country_code = (pi >> 12) & 0x0f;
            const int ecc = slow_labelling & 0xff;
            hal_log("rds", "ecc len=%d pi=0x%04x pty=0x%02x cc=0x%x ecc=0x%02x sl=0x%04x block_d=0x%04x data=%s%s",
                    len, pi, pty, country_code, ecc, slow_labelling, block_d, dump, len > 32 ? " ..." : "");
            return;
        }

        return;
    }

    hal_log("rds", "ecc len=%d data=%s%s", len, dump, len > 32 ? " ..." : "");
}

void fm_get_sig_thres_cb(int value, int status) {
    hal_log("vendor", "fm_get_sig_thres_cb value=%d status=%d", value, status);
}

void fm_get_ch_det_thr_cb(int value, int status) {
    hal_log("vendor", "fm_get_ch_det_thr_cb value=%d status=%d", value, status);
}

void fm_def_data_read_cb(int value, int status) {
    hal_log("vendor", "fm_def_data_read_cb value=%d status=%d", value, status);
}

void fm_get_blend_cb(int value, int status) {
    hal_log("vendor", "fm_get_blend_cb value=%d status=%d", value, status);
}

void fm_set_ch_det_thr_cb(int status) {
    hal_log("vendor", "fm_set_ch_det_thr_cb status=%d", status);
}

void fm_def_data_write_cb(int status) {
    hal_log("vendor", "fm_def_data_write_cb status=%d", status);
}

void fm_set_blend_cb(int status) {
    hal_log("vendor", "fm_set_blend_cb status=%d", status);
}

void fm_get_station_param_cb(int value, int status) {
    hal_log("vendor", "fm_get_station_param_cb value=%d status=%d", value, status);
}

void fm_get_station_debug_param_cb(int value, int status) {
    hal_log("vendor", "fm_get_station_debug_param_cb value=%d status=%d", value, status);
}

void enable_softmute_cb(int status) {
    hal_log("audio", "softmute status=%d", status);
}

void rds_avail_status_cb(bool rds_available) {
    hal_log("event", "RDS available=%d", rds_available ? 1 : 0);
}

void enable_slimbus_cb(int status) {
    hal_log("event", "slimbus status=%d", status);
    pthread_mutex_lock(&g_state.lock);
    g_state.audio.slimbus_enabled = status == 0;
    pthread_mutex_unlock(&g_state.lock);
}

void enabled_cb() {
    hal_log("event", "FM enabled");
    pthread_mutex_lock(&g_state.lock);
    g_state.enabled = true;
    g_state.last_enabled_cb_ms = now_ms();
    pthread_cond_broadcast(&g_state.cond);
    pthread_mutex_unlock(&g_state.lock);
    send_string_event(EVT_ENABLED, "enabled");
}

void disabled_cb() {
    hal_log("event", "FM disabled");
    pthread_mutex_lock(&g_state.lock);
    g_state.enabled = false;
    g_state.audio.mode_valid = false;
    pthread_cond_broadcast(&g_state.cond);
    pthread_mutex_unlock(&g_state.lock);
    send_string_event(EVT_DISABLED, "disabled");
}

void tune_cb(int freq) {
    hal_log("event", "tuned frequency=%d", freq);
    pthread_mutex_lock(&g_state.lock);
    g_state.current_frequency_khz = freq;
    g_state.last_tune_cb_ms = now_ms();
    reset_search_result_locked();
    reset_rds_dedup_locked();
    pthread_mutex_unlock(&g_state.lock);
    send_string_event(EVT_UPDATE_PS, "");
    send_string_event(EVT_UPDATE_RT, "");
    send_int_event(EVT_UPDATE_PTY, 0);
    send_string_event(EVT_UPDATE_PI, "");
    send_frequency_event(EVT_FREQUENCY_SET, freq);
}

void seek_complete_cb(int freq) {
    int current = 0;
    if (vendor_get(kV4l2CtrlIrisFreq, &current, "failed to read seek-complete frequency")) {
        hal_log("event", "seek complete frequency=%d current=%d", freq, current);
    } else {
        hal_log("event", "seek complete frequency=%d", freq);
    }
    pthread_mutex_lock(&g_state.lock);
    g_state.current_frequency_khz = freq;
    g_state.last_seek_cb_ms = now_ms();
    reset_search_result_locked();
    reset_rds_dedup_locked();
    pthread_mutex_unlock(&g_state.lock);
    send_string_event(EVT_UPDATE_PS, "");
    send_string_event(EVT_UPDATE_RT, "");
    send_int_event(EVT_UPDATE_PTY, 0);
    send_string_event(EVT_UPDATE_PI, "");
    send_frequency_event(EVT_SEEK_COMPLETE, freq);
}

void stereo_status_cb(bool stereo) {
    hal_log("event", "stereo=%d", stereo ? 1 : 0);
    pthread_mutex_lock(&g_state.lock);
    g_state.audio.stereo = stereo;
    pthread_mutex_unlock(&g_state.lock);
    send_string_event(EVT_STEREO, stereo ? "1" : "0");
}

/*
 * PS:
 * [0]=PS block count
 * [1]=PTY
 * [2..3]=PI
 * [4]=header,
 * [5..]=one or more 8-byte Program Service name blocks.
 *
 * Qualcomm HAL keeps PTY/PI in the same buffer, so we can update all three app fields here.
 */
void ps_update_cb(char *ps) {
    if (ps == nullptr) {
        hal_log("rds", "ps update got null");
        return;
    }

    RdsTextPayload parsed;
    if (!parse_rds_text_payload(
            reinterpret_cast<const unsigned char *>(ps),
            kUnknownRdsPayloadLen,
            RdsTextPayloadKind::kProgramService,
            sizeof(parsed.text) - 1,
            &parsed
    )) {
        hal_log("rds", "invalid ps count=%d", static_cast<unsigned char>(ps[0]) & 0x0f);
        return;
    }

    hal_log("rds", "ps count=%d pi=%04x pty=0x%02x text=`%s`", parsed.block_count, parsed.pi, parsed.pty, parsed.text);
    send_string_event(EVT_UPDATE_PS, parsed.text);
    send_int_event(EVT_UPDATE_PTY, parsed.pty);
    send_hex_event(EVT_UPDATE_PI, parsed.pi);
}

/*
 * RT:
 * [0]=text length
 * [1..4]=metadata/header
 * [5..]=RadioText.
 *
 * The HAL-provided length is clamped to the RDS 64-character RT limit before copying.
 */
void rt_update_cb(char *rt) {
    if (rt == nullptr) {
        hal_log("rds", "rt update got null");
        return;
    }

    RdsTextPayload parsed;
    if (!parse_rds_text_payload(
            reinterpret_cast<const unsigned char *>(rt),
            kUnknownRdsPayloadLen,
            RdsTextPayloadKind::kRadioText,
            sizeof(parsed.text) - 1,
            &parsed
    ) || parsed.text_len == 0) {
        hal_log("rds", "invalid rt len=%d", static_cast<unsigned char>(rt[0]) & 0x7f);
        return;
    }

    int text_len = parsed.text_len;
    for (int i = 0; i < text_len; ++i) {
        if (parsed.text[i] == '\r') {
            text_len = i;
            parsed.text[text_len] = '\0';
            break;
        }
    }
    while (text_len > 0 && parsed.text[text_len - 1] == ' ') {
        --text_len;
        parsed.text[text_len] = '\0';
    }

    pthread_mutex_lock(&g_state.lock);
    snprintf(g_state.current_rt, sizeof(g_state.current_rt), "%s", parsed.text);
    g_state.current_rt_len = text_len;
    log_rt_plus_slices_locked();
    pthread_mutex_unlock(&g_state.lock);

    hal_log("rds", "rt=`%s` (len=%d)", parsed.text, text_len);
    send_string_event(EVT_UPDATE_RT, parsed.text);
}

/*
 * AF list:
 * [0..3]=tuned frequency
 * [4..5]=PI
 * [6]=AF count,
 * [7..]=int32 kHz frequency entries from the vendor HAL.
 */
void af_update_cb(uint16_t *raw) {
    if (raw == nullptr) {
        return;
    }

    RdsAfList af;
    if (!parse_rds_af_payload(
            reinterpret_cast<const unsigned char *>(raw),
            kUnknownRdsPayloadLen,
            &af
    )) {
        const hci_ev_af_list *raw_af = reinterpret_cast<const hci_ev_af_list *>(raw);
        hal_log("af", "invalid size=%u", raw_af->af_size);
        return;
    }

    char payload[5 * 25 + 1];
    payload[0] = '\0';
    char frequencies[7 * 25 + 1];
    frequencies[0] = '\0';

    for (int i = 0; i < af.count; ++i) {
        const int freq = af.frequencies_khz[i];
        char chunk[8];
        snprintf(chunk, sizeof(chunk), "%04d", freq / 100);
        strncat(payload, chunk, sizeof(payload) - strlen(payload) - 1);

        char frequency_chunk[8];
        snprintf(frequency_chunk, sizeof(frequency_chunk), "%s%d", i == 0 ? "" : " ", freq);
        strncat(frequencies, frequency_chunk, sizeof(frequencies) - strlen(frequencies) - 1);
    }

    hal_log("af", "result count=%d frequencies=%s", af.count, frequencies);
    send_string_event(EVT_UPDATE_AF, payload);
}

/*
 * Search list:
 * [0]=station count
 * [1..]=big-endian 16-bit relative offsets.
 *
 * Offsets are measured from the current lower band edge in 50 kHz units.
 */
void search_list_cb(uint16_t *raw) {
    if (raw == nullptr) {
        return;
    }

    int lower_band = 87500;
    pthread_mutex_lock(&g_state.lock);
    lower_band = g_state.lower_band_khz;
    pthread_mutex_unlock(&g_state.lock);

    RdsSearchList list;
    if (!parse_rds_search_list_payload(
            reinterpret_cast<const unsigned char *>(raw),
            kUnknownRdsPayloadLen,
            RdsSearchListKind::kRelativeOffsets,
            lower_band,
            &list
    )) {
        hal_log("search", "invalid result");
        return;
    }

    if (list.count <= 0) {
        pthread_mutex_lock(&g_state.lock);
        const bool duplicate = g_state.last_search_payload_valid && g_state.last_search_payload[0] == '\0';
        g_state.last_search_payload_valid = true;
        g_state.last_search_payload[0] = '\0';
        pthread_mutex_unlock(&g_state.lock);
        if (duplicate) {
            return;
        }

        hal_log("search", "empty result");
        send_string_event(EVT_SEARCH_DONE, "");
        return;
    }

    char payload[5 * 20 + 1];
    payload[0] = '\0';
    char frequencies[7 * 20 + 1];
    frequencies[0] = '\0';

    for (int i = 0; i < list.count; ++i) {
        const int abs_freq = list.frequencies_khz[i];
        char chunk[8];
        snprintf(chunk, sizeof(chunk), "%04d", abs_freq / 100);
        strncat(payload, chunk, sizeof(payload) - strlen(payload) - 1);

        char frequency_chunk[8];
        snprintf(frequency_chunk, sizeof(frequency_chunk), "%s%d", i == 0 ? "" : " ", abs_freq);
        strncat(frequencies, frequency_chunk, sizeof(frequencies) - strlen(frequencies) - 1);
    }

    // Qualcomm HAL can replay the last search-list callback after the real
    // search result, typically while processing the next command-complete
    // event. The callback buffer is malloc/free-owned by the HAL, so dedupe by
    // decoded payload instead of pointer or event ordering.
    pthread_mutex_lock(&g_state.lock);
    const bool duplicate = g_state.last_search_payload_valid &&
            strcmp(g_state.last_search_payload, payload) == 0;
    if (!duplicate) {
        snprintf(g_state.last_search_payload, sizeof(g_state.last_search_payload), "%s", payload);
        g_state.last_search_payload_valid = true;
    }
    pthread_mutex_unlock(&g_state.lock);
    if (duplicate) {
        return;
    }

    hal_log("search", "result count=%d reported=%d frequencies=%s", list.count, list.reported_count, frequencies);
    send_string_event(EVT_SEARCH_DONE, payload);
}

static fm_hal_callbacks_t g_callbacks = {
    sizeof(fm_hal_callbacks_t),
    enabled_cb,
    tune_cb,
    seek_complete_cb,
    log_scan_next_cb,
    search_list_cb,
    stereo_status_cb,
    rds_avail_status_cb,
    af_update_cb,
    rt_update_cb,
    ps_update_cb,
    oda_update_cb,
    rt_plus_update_cb,
    ert_update_cb,
    disabled_cb,
    rds_grp_cntrs_rsp_cb,
    rds_grp_cntrs_ext_rsp_cb,
    fm_peek_rsp_cb,
    fm_ssbi_peek_rsp_cb,
    fm_agc_gain_rsp_cb,
    fm_ch_det_th_rsp_cb,
    ext_country_code_cb,
    log_thread_evt_cb,
    fm_get_sig_thres_cb,
    fm_get_ch_det_thr_cb,
    fm_def_data_read_cb,
    fm_get_blend_cb,
    fm_set_ch_det_thr_cb,
    fm_def_data_write_cb,
    fm_set_blend_cb,
    fm_get_station_param_cb,
    fm_get_station_debug_param_cb,
    enable_slimbus_cb,
    enable_softmute_cb,
};

bool apply_runtime_config() {
    int spacing = 0;
    int region = 0;
    int lower = 0;
    int upper = 0;
    int stereo = 0;

    pthread_mutex_lock(&g_state.lock);
    spacing = g_state.vendor_spacing;
    region = g_state.vendor_region;
    lower = g_state.lower_band_khz;
    upper = g_state.upper_band_khz;
    stereo = g_state.audio.stereo ? 1 : 0;
    pthread_mutex_unlock(&g_state.lock);

    hal_log("setup", "runtime region=%d lower=%d upper=%d spacing=%d stereo=%d",
            region, lower, upper, spacing, stereo);

    bool ok = true;
    ok &= vendor_set(kV4l2CtrlRegion, region, "failed to set region");
    ok &= vendor_set(kV4l2CtrlIrisUpperBand, upper, "failed to set upper band");
    ok &= vendor_set(kV4l2CtrlIrisLowerBand, lower, "failed to set lower band");
    ok &= vendor_set(kV4l2CtrlChannelSpacing, spacing, "failed to set channel spacing");
    ok &= vendor_set(kV4l2CtrlEmphasis, 1, "failed to set emphasis");
    ok &= vendor_set(kV4l2CtrlRdsStandard, 1, "failed to set rds standard");
    ok &= apply_signal_threshold();
    ok &= vendor_set(kV4l2CtrlSoftMute, 1, "failed to enable soft mute");
    return ok;
}

}  // namespace

bool fm2_backend_init() {
    pthread_mutex_lock(&g_state.lock);
    if (g_state.initialized) {
        clear_error_locked();
        pthread_mutex_unlock(&g_state.lock);
        return true;
    }

    apply_band_config_locked(g_state.app_band);
    g_state.vendor_spacing = map_app_spacing_to_vendor(g_state.app_spacing);

    g_state.lib_handle = dlopen(kFmLibraryName, RTLD_NOW);
    if (g_state.lib_handle == nullptr) {
        const char *err = dlerror();
        hal_log("open", "dlopen failed for %s: %s", kFmLibraryName, err ? err : "(unknown)");
        set_error_locked(err ? err : "dlopen failed");
        pthread_mutex_unlock(&g_state.lock);
        return false;
    }
    g_state.vendor = (fm_interface_t *) dlsym(g_state.lib_handle, kFmLibrarySymbol);
    if (g_state.vendor == nullptr) {
        const char *err = dlerror();
        hal_log("open", "dlsym failed for %s: %s", kFmLibrarySymbol, err ? err : "(unknown)");
        set_error_locked(err ? err : "dlsym failed");
        dlclose(g_state.lib_handle);
        g_state.lib_handle = nullptr;
        pthread_mutex_unlock(&g_state.lock);
        return false;
    }
    const int ret = g_state.vendor->hal_init(&g_callbacks);
    if (ret != 0) {
        set_error_locked("hal_init failed");
        dlclose(g_state.lib_handle);
        g_state.lib_handle = nullptr;
        g_state.vendor = nullptr;
        pthread_mutex_unlock(&g_state.lock);
        return false;
    }

    g_state.initialized = true;
    clear_error_locked();
    pthread_mutex_unlock(&g_state.lock);
    return true;
}

bool fm2_backend_enable() {
    hal_log("enable", "requested");
    if (!fm2_backend_init()) {
        return false;
    }

    pthread_mutex_lock(&g_state.lock);
    g_state.enabled = false;
    g_state.last_enabled_cb_ms = 0;
    pthread_mutex_unlock(&g_state.lock);

    if (!fm2_backend_set_slimbus(true)) {
        hal_log("enable", "failed to enable slimbus before receiver state");
    }

    if (!vendor_set(kV4l2CtrlState, kFmRxState, "failed to enable receiver")) {
        return false;
    }

    if (!fm2_backend_wait_enabled(kEnableWaitTimeoutMs)) {
        return false;
    }

    const bool runtime_ok = apply_runtime_config();
    fm2_backend_log_snapshot("after-runtime-config");
    const bool post_ok = apply_post_enable_config();
    return runtime_ok && post_ok;
}

bool fm2_backend_wait_enabled(int timeout_ms) {
    struct timespec deadline;
    clock_gettime(CLOCK_REALTIME, &deadline);
    deadline.tv_sec += timeout_ms / 1000;
    deadline.tv_nsec += (timeout_ms % 1000) * 1000000L;
    if (deadline.tv_nsec >= 1000000000L) {
        deadline.tv_sec += 1;
        deadline.tv_nsec -= 1000000000L;
    }

    pthread_mutex_lock(&g_state.lock);
    while (!g_state.enabled) {
        const int wait_ret = pthread_cond_timedwait(&g_state.cond, &g_state.lock, &deadline);
        if (wait_ret != 0) {
            set_error_locked("timed out waiting for enabled_cb");
            pthread_mutex_unlock(&g_state.lock);
            return false;
        }
    }
    clear_error_locked();
    pthread_mutex_unlock(&g_state.lock);
    return true;
}

bool fm2_backend_disable() {
    hal_log("disable", "requested");
    const bool disabled = vendor_set(kV4l2CtrlState, 0, "failed to disable receiver");
    const bool slimbus = fm2_backend_set_slimbus(false);
    return disabled && slimbus;
}

bool fm2_backend_set_frequency(uint32_t frequency_khz) {
    hal_log("tune", "request frequency=%u", frequency_khz);
    if (!vendor_set(kV4l2CtrlIrisFreq, static_cast<int>(frequency_khz), "failed to tune")) {
        return false;
    }

    pthread_mutex_lock(&g_state.lock);
    g_state.current_frequency_khz = static_cast<int>(frequency_khz);
    pthread_mutex_unlock(&g_state.lock);
    return true;
}

uint32_t fm2_backend_get_frequency() {
    int freq = 0;
    if (!vendor_get(kV4l2CtrlIrisFreq, &freq, "failed to read frequency")) {
        pthread_mutex_lock(&g_state.lock);
        const int cached = current_frequency_locked();
        pthread_mutex_unlock(&g_state.lock);
        hal_log("tune", "get frequency failed, using cached=%d", cached);
        return cached > 0 ? static_cast<uint32_t>(cached) : 0;
    }

    if (freq <= 0) {
        pthread_mutex_lock(&g_state.lock);
        const int cached = current_frequency_locked();
        clear_error_locked();
        pthread_mutex_unlock(&g_state.lock);
        hal_log("tune", "invalid frequency=%d, using cached=%d", freq, cached);
        return cached > 0 ? static_cast<uint32_t>(cached) : 0;
    }

    pthread_mutex_lock(&g_state.lock);
    g_state.current_frequency_khz = freq;
    clear_error_locked();
    pthread_mutex_unlock(&g_state.lock);
    return static_cast<uint32_t>(freq);
}

bool fm2_backend_jump(int direction, uint32_t *new_frequency) {
    pthread_mutex_lock(&g_state.lock);
    const int step = spacing_step_khz_locked();
    const int lower = g_state.lower_band_khz;
    const int upper = g_state.upper_band_khz;
    pthread_mutex_unlock(&g_state.lock);

    const int current = static_cast<int>(fm2_backend_get_frequency());
    if (current <= 0) {
        return false;
    }

    int target = current + (step * (direction >= 0 ? 1 : -1));
    if (target > upper) {
        target = lower;
    } else if (target < lower) {
        target = upper;
    }

    if (!fm2_backend_set_frequency(target)) {
        return false;
    }

    if (new_frequency != nullptr) {
        *new_frequency = static_cast<uint32_t>(target);
    }
    return true;
}

bool fm2_backend_seek(int direction) {
    hal_log("search", "seek direction=%d", direction);
    const bool ok = vendor_set(kV4l2CtrlSearchMode, kSearchModeSeek, "failed to set seek mode") &&
                    vendor_set(kV4l2CtrlScanDwell, kDefaultSeekDwell, "failed to set seek dwell") &&
                    vendor_set(kV4l2CtrlIrisSeek, direction >= 0 ? 1 : 0, "failed to start seek");
    return ok;
}

bool fm2_backend_search() {
    pthread_mutex_lock(&g_state.lock);
    const int start_frequency = g_state.lower_band_khz;
    reset_search_result_locked();
    reset_scan_locked();
    g_state.scan.active = true;
    g_state.scan.start_frequency_khz = start_frequency;
    g_state.scan.last_frequency_khz = start_frequency;
    pthread_mutex_unlock(&g_state.lock);

    hal_log("search", "scan start frequency=%d", start_frequency);

    const bool ok = vendor_set(kV4l2CtrlIrisFreq, start_frequency, "failed to tune scan start") &&
                    vendor_set(kV4l2CtrlSearchMode, kSearchModeScan, "failed to set scan mode") &&
                    vendor_set(kV4l2CtrlScanDwell, 1, "failed to set scan dwell") &&
                    vendor_set(kV4l2CtrlIrisSeek, 1, "failed to start scan");
    if (!ok) {
        pthread_mutex_lock(&g_state.lock);
        reset_scan_locked();
        pthread_mutex_unlock(&g_state.lock);
    }
    return ok;
}

bool fm2_backend_cancel_search() {
    hal_log("search", "cancel requested");
    pthread_mutex_lock(&g_state.lock);
    reset_scan_locked();
    pthread_mutex_unlock(&g_state.lock);
    return vendor_set(kV4l2CtrlSearchOn, 0, "failed to cancel search");
}

bool fm2_backend_set_rds(bool enabled) {
    hal_log("rds", "set enabled=%d", enabled ? 1 : 0);
    pthread_mutex_lock(&g_state.lock);
    g_state.rds_enabled = enabled;
    const bool auto_af = g_state.auto_af;
    pthread_mutex_unlock(&g_state.lock);

    if (!enabled) {
        return disable_processed_rds_config();
    }

    return apply_raw_rds_group_mask() &&
           apply_processed_rds_config(auto_af);
}

bool fm2_backend_set_stereo(bool enabled) {
    pthread_mutex_lock(&g_state.lock);
    const bool already_set = g_state.audio.mode_valid && g_state.audio.mode_stereo == enabled;
    g_state.audio.mode_valid = true;
    g_state.audio.mode_stereo = enabled;
    pthread_mutex_unlock(&g_state.lock);

    if (already_set) {
        hal_log("audio", "set stereo=%d skipped", enabled ? 1 : 0);
        return true;
    }

    hal_log("audio", "set stereo=%d", enabled ? 1 : 0);
    return vendor_set(kV4l2CtrlIrisAudioMode, enabled ? 1 : 0, "failed to set stereo mode");
}

bool fm2_backend_set_spacing_app_value(int app_spacing) {
    hal_log("setup", "set app spacing=%d", app_spacing);
    pthread_mutex_lock(&g_state.lock);
    g_state.app_spacing = app_spacing;
    g_state.vendor_spacing = map_app_spacing_to_vendor(app_spacing);
    const int vendor_spacing = g_state.vendor_spacing;
    pthread_mutex_unlock(&g_state.lock);
    return vendor_set(kV4l2CtrlChannelSpacing, vendor_spacing, "failed to set spacing");
}

bool fm2_backend_set_region_app_value(int app_region) {
    hal_log("setup", "set app region=%d", app_region);
    pthread_mutex_lock(&g_state.lock);
    apply_band_config_locked(app_region);
    const int region = g_state.vendor_region;
    const int lower = g_state.lower_band_khz;
    const int upper = g_state.upper_band_khz;
    pthread_mutex_unlock(&g_state.lock);

    hal_log("setup", "apply region=%d lower=%d upper=%d", region, lower, upper);
    return vendor_set(kV4l2CtrlIrisUpperBand, upper, "failed to set upper band") &&
           vendor_set(kV4l2CtrlIrisLowerBand, lower, "failed to set lower band") &&
           vendor_set(kV4l2CtrlRegion, region, "failed to set region");
}

bool fm2_backend_set_antenna(int antenna) {
    hal_log("setup", "set antenna=%d", antenna);
    pthread_mutex_lock(&g_state.lock);
    g_state.antenna = antenna;
    pthread_mutex_unlock(&g_state.lock);
    return vendor_set(kV4l2CtrlAntenna, antenna, "failed to set antenna");
}

bool fm2_backend_set_power_mode(bool low_power) {
    hal_log("setup", "set low_power=%d", low_power ? 1 : 0);
    pthread_mutex_lock(&g_state.lock);
    g_state.low_power = low_power;
    pthread_mutex_unlock(&g_state.lock);
    return vendor_set(kV4l2CtrlLowPowerMode, low_power ? 1 : 0, "failed to set power mode");
}

bool fm2_backend_set_auto_af(bool enabled) {
    hal_log("af", "set auto=%d", enabled ? 1 : 0);
    pthread_mutex_lock(&g_state.lock);
    g_state.auto_af = enabled;
    pthread_mutex_unlock(&g_state.lock);
    return vendor_set(kV4l2CtrlAfJump, enabled ? 1 : 0, "failed to set auto af");
}

bool fm2_backend_set_slimbus(bool enabled) {
    hal_log("setup", "set slimbus=%d", enabled ? 1 : 0);
    if (!vendor_set(kV4l2CtrlEnableSlimbus, enabled ? 1 : 0, "failed to set slimbus")) {
        return false;
    }

    pthread_mutex_lock(&g_state.lock);
    g_state.audio.slimbus_enabled = enabled;
    pthread_mutex_unlock(&g_state.lock);
    return true;
}

bool fm2_backend_raw_set(int id, int value) {
    return vendor_set(id, value, "failed raw set");
}

bool fm2_backend_raw_get(int id, int *value) {
    return vendor_get(id, value, "failed raw get");
}

bool fm2_backend_log_snapshot(const char *reason) {
    struct CtlProbe {
        int id;
        const char *name;
    };

    static const CtlProbe probes[] = {
        {kV4l2CtrlState, "state"},
        {kV4l2CtrlRegion, "region"},
        {kV4l2CtrlEmphasis, "emphasis"},
        {kV4l2CtrlRdsStandard, "rds_std"},
        {kV4l2CtrlChannelSpacing, "spacing"},
        {kV4l2CtrlSignalThreshold, "signal_th"},
        {kV4l2CtrlRdsOn, "rds_on"},
        {kV4l2CtrlRdsGroupMask, "rds_mask"},
        {kV4l2CtrlRdsGroupProc, "rds_proc"},
        {kV4l2CtrlLowPowerMode, "lp_mode"},
        {kV4l2CtrlAntenna, "antenna"},
        {kV4l2CtrlAfJump, "af_jump"},
        {kV4l2CtrlSoftMute, "soft_mute"},
        {kV4l2CtrlIrisFreq, "freq"},
        {kV4l2CtrlIrisRmssi, "rmssi"},
    };

    bool ok = true;
    hal_log("snapshot", "reason=%s", reason ? reason : "(null)");
    for (const CtlProbe &probe : probes) {
        int value = 0;
        const bool probe_ok = vendor_get(probe.id, &value, "failed snapshot read");
        ok &= probe_ok;
        hal_log("snapshot", "%s id=0x%x ok=%d value=%d", probe.name, probe.id, probe_ok ? 1 : 0, value);
    }

    return ok;
}

const char *fm2_backend_last_error() {
    return g_state.last_error;
}
