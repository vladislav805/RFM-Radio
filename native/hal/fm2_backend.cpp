#include "fm2_backend.h"

#include <dlfcn.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "../ctl_server.h"
#include "fm2_vendor_iface.h"

namespace {

constexpr const char *kFmLibraryName = "fm_helium.so";
constexpr const char *kFmLibrarySymbol = "FM_HELIUM_LIB_INTERFACE";
constexpr int kFmRxState = 1;
constexpr int kSearchModeSeek = 0;
constexpr int kSearchModeStrongList = 2;
constexpr int kDefaultListSize = 20;
constexpr int kEnableWaitTimeoutMs = 2000;

// FM_RX_RSSI_LEVEL_VERY_WEAK   = -105;
// FM_RX_RSSI_LEVEL_WEAK        = -100;
// FM_RX_RSSI_LEVEL_STRONG      = -96;
// FM_RX_RSSI_LEVEL_VERY_STRONG = -90;
// set_ctrl(V4L2_CID_PRIVATE_IRIS_SIGNAL_TH, kDefaultSignalThreshold - 105)
constexpr int kDefaultSignalThreshold = 0x40;
constexpr int kDefaultSeekDwell = 7;
constexpr int kDefaultRdsGroupProcMask = 0xEF;
constexpr int kDefaultRawRdsGroupMask = 40; // 64;

struct RuntimeState {
    void *lib_handle = nullptr;
    fm_interface_t *vendor = nullptr;
    bool initialized = false;
    bool enabled = false;
    bool rds_enabled = true;
    bool stereo = true;
    bool low_power = false;
    bool auto_af = true;
    bool slimbus_enabled = false;
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
    char last_error[128] = "";
    pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
    pthread_cond_t cond = PTHREAD_COND_INITIALIZER;
} g_state;

void set_error_locked(const char *message) {
    FM2_LOGE("backend error set: %s", message ? message : "(null)");
    snprintf(g_state.last_error, sizeof(g_state.last_error), "%s", message);
}

void clear_error_locked() {
    g_state.last_error[0] = '\0';
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

    const int ret = g_state.vendor->set_fm_ctrl(id, value);
    if (ret < 0) {
        set_error_locked(message);
        FM2_LOGW("vendor_set failed id=0x%x value=%d ret=%d", id, value, ret);
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

    const int ret = g_state.vendor->get_fm_ctrl(id, value);
    if (ret < 0) {
        set_error_locked(message);
        FM2_LOGW("vendor_get failed id=0x%x ret=%d", id, ret);
        pthread_mutex_unlock(&g_state.lock);
        return false;
    }

    clear_error_locked();
    pthread_mutex_unlock(&g_state.lock);
    return true;
}

bool apply_signal_threshold() {
    return vendor_set(V4L2_CID_PRV_SIGNAL_TH, kDefaultSignalThreshold, "failed to set signal threshold");
}

bool apply_raw_rds_group_mask() {
    return vendor_set(V4L2_CID_PRV_RDSGROUP_MASK, kDefaultRawRdsGroupMask, "failed to set raw rds group mask");
}

bool apply_processed_rds_config(bool auto_af) {
    if (!vendor_set(V4L2_CID_PRV_RDSON, 1, "failed to set rds on")) {
        return false;
    }
    if (!vendor_set(V4L2_CID_PRV_RDSGROUP_PROC, kDefaultRdsGroupProcMask, "failed to set rds groups")) {
        return false;
    }
    return vendor_set(V4L2_CID_PRV_AF_JUMP, auto_af ? 1 : 0, "failed to set af jump");
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

    if (!vendor_set(V4L2_CID_PRV_LP_MODE, low_power ? 1 : 0, "failed to set power mode")) {
        return false;
    }

    if (rds_enabled) {
        return apply_processed_rds_config(auto_af);
    }

    return vendor_set(V4L2_CID_PRV_RDSON, 0, "failed to disable rds") &&
           vendor_set(V4L2_CID_PRV_RDSGROUP_PROC, 0, "failed to clear rds groups") &&
           vendor_set(V4L2_CID_PRV_AF_JUMP, 0, "failed to clear af jump");
}

bool apply_post_tune_config() {
    bool rds_enabled = false;
    bool auto_af = false;

    pthread_mutex_lock(&g_state.lock);
    rds_enabled = g_state.rds_enabled;
    auto_af = g_state.auto_af;
    pthread_mutex_unlock(&g_state.lock);

    if (!rds_enabled) {
        return true;
    }

    // return apply_raw_rds_group_mask() &&
    //        apply_processed_rds_config(auto_af);
    return true;
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
}

void log_char_cb(const char *name, char *payload) {
    (void)name;
    (void)payload;
}

void log_thread_evt_cb(unsigned int evt) {
    (void)evt;
}

void log_ii_cb(const char *name, int value, int status) {
    (void)name;
    (void)value;
    (void)status;
}

void log_i_cb(const char *name, int status) {
    (void)name;
    (void)status;
}

void oda_update_cb() {
}

void rt_plus_update_cb(char *payload) {
    log_char_cb("rt_plus_update_cb", payload);
}

void ert_update_cb(char *payload) {
    log_char_cb("ert_update_cb", payload);
}

void rds_grp_cntrs_rsp_cb(char *payload) {
    log_char_cb("rds_grp_cntrs_rsp_cb", payload);
}

void rds_grp_cntrs_ext_rsp_cb(char *payload) {
    log_char_cb("rds_grp_cntrs_ext_rsp_cb", payload);
}

void fm_peek_rsp_cb(char *payload) {
    log_char_cb("fm_peek_rsp_cb", payload);
}

void fm_ssbi_peek_rsp_cb(char *payload) {
    log_char_cb("fm_ssbi_peek_rsp_cb", payload);
}

void fm_agc_gain_rsp_cb(char *payload) {
    log_char_cb("fm_agc_gain_rsp_cb", payload);
}

void fm_ch_det_th_rsp_cb(char *payload) {
    log_char_cb("fm_ch_det_th_rsp_cb", payload);
}

void ext_country_code_cb(char *payload) {
    log_char_cb("ext_country_code_cb", payload);
}

void fm_get_sig_thres_cb(int value, int status) {
    log_ii_cb("fm_get_sig_thres_cb", value, status);
}

void fm_get_ch_det_thr_cb(int value, int status) {
    log_ii_cb("fm_get_ch_det_thr_cb", value, status);
}

void fm_def_data_read_cb(int value, int status) {
    log_ii_cb("fm_def_data_read_cb", value, status);
}

void fm_get_blend_cb(int value, int status) {
    log_ii_cb("fm_get_blend_cb", value, status);
}

void fm_set_ch_det_thr_cb(int status) {
    log_i_cb("fm_set_ch_det_thr_cb", status);
}

void fm_def_data_write_cb(int status) {
    log_i_cb("fm_def_data_write_cb", status);
}

void fm_set_blend_cb(int status) {
    log_i_cb("fm_set_blend_cb", status);
}

void fm_get_station_param_cb(int value, int status) {
    log_ii_cb("fm_get_station_param_cb", value, status);
}

void fm_get_station_debug_param_cb(int value, int status) {
    log_ii_cb("fm_get_station_debug_param_cb", value, status);
}

void enable_softmute_cb(int status) {
    log_i_cb("enable_softmute_cb", status);
}

void rds_avail_status_cb(bool rds_available) {
    FM2_LOGI("HAL callback rds_avail_status_cb rds_available=%d", rds_available ? 1 : 0);
}

void enable_slimbus_cb(int status) {
    FM2_LOGI("HAL callback enable_slimbus_cb status=%d", status);
    pthread_mutex_lock(&g_state.lock);
    g_state.slimbus_enabled = status == 0;
    pthread_mutex_unlock(&g_state.lock);
}

void enabled_cb() {
    FM2_LOGI("HAL callback enabled_cb");
    pthread_mutex_lock(&g_state.lock);
    g_state.enabled = true;
    g_state.last_enabled_cb_ms = now_ms();
    pthread_cond_broadcast(&g_state.cond);
    pthread_mutex_unlock(&g_state.lock);
    apply_post_enable_config();
    send_string_event(EVT_ENABLED, "enabled");
}

void disabled_cb() {
    FM2_LOGI("HAL callback disabled_cb");
    pthread_mutex_lock(&g_state.lock);
    g_state.enabled = false;
    pthread_cond_broadcast(&g_state.cond);
    pthread_mutex_unlock(&g_state.lock);
    send_string_event(EVT_DISABLED, "disabled");
}

void tune_cb(int freq) {
    FM2_LOGI("HAL callback tune_cb freq=%d", freq);
    pthread_mutex_lock(&g_state.lock);
    g_state.current_frequency_khz = freq;
    g_state.last_tune_cb_ms = now_ms();
    pthread_mutex_unlock(&g_state.lock);
    send_string_event(EVT_UPDATE_PS, "");
    send_string_event(EVT_UPDATE_RT, "");
    send_int_event(EVT_UPDATE_PTY, 0);
    send_string_event(EVT_UPDATE_PI, "");
    send_frequency_event(EVT_FREQUENCY_SET, freq);
}

void seek_complete_cb(int freq) {
    FM2_LOGI("HAL callback seek_complete_cb freq=%d", freq);
    pthread_mutex_lock(&g_state.lock);
    g_state.current_frequency_khz = freq;
    g_state.last_seek_cb_ms = now_ms();
    pthread_mutex_unlock(&g_state.lock);
    send_string_event(EVT_UPDATE_PS, "");
    send_string_event(EVT_UPDATE_RT, "");
    send_int_event(EVT_UPDATE_PTY, 0);
    send_string_event(EVT_UPDATE_PI, "");
    send_frequency_event(EVT_SEEK_COMPLETE, freq);
}

void stereo_status_cb(bool stereo) {
    FM2_LOGI("HAL callback stereo_status_cb stereo=%d", stereo ? 1 : 0);
    pthread_mutex_lock(&g_state.lock);
    g_state.stereo = stereo;
    pthread_mutex_unlock(&g_state.lock);
    send_string_event(EVT_STEREO, stereo ? "1" : "0");
}

void ps_update_cb(char *ps) {
    if (ps == nullptr) {
        FM2_LOGW("HAL callback ps_update_cb got null");
        return;
    }

    const int ps_count = static_cast<unsigned char>(ps[0]);
    const int pty = static_cast<unsigned char>(ps[1]) & 0x1f;
    const int pi = (static_cast<unsigned char>(ps[2]) << 8) | static_cast<unsigned char>(ps[3]);
    int text_len = ps_count * 8;
    if (text_len > 119) {
        text_len = 119;
    }

    char text[120];
    memset(text, 0, sizeof(text));
    memcpy(text, ps + 5, text_len);
    FM2_LOGI("HAL callback ps_update_cb ps_count=%d pty=%d pi=0x%x text=`%s`", ps_count, pty, pi, text);
    send_string_event(EVT_UPDATE_PS, text);
    send_int_event(EVT_UPDATE_PTY, pty);
    send_hex_event(EVT_UPDATE_PI, pi);
}

void rt_update_cb(char *rt) {
    if (rt == nullptr) {
        FM2_LOGW("HAL callback rt_update_cb got null");
        return;
    }

    int text_len = static_cast<unsigned char>(rt[0]);
    if (text_len > 64) {
        text_len = 64;
    }

    char text[96];
    memset(text, 0, sizeof(text));
    memcpy(text, rt + 5, text_len);
    FM2_LOGI("HAL callback rt_update_cb len=%d text=`%s`", text_len, text);
    send_string_event(EVT_UPDATE_RT, text);
}

void af_update_cb(uint16_t *raw) {
    if (raw == nullptr) {
        return;
    }

    const hci_ev_af_list *af = reinterpret_cast<const hci_ev_af_list *>(raw);
    const uint8_t af_size = af->af_size;
    if (af_size == 0 || af_size > 25) {
        FM2_LOGW("HAL callback af_update_cb invalid size=%u", af_size);
        return;
    }

    char payload[5 * 25 + 1];
    payload[0] = '\0';

    for (uint8_t i = 0; i < af_size; ++i) {
        int32_t freq = 0;
        memcpy(&freq, &af->af_list[i * sizeof(int32_t)], sizeof(int32_t));
        char chunk[8];
        snprintf(chunk, sizeof(chunk), "%04d", static_cast<int>(freq / 100));
        strncat(payload, chunk, sizeof(payload) - strlen(payload) - 1);
    }

    FM2_LOGI("HAL callback af_update_cb size=%u payload=`%s`", af_size, payload);
    send_string_event(EVT_UPDATE_AF, payload);
}

void search_list_cb(uint16_t *raw) {
    if (raw == nullptr) {
        return;
    }

    const hci_ev_srch_list_compl *list = reinterpret_cast<const hci_ev_srch_list_compl *>(raw);
    const int count = static_cast<unsigned char>(list->num_stations_found);
    if (count <= 0) {
        FM2_LOGW("HAL callback search_list_cb empty search result");
        send_string_event(EVT_SEARCH_DONE, "");
        return;
    }

    int lower_band = 87500;
    pthread_mutex_lock(&g_state.lock);
    lower_band = g_state.lower_band_khz;
    pthread_mutex_unlock(&g_state.lock);

    char payload[5 * 20 + 1];
    payload[0] = '\0';

    for (int i = 0; i < count && i < 20; ++i) {
        const int rel = ((static_cast<unsigned char>(list->rel_freq[i].rel_freq_msb) << 8) |
                         static_cast<unsigned char>(list->rel_freq[i].rel_freq_lsb));
        const int abs_freq = lower_band + (rel * 50);
        char chunk[8];
        snprintf(chunk, sizeof(chunk), "%04d", abs_freq / 100);
        strncat(payload, chunk, sizeof(payload) - strlen(payload) - 1);
    }

    FM2_LOGI("HAL callback search_list_cb count=%d payload=`%s`", count, payload);
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
    stereo = g_state.stereo ? 1 : 0;
    pthread_mutex_unlock(&g_state.lock);

    FM2_LOGI("apply_runtime_config region=%d lower=%d upper=%d spacing=%d stereo=%d",
             region, lower, upper, spacing, stereo);

    return vendor_set(V4L2_CID_PRV_SOFT_MUTE, 1, "failed to enable soft mute") &&
           vendor_set(V4L2_CID_PRV_EMPHASIS, 1, "failed to set emphasis") &&
           vendor_set(V4L2_CID_PRV_RDS_STD, 1, "failed to set rds standard") &&
           vendor_set(V4L2_CID_PRV_CHAN_SPACING, spacing, "failed to set channel spacing") &&
           vendor_set(V4L2_CID_PRV_IRIS_UPPER_BAND, upper, "failed to set upper band") &&
           vendor_set(V4L2_CID_PRV_IRIS_LOWER_BAND, lower, "failed to set lower band") &&
           vendor_set(V4L2_CID_PRV_REGION, region, "failed to set region");
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
        FM2_LOGE("dlopen failed for %s: %s", kFmLibraryName, dlerror());
        set_error_locked(dlerror());
        pthread_mutex_unlock(&g_state.lock);
        return false;
    }
    g_state.vendor = (fm_interface_t *) dlsym(g_state.lib_handle, kFmLibrarySymbol);
    if (g_state.vendor == nullptr) {
        FM2_LOGE("dlsym failed for %s: %s", kFmLibrarySymbol, dlerror());
        set_error_locked(dlerror());
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
    FM2_LOGI("fm2_backend_enable called");
    if (!fm2_backend_init()) {
        return false;
    }

    pthread_mutex_lock(&g_state.lock);
    g_state.enabled = false;
    g_state.last_enabled_cb_ms = 0;
    pthread_mutex_unlock(&g_state.lock);

    if (!fm2_backend_set_slimbus(true)) {
        FM2_LOGW("fm2_backend_enable: failed to enable slimbus before receiver state");
    }

    if (!vendor_set(V4L2_CID_PRV_STATE, kFmRxState, "failed to enable receiver")) {
        return false;
    }

    const bool ok = apply_runtime_config();
    return ok;
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
    FM2_LOGI("fm2_backend_disable called");
    const bool disabled = vendor_set(V4L2_CID_PRV_STATE, 0, "failed to disable receiver");
    const bool slimbus = fm2_backend_set_slimbus(false);
    return disabled && slimbus;
}

bool fm2_backend_set_frequency(uint32_t frequency_khz) {
    FM2_LOGI("fm2_backend_set_frequency freq=%u", frequency_khz);
    if (!vendor_set(V4L2_CID_PRV_IRIS_FREQ, static_cast<int>(frequency_khz), "failed to tune")) {
        return false;
    }

    pthread_mutex_lock(&g_state.lock);
    g_state.current_frequency_khz = static_cast<int>(frequency_khz);
    pthread_mutex_unlock(&g_state.lock);
    return true;
}

uint32_t fm2_backend_get_frequency() {
    int freq = 0;
    if (!vendor_get(V4L2_CID_PRV_IRIS_FREQ, &freq, "failed to read frequency")) {
        pthread_mutex_lock(&g_state.lock);
        const int cached = current_frequency_locked();
        pthread_mutex_unlock(&g_state.lock);
        FM2_LOGW("fm2_backend_get_frequency: vendor_get failed, using cached frequency=%d", cached);
        return cached > 0 ? static_cast<uint32_t>(cached) : 0;
    }

    if (freq <= 0) {
        pthread_mutex_lock(&g_state.lock);
        const int cached = current_frequency_locked();
        clear_error_locked();
        pthread_mutex_unlock(&g_state.lock);
        FM2_LOGW("fm2_backend_get_frequency: HAL returned invalid frequency=%d, using cached=%d", freq, cached);
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
    pthread_mutex_unlock(&g_state.lock);

    const int current = static_cast<int>(fm2_backend_get_frequency());
    if (current <= 0) {
        return false;
    }

    const uint32_t target = static_cast<uint32_t>(current + (step * (direction >= 0 ? 1 : -1)));
    if (!fm2_backend_set_frequency(target)) {
        return false;
    }

    if (new_frequency != nullptr) {
        *new_frequency = target;
    }
    return true;
}

bool fm2_backend_seek(int direction) {
    FM2_LOGI("fm2_backend_seek direction=%d", direction);
    const bool ok = vendor_set(V4L2_CID_PRV_SRCHMODE, kSearchModeSeek, "failed to set seek mode") &&
                    vendor_set(V4L2_CID_PRV_SCANDWELL, kDefaultSeekDwell, "failed to set seek dwell") &&
                    vendor_set(V4L2_CID_PRV_IRIS_SEEK, direction >= 0 ? 1 : 0, "failed to start seek");
    return ok;
}

bool fm2_backend_search() {
    return vendor_set(V4L2_CID_PRV_SRCHMODE, kSearchModeStrongList, "failed to set search list mode") &&
           vendor_set(V4L2_CID_PRV_SRCH_CNT, 1, "failed to set search list size") &&
           vendor_set(V4L2_CID_PRV_IRIS_SEEK, 1, "failed to start search list");
}

bool fm2_backend_cancel_search() {
    FM2_LOGI("fm2_backend_cancel_search called");
    return vendor_set(V4L2_CID_PRV_SRCHON, 0, "failed to cancel search");
}

bool fm2_backend_set_rds(bool enabled) {
    FM2_LOGI("fm2_backend_set_rds enabled=%d", enabled ? 1 : 0);
    pthread_mutex_lock(&g_state.lock);
    g_state.rds_enabled = enabled;
    pthread_mutex_unlock(&g_state.lock);

    if (!enabled) {
        return vendor_set(V4L2_CID_PRV_RDSON, 0, "failed to set rds") &&
               vendor_set(V4L2_CID_PRV_RDSGROUP_PROC, 0, "failed to clear rds groups");
    }

    return apply_raw_rds_group_mask() &&
           apply_processed_rds_config(g_state.auto_af);
}

bool fm2_backend_set_stereo(bool enabled) {
    FM2_LOGI("fm2_backend_set_stereo enabled=%d", enabled ? 1 : 0);
    pthread_mutex_lock(&g_state.lock);
    g_state.stereo = enabled;
    pthread_mutex_unlock(&g_state.lock);
    return vendor_set(V4L2_CID_PRV_IRIS_AUDIO_MODE, enabled ? 1 : 0, "failed to set stereo mode");
}

bool fm2_backend_set_spacing_app_value(int app_spacing) {
    FM2_LOGI("fm2_backend_set_spacing_app_value app_spacing=%d", app_spacing);
    pthread_mutex_lock(&g_state.lock);
    g_state.app_spacing = app_spacing;
    g_state.vendor_spacing = map_app_spacing_to_vendor(app_spacing);
    const int vendor_spacing = g_state.vendor_spacing;
    pthread_mutex_unlock(&g_state.lock);
    return vendor_set(V4L2_CID_PRV_CHAN_SPACING, vendor_spacing, "failed to set spacing");
}

bool fm2_backend_set_region_app_value(int app_region) {
    FM2_LOGI("fm2_backend_set_region_app_value app_region=%d", app_region);
    pthread_mutex_lock(&g_state.lock);
    apply_band_config_locked(app_region);
    const int region = g_state.vendor_region;
    const int lower = g_state.lower_band_khz;
    const int upper = g_state.upper_band_khz;
    pthread_mutex_unlock(&g_state.lock);

    FM2_LOGI("set_region: applying upper=%d lower=%d region=%d", upper, lower, region);
    return vendor_set(V4L2_CID_PRV_IRIS_UPPER_BAND, upper, "failed to set upper band") &&
           vendor_set(V4L2_CID_PRV_IRIS_LOWER_BAND, lower, "failed to set lower band") &&
           vendor_set(V4L2_CID_PRV_REGION, region, "failed to set region");
}

bool fm2_backend_set_antenna(int antenna) {
    FM2_LOGI("fm2_backend_set_antenna antenna=%d", antenna);
    pthread_mutex_lock(&g_state.lock);
    g_state.antenna = antenna;
    pthread_mutex_unlock(&g_state.lock);
    return vendor_set(V4L2_CID_PRV_ANTENNA, antenna, "failed to set antenna");
}

bool fm2_backend_set_power_mode(bool low_power) {
    FM2_LOGI("fm2_backend_set_power_mode low_power=%d", low_power ? 1 : 0);
    pthread_mutex_lock(&g_state.lock);
    g_state.low_power = low_power;
    pthread_mutex_unlock(&g_state.lock);
    return vendor_set(V4L2_CID_PRV_LP_MODE, low_power ? 1 : 0, "failed to set power mode");
}

bool fm2_backend_set_auto_af(bool enabled) {
    FM2_LOGI("fm2_backend_set_auto_af enabled=%d", enabled ? 1 : 0);
    pthread_mutex_lock(&g_state.lock);
    g_state.auto_af = enabled;
    pthread_mutex_unlock(&g_state.lock);
    return vendor_set(V4L2_CID_PRV_AF_JUMP, enabled ? 1 : 0, "failed to set auto af");
}

bool fm2_backend_set_slimbus(bool enabled) {
    FM2_LOGI("fm2_backend_set_slimbus enabled=%d", enabled ? 1 : 0);
    if (!vendor_set(V4L2_CID_PRV_ENABLE_SLIMBUS, enabled ? 1 : 0, "failed to set slimbus")) {
        return false;
    }

    pthread_mutex_lock(&g_state.lock);
    g_state.slimbus_enabled = enabled;
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
    int state = 0;
    int region = 0;
    int emphasis = 0;
    int rds_std = 0;
    int spacing = 0;
    int signal_th = 0;
    int rds_on = 0;
    int rds_mask = 0;
    int rds_proc = 0;
    int lp_mode = 0;
    int antenna = 0;
    int af_jump = 0;
    int soft_mute = 0;
    int freq = 0;
    const bool ok =
        vendor_get(V4L2_CID_PRV_STATE, &state, "failed to read state") &&
        vendor_get(V4L2_CID_PRV_REGION, &region, "failed to read region") &&
        vendor_get(V4L2_CID_PRV_EMPHASIS, &emphasis, "failed to read emphasis") &&
        vendor_get(V4L2_CID_PRV_RDS_STD, &rds_std, "failed to read rds std") &&
        vendor_get(V4L2_CID_PRV_CHAN_SPACING, &spacing, "failed to read spacing") &&
        vendor_get(V4L2_CID_PRV_SIGNAL_TH, &signal_th, "failed to read signal threshold") &&
        vendor_get(V4L2_CID_PRV_RDSON, &rds_on, "failed to read rds on") &&
        vendor_get(V4L2_CID_PRV_RDSGROUP_MASK, &rds_mask, "failed to read rds group mask") &&
        vendor_get(V4L2_CID_PRV_RDSGROUP_PROC, &rds_proc, "failed to read rds group proc") &&
        vendor_get(V4L2_CID_PRV_LP_MODE, &lp_mode, "failed to read low power mode") &&
        vendor_get(V4L2_CID_PRV_ANTENNA, &antenna, "failed to read antenna") &&
        vendor_get(V4L2_CID_PRV_AF_JUMP, &af_jump, "failed to read af jump") &&
        vendor_get(V4L2_CID_PRV_SOFT_MUTE, &soft_mute, "failed to read soft mute") &&
        vendor_get(V4L2_CID_PRV_IRIS_FREQ, &freq, "failed to read frequency");

    FM2_LOGI("snapshot reason=%s ok=%d freq=%d",
             reason ? reason : "(null)", ok ? 1 : 0, freq);
    return ok;
}

const char *fm2_backend_last_error() {
    return g_state.last_error;
}
