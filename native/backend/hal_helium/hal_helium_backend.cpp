#include "hal_helium_backend.h"

#include <dlfcn.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "../../ctl_server.h"
#include "../../frequency_format.h"
#include "../../hex_dump.h"
#include "../../monotonic_clock.h"
#include "../../region_profile.h"
#include "../../rmssi.h"

namespace {

constexpr Logger kHeliumLog{"helium"};
constexpr const char *kErrFailed = "ERR_FAILED";
constexpr const char *kFmLibraryName = "fm_helium.so";
constexpr const char *kFmLibrarySymbol = "FM_HELIUM_LIB_INTERFACE";

constexpr int kFmRxState = 1;

constexpr int kSearchModeSeek = 0;
constexpr int kSearchModeScan = 1;

constexpr int kHeliumSpacing200Khz = 0;
constexpr int kHeliumSpacing100Khz = 1;
constexpr int kHeliumSpacing50Khz = 2;

constexpr int kEnableWaitTimeoutMs = 2000;
constexpr int kShutdownWaitTimeoutMs = 2000;
constexpr uint64_t kRmssiRequestTimeoutMs = 5000;

// Qualcomm's stock WEAK threshold. Device scans found no useful benefit from
// stricter values, while the previous value (0x40) matched no known ABI scale.
constexpr int kDefaultSignalThreshold = -100;
// Sweeping across the entire frequency range stops at each station found and pauses for N seconds.
constexpr int kDefaultSeekDwell = 2;

// Default Helium processed RDS group mask: 0xEF (0b1110_1111, group-proc flags with bit 4 cleared).
constexpr int kHeliumDefaultRdsGroupProcMask = 0xEF;

// Default Helium raw RDS group pass-through mask: 40 (0x28 = (1 << 3) | (1 << 5)).
constexpr int kHeliumDefaultRawRdsGroupMask = (1 << 3) | (1 << 5);

int map_spacing_to_vendor(int spacing_khz) {
    switch (spacing_khz) {
        case 50:
            return kHeliumSpacing50Khz;
        case 200:
            return kHeliumSpacing200Khz;
        case 100:
        default:
            return kHeliumSpacing100Khz;
    }
}

bool is_duplicate_payload_locked(
        bool *valid,
        unsigned char *last,
        size_t last_size,
        const char *payload,
        int len
) {
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
        kHeliumLog(scope, "%s got null", name);
        return;
    }

    const int len = static_cast<unsigned char>(payload[0]) + extra_len;
    char dump[3 * 32 + 1];
    format_hex_dump(payload, len, dump, sizeof(dump));
    kHeliumLog(scope, "%s len=%d data=%s%s", name, len, dump, len > 32 ? " ..." : "");
}

}  // namespace

HalHeliumBackend *HalHeliumBackend::active_instance_ = nullptr;

const fm_hal_callbacks_t HalHeliumBackend::kCallbacks = {
    sizeof(fm_hal_callbacks_t),
    HalHeliumBackend::enabled_cb,
    HalHeliumBackend::tune_cb,
    HalHeliumBackend::seek_complete_cb,
    HalHeliumBackend::scan_next_cb,
    HalHeliumBackend::search_list_cb,
    HalHeliumBackend::stereo_status_cb,
    HalHeliumBackend::rds_avail_status_cb,
    HalHeliumBackend::af_update_cb,
    HalHeliumBackend::rt_update_cb,
    HalHeliumBackend::ps_update_cb,
    HalHeliumBackend::oda_update_cb,
    HalHeliumBackend::rt_plus_update_cb,
    HalHeliumBackend::ert_update_cb,
    HalHeliumBackend::disabled_cb,
    HalHeliumBackend::rds_grp_cntrs_rsp_cb,
    HalHeliumBackend::rds_grp_cntrs_ext_rsp_cb,
    HalHeliumBackend::fm_peek_rsp_cb,
    HalHeliumBackend::fm_ssbi_peek_rsp_cb,
    HalHeliumBackend::fm_agc_gain_rsp_cb,
    HalHeliumBackend::fm_ch_det_th_rsp_cb,
    HalHeliumBackend::ext_country_code_cb,
    HalHeliumBackend::thread_evt_cb,
    HalHeliumBackend::fm_get_sig_thres_cb,
    HalHeliumBackend::fm_get_ch_det_thr_cb,
    HalHeliumBackend::fm_def_data_read_cb,
    HalHeliumBackend::fm_get_blend_cb,
    HalHeliumBackend::fm_set_ch_det_thr_cb,
    HalHeliumBackend::fm_def_data_write_cb,
    HalHeliumBackend::fm_set_blend_cb,
    HalHeliumBackend::fm_get_station_param_cb,
    HalHeliumBackend::fm_get_station_debug_param_cb,
    HalHeliumBackend::enable_slimbus_cb,
    HalHeliumBackend::enable_softmute_cb,
};

HalHeliumBackend::HalHeliumBackend() : Backend(kHeliumLog), state_(log) {
    active_instance_ = this;
}

HalHeliumBackend::~HalHeliumBackend() {
    if (active_instance_ == this) {
        active_instance_ = nullptr;
    }
}

// Records an error message and logs it under state_lock_.
void HalHeliumBackend::set_error_locked(const char *message) {
    const char *safe_message = message ? message : "unknown error";
    log("error", "%s", safe_message);
    snprintf(state_.last_error, sizeof(state_.last_error), "%s", safe_message);
}

// Resets runtime state and error tracking under state_lock_ for unit tests.
void HalHeliumBackend::reset_for_test(
        fm_interface_t *vendor,
        bool initialized,
        bool enabled,
        bool receiver_cleanup_needed
) {
    {
        std::lock_guard<std::mutex> lock(state_lock_);
        state_.vendor = vendor;
        state_.initialized = initialized;
        state_.enabled = enabled;
        state_.receiver_cleanup_needed = receiver_cleanup_needed;
        state_.disable_pending = false;
        state_.scan.active = false;
        state_.current_frequency_khz = 0;
        state_.rmssi.clear_request();
        state_.rmssi.paused = false;
        sinr_supported_ = true;
        clear_error_locked();
    }
    last_error_.clear();
}

// Clears the stored error message under state_lock_.
void HalHeliumBackend::clear_error_locked() {
    state_.last_error[0] = '\0';
}

// Clears cached search-list deduplication state under state_lock_.
void HalHeliumBackend::reset_search_result_locked() {
    state_.last_search_payload_valid = false;
    state_.last_search_payload[0] = '\0';
}

// Resets sequential scan and ordinary seek tracking state under state_lock_.
void HalHeliumBackend::reset_scan_locked() {
    state_.scan.active = false;
    state_.scan.seeking = false;
    state_.scan.wrapped = false;
    state_.scan.start_frequency_khz = 0;
    state_.scan.last_frequency_khz = 0;
    state_.scan.stations.reset();
}

// Clears RDS deduplication buffers and RT+ context under state_lock_.
void HalHeliumBackend::reset_rds_dedup_locked() {
    state_.last_rt_plus_valid = false;
    state_.last_ecc_valid = false;
    state_.rt_plus.reset();
}

// Updates region band bounds, emphasis, and RDS standard under state_lock_.
void HalHeliumBackend::apply_region_profile_locked(StartupRegion region) {
    const RegionProfile &profile = get_region_profile(region);
    state_.region = region;
    state_.vendor_region = profile.helium_region;
    state_.lower_band_khz = static_cast<int>(profile.lower_frequency_khz);
    state_.upper_band_khz = static_cast<int>(profile.upper_frequency_khz);
    state_.emphasis = profile.emphasis;
    state_.rds_standard = profile.rds_standard;
}

// Updates last_error_ from state_.last_error on failure.
bool HalHeliumBackend::set_status(bool ok) {
    if (ok) {
        last_error_.clear();
        return true;
    }

    std::lock_guard<std::mutex> lock(state_lock_);
    last_error_ = state_.last_error[0] ? state_.last_error : kErrFailed;
    return false;
}

// Disables the receiver when enable fails partway while keeping the original error.
bool HalHeliumBackend::rollback_enable() {
    const std::string error = last_error_;
    disable();
    last_error_ = error;
    return false;
}

// Writes a vendor control through fm_interface_t under state_lock_.
bool HalHeliumBackend::vendor_set(int id, int value, const char *message) {
    std::lock_guard<std::mutex> lock(state_lock_);
    if (state_.vendor == nullptr) {
        set_error_locked("vendor interface is null");
        return false;
    }

    errno = 0;
    const int ret = state_.vendor->set_fm_ctrl(id, value);
    if (ret < 0) {
        const int saved_errno = errno;
        set_error_locked(message);
        log("vendor", "set id=0x%x value=%d failed, ret=%d errno=%d (%s)",
                id, value, ret, saved_errno, saved_errno ? strerror(saved_errno) : "none");
        return false;
    }

    clear_error_locked();
    return true;
}

// Reads a vendor control through fm_interface_t under state_lock_.
bool HalHeliumBackend::vendor_get(int id, int *value, const char *message) {
    std::lock_guard<std::mutex> lock(state_lock_);
    if (state_.vendor == nullptr) {
        set_error_locked("vendor interface is null");
        return false;
    }

    errno = 0;
    const int ret = state_.vendor->get_fm_ctrl(id, value);
    if (ret < 0) {
        const int saved_errno = errno;
        set_error_locked(message);
        log("vendor", "get id=0x%x failed, ret=%d errno=%d (%s)",
                id, ret, saved_errno, saved_errno ? strerror(saved_errno) : "none");
        return false;
    }

    clear_error_locked();
    return true;
}

// Writes Qualcomm's default signal search threshold.
bool HalHeliumBackend::apply_signal_threshold() {
    return vendor_set(kV4l2CtrlSignalThreshold, kDefaultSignalThreshold, "failed to set signal threshold");
}

// Enables raw RDS group 3A discovery for RT+ and ODA.
bool HalHeliumBackend::apply_raw_rds_group_mask() {
    return vendor_set(kV4l2CtrlRdsGroupMask, kHeliumDefaultRawRdsGroupMask, "failed to set raw rds group mask");
}

// Configures RDS standard, processed group mask, and auto-AF jump.
bool HalHeliumBackend::apply_processed_rds_config(bool auto_af, int rds_standard) {
    if (!vendor_set(kV4l2CtrlRdsStandard, rds_standard, "failed to set rds standard")) {
        return false;
    }

    if (!vendor_set(kV4l2CtrlRdsGroupProc, kHeliumDefaultRdsGroupProcMask, "failed to set rds groups")) {
        return false;
    }

    if (!vendor_set(kV4l2CtrlAfJump, auto_af ? 1 : 0, "failed to set af jump")) {
        char original_error[sizeof(state_.last_error)];
        {
            std::lock_guard<std::mutex> lock(state_lock_);
            snprintf(original_error, sizeof(original_error), "%s", state_.last_error);
        }

        vendor_set(kV4l2CtrlRdsGroupProc, 0, "failed to roll back rds groups");

        {
            std::lock_guard<std::mutex> lock(state_lock_);
            snprintf(state_.last_error, sizeof(state_.last_error), "%s", original_error);
        }
        return false;
    }

    return true;
}

// Applies band, emphasis, spacing, audio mode, antenna, and soft mute after enabled_cb.
bool HalHeliumBackend::apply_runtime_config() {
    int spacing = 0;
    int region = 0;
    int lower = 0;
    int upper = 0;
    int stereo = 0;
    int soft_mute = 0;
    int antenna = 0;
    int emphasis = 0;
    int rds_standard = 0;

    {
        std::lock_guard<std::mutex> lock(state_lock_);
        spacing = state_.vendor_spacing;
        region = state_.vendor_region;
        lower = state_.lower_band_khz;
        upper = state_.upper_band_khz;
        stereo = state_.audio.mode_stereo ? 1 : 0;
        soft_mute = state_.audio.soft_mute ? 1 : 0;
        antenna = state_.antenna;
        emphasis = state_.emphasis;
        rds_standard = state_.rds_standard;
    }

    log("setup", "runtime region=%d lower_khz=%d upper_khz=%d spacing=%d emphasis=%d rds=%d stereo=%d soft_mute=%d antenna=%d",
            region, lower, upper, spacing, emphasis, rds_standard, stereo, soft_mute, antenna);

    if (
        !vendor_set(kV4l2CtrlEmphasis, emphasis, "failed to set emphasis") ||
        !vendor_set(kV4l2CtrlRdsStandard, rds_standard, "failed to set rds standard") ||
        !vendor_set(kV4l2CtrlChannelSpacing, spacing, "failed to set channel spacing") ||
        !vendor_set(kV4l2CtrlIrisUpperBand, upper, "failed to set upper band") ||
        !vendor_set(kV4l2CtrlIrisLowerBand, lower, "failed to set lower band") ||
        !vendor_set(kV4l2CtrlRegion, region, "failed to set region") ||
        !vendor_set(kV4l2CtrlIrisAudioMode, stereo, "failed to set stereo mode") ||
        !vendor_set(kV4l2CtrlAntenna, antenna, "failed to set antenna") ||
        !apply_signal_threshold() ||
        !vendor_set(kV4l2CtrlSoftMute, soft_mute, "failed to set soft mute")
    ) {
        return false;
    }

    std::lock_guard<std::mutex> lock(state_lock_);
    state_.audio.mode_valid = true;
    return true;
}

// Arms async event masks via power transition and enables processed RDS groups.
bool HalHeliumBackend::apply_post_enable_config() {
    bool auto_af = false;
    bool low_power = false;
    int rds_standard = 0;

    {
        std::lock_guard<std::mutex> lock(state_lock_);
        auto_af = state_.auto_af;
        low_power = state_.low_power;
        rds_standard = state_.rds_standard;
    }

    if (!apply_raw_rds_group_mask()) {
        return false;
    }

    if (low_power) {
        if (!vendor_set(kV4l2CtrlLowPowerMode, 1, "failed to set power mode")) {
            return false;
        }
    } else {
        // Helium initializes its cached power mode to normal and only sends the
        // async event mask when that value changes. Force a low-to-normal edge
        // so stereo, RDS sync, and signal interrupts are armed on first enable.
        // Keep this best-effort because the receiver still works without async
        // transitions, and always attempt to restore the requested normal mode.
        // Reference: https://github.com/LineageOS/android_hardware_qcom_fm/blob/3253b05eba14f36a90058da25627013e354d4f76/helium/radio_helium_hal.c#L1183-L1213
        log("setup", "arm async events through low-to-normal transition");
        vendor_set(kV4l2CtrlLowPowerMode, 1, "failed to enter low power while arming async events");
        vendor_set(kV4l2CtrlLowPowerMode, 0, "failed to restore normal power while arming async events");
    }

    return apply_processed_rds_config(auto_af, rds_standard);
}

// Logs a diagnostic snapshot of key vendor FM control values.
bool HalHeliumBackend::log_snapshot(const char *reason) {
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
    };

    bool ok = true;
    log("snap", "reason=%s", reason ? reason : "(null)");
    for (const CtlProbe &probe : probes) {
        int value = 0;
        const bool probe_ok = vendor_get(probe.id, &value, "failed snapshot read");
        ok &= probe_ok;
        log("snap", "%-9s id=0x%07x value=%d (ok=%d)", probe.name, probe.id, value, probe_ok ? 1 : 0);
    }

    return ok;
}

// Loads fm_helium.so, resolves FM_HELIUM_LIB_INTERFACE, and calls hal_init().
bool HalHeliumBackend::init() {
    std::unique_lock<std::mutex> lock(state_lock_);
    if (state_.initialized) {
        clear_error_locked();
        return set_status(true);
    }

    apply_region_profile_locked(state_.region);
    state_.vendor_spacing = map_spacing_to_vendor(state_.spacing_khz);

    state_.lib_handle = dlopen(kFmLibraryName, RTLD_NOW);
    if (state_.lib_handle == nullptr) {
        const char *err = dlerror();
        log("open", "dlopen failed for %s: %s", kFmLibraryName, err ? err : "(unknown)");
        set_error_locked(err ? err : "dlopen failed");
        lock.unlock();
        return set_status(false);
    }

    state_.vendor = reinterpret_cast<fm_interface_t *>(dlsym(state_.lib_handle, kFmLibrarySymbol));
    if (state_.vendor == nullptr) {
        const char *err = dlerror();
        log("open", "dlsym failed for %s: %s", kFmLibrarySymbol, err ? err : "(unknown)");
        set_error_locked(err ? err : "dlsym failed");
        dlclose(state_.lib_handle);
        state_.lib_handle = nullptr;
        lock.unlock();
        return set_status(false);
    }

    const int ret = state_.vendor->hal_init(&kCallbacks);
    if (ret != 0) {
        set_error_locked("hal_init failed");
        dlclose(state_.lib_handle);
        state_.lib_handle = nullptr;
        state_.vendor = nullptr;
        lock.unlock();
        return set_status(false);
    }

    state_.initialized = true;
    clear_error_locked();
    return set_status(true);
}

// Stores startup region, spacing, audio, antenna, and AF preferences before enable.
bool HalHeliumBackend::configure_startup(const StartupConfig &config) {
    std::lock_guard<std::mutex> lock(state_lock_);
    apply_region_profile_locked(config.region);
    state_.spacing_khz = config.spacing_khz;
    state_.vendor_spacing = map_spacing_to_vendor(config.spacing_khz);
    state_.audio.mode_stereo = config.stereo;
    state_.audio.mode_valid = false;
    state_.audio.soft_mute = config.soft_mute;
    state_.antenna = config.antenna;
    state_.auto_af = config.auto_af;
    return true;
}

// Waits up to timeout_ms for enabled_cb after requesting STATE=RX.
bool HalHeliumBackend::wait_enabled(int timeout_ms) {
    std::unique_lock<std::mutex> lock(state_lock_);
    const bool ready = state_.cond.wait_for(
            lock,
            std::chrono::milliseconds(timeout_ms),
            [&] { return state_.enabled; }
    );
    if (!ready) {
        set_error_locked("timed out waiting for enabled_cb");
        return false;
    }
    clear_error_locked();
    return true;
}

// Powers on the Helium receiver and applies runtime/RDS configuration.
bool HalHeliumBackend::enable_receiver() {
    log("enable", "requested");
    if (!init()) {
        return false;
    }

    {
        std::lock_guard<std::mutex> lock(state_lock_);
        state_.enabled = false;
        state_.last_enabled_cb_ms = 0;
        state_.receiver_cleanup_needed = true;
        state_.disable_pending = false;
        state_.rmssi.clear_request();
        state_.rmssi.paused = true;
        reset_scan_locked();
    }

    if (!set_slimbus(true)) {
        log("enable", "failed to enable slimbus before receiver state");
    }

    if (!vendor_set(kV4l2CtrlState, kFmRxState, "failed to enable receiver")) {
        return false;
    }

    if (!wait_enabled(kEnableWaitTimeoutMs)) {
        return false;
    }

    if (!apply_runtime_config()) {
        return false;
    }

    log_snapshot("after-runtime-config");
    if (!apply_post_enable_config()) {
        return false;
    }
    log("enable", "accepted");
    return true;
}

// Enables the FM receiver with the given startup configuration and initial frequency.
bool HalHeliumBackend::enable(const StartupConfig &config) {
    if (!set_status(configure_startup(config))) {
        return false;
    }

    if (!set_status(enable_receiver())) {
        return rollback_enable();
    }

    if (!set_status(set_frequency(config.frequency_khz))) {
        return rollback_enable();
    }
    return true;
}

// Requests asynchronous receiver disable and turns off Slimbus transport.
bool HalHeliumBackend::disable() {
    log("disable", "requested");
    {
        std::lock_guard<std::mutex> lock(state_lock_);
        if (!state_.receiver_cleanup_needed || state_.disable_pending) {
            return set_status(true);
        }
        state_.disable_pending = true;
        state_.rmssi.paused = true;
        state_.rmssi.clear_request();
    }

    const bool disabled = vendor_set(kV4l2CtrlState, 0, "failed to disable receiver");
    if (!disabled) {
        std::lock_guard<std::mutex> lock(state_lock_);
        state_.disable_pending = false;
    }
    const bool slimbus = set_slimbus(false);
    const bool accepted = disabled && slimbus;

    if (accepted) {
        log("disable", "accepted");
    }

    return set_status(accepted);
}

// Initiates disable if needed and waits up to timeout_ms for disabled_cb.
bool HalHeliumBackend::shutdown_with_timeout(int timeout_ms) {
    // Also handles an earlier disable/enable rollback still waiting for its
    // callback. Do not send another OFF to a HAL that is already closing.
    disable();

    {
        std::unique_lock<std::mutex> lock(state_lock_);
        const bool cleaned_up = state_.cond.wait_for(
                lock,
                std::chrono::milliseconds(timeout_ms),
                [&] { return !state_.receiver_cleanup_needed; }
        );
        if (!cleaned_up) {
            set_error_locked("timed out waiting for disabled_cb");
            lock.unlock();
            return set_status(false);
        }
        clear_error_locked();
    }
    log("shutdown", "receiver cleanup complete");
    return set_status(true);
}

// Performs process teardown and waits for Helium receiver cleanup.
void HalHeliumBackend::shutdown() {
    shutdown_with_timeout(kShutdownWaitTimeoutMs);
}

// Tunes the receiver to a specific frequency in kHz.
bool HalHeliumBackend::set_frequency(uint32_t frequency_khz) {
    log("tune", "request frequency_khz=%u", frequency_khz);

    {
        std::lock_guard<std::mutex> lock(state_lock_);
        state_.rmssi.paused = true;
    }

    if (!vendor_set(kV4l2CtrlIrisFreq, static_cast<int>(frequency_khz), "failed to tune")) {
        {
            std::lock_guard<std::mutex> lock(state_lock_);
            state_.rmssi.paused = false;
        }
        return set_status(false);
    }
    log("tune", "request frequency_khz=%u accepted", frequency_khz);

    std::lock_guard<std::mutex> lock(state_lock_);
    state_.current_frequency_khz = static_cast<int>(frequency_khz);
    return set_status(true);
}

// Moves frequency by one channel step in the requested direction.
bool HalHeliumBackend::jump(int direction, uint32_t *new_frequency) {
    int step = 0;
    int lower = 0;
    int upper = 0;
    {
        std::lock_guard<std::mutex> lock(state_lock_);
        step = state_.spacing_khz;
        lower = state_.lower_band_khz;
        upper = state_.upper_band_khz;
    }

    int freq = 0;
    uint32_t current_u = 0;
    if (!vendor_get(kV4l2CtrlIrisFreq, &freq, "failed to read frequency")) {
        int cached = 0;
        {
            std::lock_guard<std::mutex> lock(state_lock_);
            cached = state_.current_frequency_khz;
        }
        log("tune", "get frequency failed, using cached (frequency_khz=%d)", cached);
        current_u = cached > 0 ? static_cast<uint32_t>(cached) : 0;
    } else if (freq <= 0) {
        int cached = 0;
        {
            std::lock_guard<std::mutex> lock(state_lock_);
            cached = state_.current_frequency_khz;
            clear_error_locked();
        }
        log("tune", "invalid frequency_khz=%d, using cached (frequency_khz=%d)", freq, cached);
        current_u = cached > 0 ? static_cast<uint32_t>(cached) : 0;
    } else {
        {
            std::lock_guard<std::mutex> lock(state_lock_);
            state_.current_frequency_khz = freq;
            clear_error_locked();
        }
        current_u = static_cast<uint32_t>(freq);
    }

    const int current = static_cast<int>(current_u);
    if (current <= 0) {
        return set_status(false);
    }

    int target = current + (step * (direction >= 0 ? 1 : -1));
    if (target > upper) {
        target = lower;
    } else if (target < lower) {
        target = upper;
    }

    if (!set_frequency(static_cast<uint32_t>(target))) {
        return false;
    }

    if (new_frequency != nullptr) {
        *new_frequency = static_cast<uint32_t>(target);
    }
    return true;
}

// Starts hardware seek up or down.
bool HalHeliumBackend::seek(int direction) {
    log("search", "seek requested direction=%d dwell=%d", direction, kDefaultSeekDwell);

    {
        std::unique_lock<std::mutex> lock(state_lock_);
        if (state_.scan.active || state_.scan.seeking) {
            set_error_locked("search is already active");
            log("search", "seek rejected because another search is active");
            lock.unlock();
            return set_status(false);
        }
        state_.scan.seeking = true;
        state_.rmssi.paused = true;
    }

    const bool ok = vendor_set(kV4l2CtrlSearchMode, kSearchModeSeek, "failed to set seek mode") &&
                    vendor_set(kV4l2CtrlScanDwell, kDefaultSeekDwell, "failed to set seek dwell") &&
                    vendor_set(kV4l2CtrlIrisSeek, direction >= 0 ? 1 : 0, "failed to start seek");
    if (ok) {
        log("search", "seek direction=%d accepted", direction);
    } else {
        std::lock_guard<std::mutex> lock(state_lock_);
        state_.scan.seeking = false;
        state_.rmssi.paused = false;
    }
    return set_status(ok);
}

// Starts sequential full-band scan from the lower band edge.
bool HalHeliumBackend::search() {
    int start_frequency = 0;
    {
        std::unique_lock<std::mutex> lock(state_lock_);
        if (state_.scan.active || state_.scan.seeking) {
            set_error_locked("search is already active");
            log("search", "scan rejected because another search is active");
            lock.unlock();
            return set_status(false);
        }
        start_frequency = state_.lower_band_khz;
        reset_search_result_locked();
        reset_scan_locked();
        state_.scan.active = true;
        state_.scan.start_frequency_khz = start_frequency;
        state_.scan.last_frequency_khz = start_frequency;
    }

    log("search", "scan start frequency_khz=%d", start_frequency);

    const bool ok = vendor_set(kV4l2CtrlIrisFreq, start_frequency, "failed to tune scan start") &&
                    vendor_set(kV4l2CtrlSearchMode, kSearchModeScan, "failed to set scan mode") &&
                    vendor_set(kV4l2CtrlScanDwell, 1, "failed to set scan dwell") &&
                    vendor_set(kV4l2CtrlIrisSeek, 1, "failed to start scan");
    if (!ok) {
        std::lock_guard<std::mutex> lock(state_lock_);
        reset_scan_locked();
    }
    return set_status(ok);
}

// Cancels ongoing scan or seek operation.
bool HalHeliumBackend::cancel_search() {
    log("search", "cancel requested");
    {
        std::lock_guard<std::mutex> lock(state_lock_);
        reset_scan_locked();
    }
    return set_status(vendor_set(kV4l2CtrlSearchOn, 0, "failed to cancel search"));
}

// Sets stereo vs mono audio reception, skipping duplicate requests.
bool HalHeliumBackend::set_stereo(bool enabled) {
    bool already_set = false;
    {
        std::lock_guard<std::mutex> lock(state_lock_);
        already_set = state_.audio.mode_valid && state_.audio.mode_stereo == enabled;
        state_.audio.mode_valid = true;
        state_.audio.mode_stereo = enabled;
    }

    if (already_set) {
        log("audio", "set stereo=%d skipped", enabled ? 1 : 0);
        return set_status(true);
    }

    log("audio", "set stereo=%d", enabled ? 1 : 0);
    return set_status(vendor_set(kV4l2CtrlIrisAudioMode, enabled ? 1 : 0, "failed to set stereo mode"));
}

// Sets channel spacing in kHz.
bool HalHeliumBackend::set_spacing(int spacing_khz) {
    log("setup", "set spacing_khz=%d", spacing_khz);
    int vendor_spacing = 0;
    {
        std::lock_guard<std::mutex> lock(state_lock_);
        state_.spacing_khz = spacing_khz;
        state_.vendor_spacing = map_spacing_to_vendor(spacing_khz);
        vendor_spacing = state_.vendor_spacing;
    }
    return set_status(vendor_set(kV4l2CtrlChannelSpacing, vendor_spacing, "failed to set spacing"));
}

// Configures radio region, band bounds, emphasis, and clamps current frequency if needed.
bool HalHeliumBackend::set_region(StartupRegion startup_region) {
    log("setup", "set region=%s", get_region_name(startup_region));
    int region = 0;
    int lower = 0;
    int upper = 0;
    int emphasis = 0;
    int rds_standard = 0;
    int current_frequency = 0;

    {
        std::lock_guard<std::mutex> lock(state_lock_);
        apply_region_profile_locked(startup_region);
        region = state_.vendor_region;
        lower = state_.lower_band_khz;
        upper = state_.upper_band_khz;
        emphasis = state_.emphasis;
        rds_standard = state_.rds_standard;
        current_frequency = state_.current_frequency_khz;
    }

    log("setup", "apply region=%d lower_khz=%d upper_khz=%d emphasis=%d rds=%d",
            region, lower, upper, emphasis, rds_standard);
    const bool configured =
            vendor_set(kV4l2CtrlIrisUpperBand, upper, "failed to set upper band") &&
            vendor_set(kV4l2CtrlIrisLowerBand, lower, "failed to set lower band") &&
            vendor_set(kV4l2CtrlEmphasis, emphasis, "failed to set emphasis") &&
            vendor_set(kV4l2CtrlRdsStandard, rds_standard, "failed to set rds standard") &&
            vendor_set(kV4l2CtrlRegion, region, "failed to set region");
    if (!configured || current_frequency <= 0) {
        return set_status(configured);
    }

    const RegionProfile &profile = get_region_profile(startup_region);
    const uint32_t target_frequency = clamp_frequency_to_region(profile, static_cast<uint32_t>(current_frequency));
    if (target_frequency == static_cast<uint32_t>(current_frequency)) {
        return set_status(true);
    }
    return set_frequency(target_frequency);
}

// Sets antenna type.
bool HalHeliumBackend::set_antenna(int antenna) {
    log("setup", "set antenna=%d", antenna);
    {
        std::lock_guard<std::mutex> lock(state_lock_);
        state_.antenna = antenna;
    }
    return set_status(vendor_set(kV4l2CtrlAntenna, antenna, "failed to set antenna"));
}

// Sets power mode (normal vs low power).
bool HalHeliumBackend::set_power_mode(bool low_power) {
    log("setup", "set low_power=%d", low_power ? 1 : 0);
    {
        std::lock_guard<std::mutex> lock(state_lock_);
        state_.low_power = low_power;
    }
    return set_status(vendor_set(kV4l2CtrlLowPowerMode, low_power ? 1 : 0, "failed to set power mode"));
}

// Enables or disables automatic AF jump.
bool HalHeliumBackend::set_auto_af(bool enabled) {
    log("af", "set auto=%d", enabled ? 1 : 0);
    {
        std::lock_guard<std::mutex> lock(state_lock_);
        state_.auto_af = enabled;
    }
    return set_status(vendor_set(kV4l2CtrlAfJump, enabled ? 1 : 0, "failed to set auto af"));
}

// Enables or disables soft mute.
bool HalHeliumBackend::set_soft_mute(bool enabled) {
    log("audio", "set soft_mute=%d", enabled ? 1 : 0);
    if (!vendor_set(kV4l2CtrlSoftMute, enabled ? 1 : 0, "failed to set soft mute")) {
        return set_status(false);
    }

    std::lock_guard<std::mutex> lock(state_lock_);
    state_.audio.soft_mute = enabled;
    return set_status(true);
}

// Configures hardware notch filter (0 = auto, 1 = low, 2 = high).
bool HalHeliumBackend::set_notch_filter(int mode) {
    if (mode < 0 || mode > 2) {
        {
            std::lock_guard<std::mutex> lock(state_lock_);
            set_error_locked("invalid notch filter mode");
        }
        return set_status(false);
    }

    log("audio", "set notch_filter=%d", mode);
    return set_status(vendor_set(kV4l2CtrlSetNotchFilter, mode, "failed to set notch filter"));
}

// Enables or disables Slimbus digital audio transport.
bool HalHeliumBackend::set_slimbus(bool enabled) {
    log("setup", "set slimbus=%d", enabled ? 1 : 0);
    if (!vendor_set(kV4l2CtrlEnableSlimbus, enabled ? 1 : 0, "failed to set slimbus")) {
        return set_status(false);
    }

    std::lock_guard<std::mutex> lock(state_lock_);
    state_.audio.slimbus_enabled = enabled;
    return set_status(true);
}

// Issues a single non-overlapping asynchronous SINR or RMSSI request.
HeliumMetricRequestResult HalHeliumBackend::request_metric_internal(
        int ctrl_id,
        HeliumMetricType type,
        const char *name
) {
    const uint64_t requested_at = monotonic_ms();

    fm_interface_t *vendor = nullptr;
    {
        std::lock_guard<std::mutex> lock(state_lock_);

        if (
                !state_.enabled ||
                state_.scan.active ||
                state_.rmssi.paused ||
                state_.vendor == nullptr
        ) {
            return HeliumMetricRequestResult::kSkipped;
        }

        if (state_.rmssi.type != HeliumMetricType::kNone) {
            const uint64_t elapsed = requested_at - state_.rmssi.requested_at_ms;

            if (
                    elapsed >= kRmssiRequestTimeoutMs &&
                    !state_.rmssi.timeout_logged
            ) {
                log("metric", "request still pending after %llu ms", static_cast<unsigned long long>(elapsed));
                state_.rmssi.timeout_logged = true;
            }

            return HeliumMetricRequestResult::kSkipped;
        }

        state_.rmssi.type = type;
        state_.rmssi.requested_at_ms = requested_at;
        state_.rmssi.request_frequency_khz = state_.current_frequency_khz;
        state_.rmssi.timeout_logged = false;
        vendor = state_.vendor;
    }

    int unused = 0;
    errno = 0;

    const int ret = vendor->get_fm_ctrl(ctrl_id, &unused);
    if (ret < 0) {
        const int saved_errno = errno;

        {
            std::lock_guard<std::mutex> lock(state_lock_);
            if (state_.rmssi.type == type) {
                state_.rmssi.clear_request();
            }
        }

        log("vendor", "get %s id=0x%x failed, ret=%d errno=%d (%s)",
                name, ctrl_id, ret, saved_errno, saved_errno ? strerror(saved_errno) : "none");
        return HeliumMetricRequestResult::kUnsupported;
    }

    log("metric", "requested %s", name);
    return HeliumMetricRequestResult::kOk;
}

// Requests SINR measurement from the vendor HAL.
HeliumMetricRequestResult HalHeliumBackend::request_sinr() {
    return request_metric_internal(kV4l2CtrlIrisSinr, HeliumMetricType::kSinr, "sinr");
}

// Requests RMSSI measurement from the vendor HAL.
HeliumMetricRequestResult HalHeliumBackend::request_rmssi() {
    return request_metric_internal(kV4l2CtrlIrisRmssi, HeliumMetricType::kRmssi, "rmssi");
}

// Polls signal quality metrics, falling back from SINR to RMSSI if unsupported.
bool HalHeliumBackend::request_signal_metric() {
    if (sinr_supported_) {
        const HeliumMetricRequestResult res = request_sinr();

        if (res == HeliumMetricRequestResult::kOk) {
            return true;
        }

        if (res == HeliumMetricRequestResult::kSkipped) {
            return false;
        }
        log("hal", "SINR not supported by HAL vendor interface, falling back to RMSSI");
        sinr_supported_ = false;
        radio_state_patch_t patch = radio_state_patch_empty();
        patch.sinr = RADIO_PATCH_CLEAR_METRIC;
        send_radio_state_patch(&patch);
    }

    return request_rmssi() == HeliumMetricRequestResult::kOk;
}

// Handles enabled_cb and wakes wait_enabled().
void HalHeliumBackend::on_enabled() {
    log("event", "FM enabled");
    std::lock_guard<std::mutex> lock(state_lock_);
    state_.enabled = true;
    state_.last_enabled_cb_ms = monotonic_ms();
    state_.cond.notify_all();
}

// Handles disabled_cb, wakes shutdown waiters, and emits the disabled event.
void HalHeliumBackend::on_disabled() {
    log("event", "FM disabled");

    {
        std::lock_guard<std::mutex> lock(state_lock_);
        state_.enabled = false;
        state_.receiver_cleanup_needed = false;
        state_.disable_pending = false;
        state_.audio.mode_valid = false;
        state_.rmssi.clear_request();
        state_.rmssi.paused = true;
        reset_scan_locked();
        state_.cond.notify_all();
    }

    send_native_event("disabled");
}

// Handles tune_cb, clears stale RDS state, publishes frequency, and requests SINR.
void HalHeliumBackend::on_tune(int freq) {
    log("event", "tune frequency_khz=%d", freq);
    {
        std::lock_guard<std::mutex> lock(state_lock_);
        state_.current_frequency_khz = freq;
        state_.rmssi.paused = false;
        state_.last_tune_cb_ms = monotonic_ms();
        reset_search_result_locked();
        reset_rds_dedup_locked();
    }
    radio_state_patch_t patch = make_station_metadata_reset_patch();
    patch.frequency_khz = freq;
    send_radio_state_patch(&patch);

    // The tune callback is the first authoritative point at which signal metric belongs
    // to the new station. Refresh now using the active metric policy instead of waiting
    // for the periodic tick.
    request_signal_metric();
}

// Handles seek_cmpl_cb for both ordinary seeks and terminal scan completion.
void HalHeliumBackend::on_seek_complete(int freq) {
    int current = 0;
    if (vendor_get(kV4l2CtrlIrisFreq, &current, "failed to read seek-complete frequency")) {
        log("event", "seek complete frequency_khz=%d current_frequency_khz=%d", freq, current);
    } else {
        log("event", "seek complete frequency_khz=%d", freq);
    }

    ScanResult result;
    bool scan_complete = false;
    bool seek_complete = false;

    {
        std::lock_guard<std::mutex> lock(state_lock_);
        // Helium reports terminal SEARCH_COMPLETE through the seek callback and may
        // repeat it, so claim the active scan or seek under the lock before sending results.
        if (state_.scan.active) {
            scan_complete = true;
            state_.scan.active = false;
            state_.scan.stations.copy_sorted(&result);
        } else if (state_.scan.seeking) {
            seek_complete = true;
            state_.scan.seeking = false;
        }

        if (scan_complete || seek_complete) {
            state_.current_frequency_khz = freq;
            state_.rmssi.paused = false;
            state_.last_seek_cb_ms = monotonic_ms();
            reset_search_result_locked();
            reset_rds_dedup_locked();
        }
    }

    if (scan_complete) {
        const std::string frequencies = format_frequency_list_khz(result.frequencies_khz, result.count);
        log("search", "scan complete source=seek_complete count=%d frequencies_khz=%s",
                result.count, frequencies.c_str());
        send_search_done(result.frequencies_khz, result.count);
        return;
    }

    if (!seek_complete) {
        log("search", "ignoring seek complete without active seek (frequency_khz=%d)", freq);
        return;
    }

    radio_state_patch_t patch = make_station_metadata_reset_patch();
    patch.frequency_khz = freq;
    send_radio_state_patch(&patch);
    send_native_event("seek_done");
}

// Handles scan_next_cb, collecting found frequencies and checking wrap completion.
void HalHeliumBackend::on_scan_next() {
    int freq = 0;
    if (vendor_get(kV4l2CtrlIrisFreq, &freq, "failed to read scan-next frequency")) {
        log("search", "scan next frequency_khz=%d", freq);
    } else {
        log("search", "scan next");
        return;
    }

    ScanResult result;
    bool complete = false;
    int progress_count = 0;

    {
        std::lock_guard<std::mutex> lock(state_lock_);
        if (state_.scan.active) {
            const ScanAddResult add_result = state_.scan.stations.add(freq);
            if (add_result == ScanAddResult::kAdded) {
                progress_count = state_.scan.stations.count();
            } else if (add_result == ScanAddResult::kFull) {
                log("search", "scan result full, dropping frequency_khz=%d", freq);
            }
            if (state_.scan.last_frequency_khz > 0 && freq < state_.scan.last_frequency_khz) {
                state_.scan.wrapped = true;
            }
            state_.scan.last_frequency_khz = freq;

            if (state_.scan.wrapped && freq >= state_.scan.start_frequency_khz) {
                complete = true;
                state_.scan.active = false;
                state_.scan.stations.copy_sorted(&result);
            }
        }
    }

    if (!complete) {
        if (progress_count > 0) {
            send_search_progress(progress_count);
        }
        return;
    }

    vendor_set(kV4l2CtrlSearchOn, 0, "failed to stop completed scan");
    const std::string frequencies = format_frequency_list_khz(result.frequencies_khz, result.count);

    log("search", "scan complete count=%d frequencies_khz=%s", result.count, frequencies.c_str());
    send_search_done(result.frequencies_khz, result.count);
}

/*
 * Search list:
 * [0]=station count
 * [1..]=big-endian 16-bit relative offsets.
 *
 * Offsets are measured from the current lower band edge in 50 kHz units.
 */
void HalHeliumBackend::on_search_list(uint16_t *raw) {
    if (raw == nullptr) {
        return;
    }

    int lower_band = 87500;
    {
        std::lock_guard<std::mutex> lock(state_lock_);
        lower_band = state_.lower_band_khz;
    }

    RdsSearchList list;
    if (!parse_rds_search_list_payload(
            reinterpret_cast<const unsigned char *>(raw),
            kUnknownRdsPayloadLen,
            RdsSearchListKind::kRelativeOffsets,
            lower_band,
            &list
    )) {
        log("search", "invalid result");
        return;
    }

    if (list.count <= 0) {
        bool duplicate = false;
        {
            std::lock_guard<std::mutex> lock(state_lock_);
            duplicate = state_.last_search_payload_valid && state_.last_search_payload[0] == '\0';
            state_.last_search_payload_valid = true;
            state_.last_search_payload[0] = '\0';
        }
        if (duplicate) {
            return;
        }

        log("search", "empty result");
        send_search_done(nullptr, 0);
        return;
    }

    const std::string frequencies = format_frequency_list_khz(list.frequencies_khz, list.count);

    // Qualcomm HAL can replay the last search-list callback after the real
    // search result, typically while processing the next command-complete
    // event. The callback buffer is malloc/free-owned by the HAL, so dedupe by
    // decoded payload instead of pointer or event ordering.
    bool duplicate = false;
    {
        std::lock_guard<std::mutex> lock(state_lock_);
        duplicate = state_.last_search_payload_valid &&
                strcmp(state_.last_search_payload, frequencies.c_str()) == 0;
        if (!duplicate) {
            snprintf(state_.last_search_payload, sizeof(state_.last_search_payload), "%s", frequencies.c_str());
            state_.last_search_payload_valid = true;
        }
    }
    if (duplicate) {
        return;
    }

    log("search", "result count=%d reported=%d frequencies_khz=%s",
            list.count, list.reported_count, frequencies.c_str());
    send_search_done(list.frequencies_khz, list.count);
}

// Handles stereo_status_cb and publishes stereo state patches.
void HalHeliumBackend::on_stereo_status(bool stereo) {
    log("event", "stereo=%d", stereo);
    {
        std::lock_guard<std::mutex> lock(state_lock_);
        state_.audio.stereo = stereo;
    }
    radio_state_patch_t patch = radio_state_patch_empty();
    patch.stereo = stereo ? 1 : 0;
    send_radio_state_patch(&patch);
}

// Logs RDS synchronization lock transitions.
void HalHeliumBackend::on_rds_avail_status(bool rds_available) {
    log("event", "rds_sync=%d", rds_available ? 1 : 0);
}

/*
 * AF list:
 * [0..3]=tuned frequency
 * [4..5]=PI
 * [6]=AF count,
 * [7..]=int32 kHz frequency entries from the vendor HAL.
 */
void HalHeliumBackend::on_af_update(uint16_t *raw) {
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
        log("af", "invalid size=%u", raw_af->af_size);
        return;
    }

    const std::string frequencies = format_frequency_list_khz(af.frequencies_khz, af.count);

    log("af", "result count=%d frequencies_khz=%s", af.count, frequencies.c_str());
    radio_state_patch_t patch = radio_state_patch_empty();
    patch.af_khz = af.frequencies_khz;
    patch.af_count = af.count;
    send_radio_state_patch(&patch);
}

/*
 * RT:
 * [0]=text length
 * [1..4]=metadata/header
 * [5..]=RadioText.
 *
 * The HAL-provided length is clamped to the RDS 64-character RT limit before copying.
 */
void HalHeliumBackend::on_rt_update(char *rt) {
    if (rt == nullptr) {
        log("rds", "rt update got null");
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
        log("rds", "invalid rt len=%d", static_cast<unsigned char>(rt[0]) & kRdsRtLengthMask);
        return;
    }

    RdsRtPlusSlices slices;
    {
        std::lock_guard<std::mutex> lock(state_lock_);
        state_.rt_plus.set_text(parsed);
        state_.rt_plus.log_slices();
        slices = state_.rt_plus.slices();
    }

    log("rds", "rt text=`%s` (len=%d)", parsed.text, parsed.text_len);
    radio_state_patch_t patch = radio_state_patch_empty();
    patch.rt = parsed.text;
    patch.rt_plus_title = slices.title;
    patch.rt_plus_artist = slices.artist;
    patch.rt_plus_album = slices.album;
    send_radio_state_patch(&patch);
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
void HalHeliumBackend::on_ps_update(char *ps) {
    if (ps == nullptr) {
        log("rds", "ps update got null");
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
        log("rds", "invalid ps count=%d", static_cast<unsigned char>(ps[0]) & kRdsPsCountMask);
        return;
    }

    log("rds", "ps count=%d pi=%04x pty=%d text=`%s` (len=%d)",
            parsed.block_count, parsed.pi, parsed.pty, parsed.text, parsed.text_len);
    char pi[16];
    snprintf(pi, sizeof(pi), "%x", parsed.pi);
    radio_state_patch_t patch = radio_state_patch_empty();
    patch.ps = parsed.text;
    patch.pty = parsed.pty;
    patch.pi = pi;
    send_radio_state_patch(&patch);
}

// Logs ODA group 3A discovery notification.
void HalHeliumBackend::on_oda_update() {
    log("rds", "oda_update=1");
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
void HalHeliumBackend::on_rt_plus_update(char *payload) {
    if (payload == nullptr) {
        log("rds", "rt_plus_update_cb got null");
        return;
    }

    const int len = static_cast<unsigned char>(payload[0]);
    bool duplicate = false;
    {
        std::lock_guard<std::mutex> lock(state_lock_);
        duplicate = is_duplicate_payload_locked(
                &state_.last_rt_plus_valid, state_.last_rt_plus, sizeof(state_.last_rt_plus), payload, len);
    }
    if (duplicate) {
        return;
    }

    char dump[3 * 32 + 1];
    format_hex_dump(payload, len, dump, sizeof(dump));

    RdsRtPlusPayload parsed;

    if (parse_rds_rt_plus_payload(
            reinterpret_cast<const unsigned char *>(payload),
            len,
            RdsRtPlusKind::kHelium,
            &parsed
    )) {
        const int pty = rds_decode_pty(static_cast<unsigned char>(payload[1]));
        const int pi = rds_read_be16(payload, 2);
        const int flags =
                (static_cast<unsigned char>(payload[4]) << 24) |
                (static_cast<unsigned char>(payload[5]) << 16) |
                (static_cast<unsigned char>(payload[6]) << 8) |
                static_cast<unsigned char>(payload[7]);

        RdsRtPlusSlices slices;
        {
            std::lock_guard<std::mutex> lock(state_lock_);
            state_.rt_plus.set_tags(parsed);
            state_.rt_plus.log_slices();
            slices = state_.rt_plus.slices();
        }

        log("rds", "rt+ len=%d pi=%04x pty=%d flags=0x%08x toggle=%d running=%d tags=%d data=%s%s",
                len, pi, pty, flags, parsed.toggle, parsed.running, parsed.tag_count,
                dump, len > 32 ? " ..." : "");

        radio_state_patch_t patch = radio_state_patch_empty();
        patch.rt_plus_title = slices.title;
        patch.rt_plus_artist = slices.artist;
        patch.rt_plus_album = slices.album;
        send_radio_state_patch(&patch);
        return;
    }

    if (len >= 4) {
        const int pty = rds_decode_pty(static_cast<unsigned char>(payload[1]));
        const int pi = rds_read_be16(payload, 2);
        log("rds", "rt+ len=%d pi=%04x pty=%d data=%s%s",
                len, pi, pty, dump, len > 32 ? " ..." : "");
        return;
    }

    log("rds", "rt+ len=%d data=%s%s", len, dump, len > 32 ? " ..." : "");
}

// Logs eRT UTF-16 payload diagnostics.
void HalHeliumBackend::on_ert_update(char *payload) {
    log_length_prefixed_payload("rds", "ert_update_cb", payload, 3);
}

/*
 * ECC callback carries RDS group 1A:
 * [0]=12
 * [1]=PTY
 * [2..3]=PI
 * [4]=TA/TP header byte (firmware-side)
 * [5]=1A variant
 * [6..7]=flags/reserved
 * [8..9]=slow labelling
 * [10..11]=block D.
 *
 * The slow-labelling low byte is ECC only when variant == 0. Other variants
 * are broadcaster/vendor data and are ignored to avoid log spam. Parsing is
 * delegated to parse_rds_ecc_payload(), which reads the variant from [5]
 * only; the TA/TP header byte at [4] stays outside the variant gate
 * (issue #102).
 */
void HalHeliumBackend::on_ext_country_code(char *payload) {
    if (payload == nullptr) {
        log("rds", "ext_country_code_cb got null");
        return;
    }

    const int len = static_cast<unsigned char>(payload[0]);
    bool duplicate = false;
    char country[3] = "";
    bool has_country = false;
    {
        std::lock_guard<std::mutex> lock(state_lock_);
        duplicate = is_duplicate_payload_locked(
                &state_.last_ecc_valid,
                state_.last_ecc,
                sizeof(state_.last_ecc),
                payload,
                len
        );
        if (!duplicate) {
            has_country = parse_rds_ecc_payload(
                    reinterpret_cast<const unsigned char *>(payload),
                    len,
                    country
            );
        }
    }
    if (duplicate) {
        return;
    }

    char dump[3 * 32 + 1];
    format_hex_dump(payload, len, dump, sizeof(dump));

    if (len >= 12) {
        const int pty = rds_decode_pty(static_cast<unsigned char>(payload[1]));
        const int pi = rds_read_be16(payload, 2);
        const int ta_tp = static_cast<unsigned char>(payload[4]);
        const int variant = static_cast<unsigned char>(payload[5]);
        const int slow_labelling = rds_read_be16(payload, 8);
        const int block_d = rds_read_be16(payload, 10);

        if (has_country) {
            const int country_code = rds_decode_pi_country_code(pi);
            const int ecc = slow_labelling & kRdsByteMask;
            log(
                    "rds",
                    "ecc len=%d pi=%04x pty=%d ta_tp=0x%02x cc=0x%x ecc=0x%02x country=%s sl=0x%04x block_d=0x%04x data=%s%s",
                    len,
                    pi,
                    pty,
                    ta_tp,
                    country_code,
                    ecc,
                    country,
                    slow_labelling,
                    block_d,
                    dump,
                    len > 32 ? " ..." : ""
            );
            radio_state_patch_t patch = radio_state_patch_empty();
            patch.country = country;
            send_radio_state_patch(&patch);
            return;
        }

        log(
                "rds",
                "ecc ignored len=%d pi=%04x pty=%d ta_tp=0x%02x variant=0x%02x sl=0x%04x block_d=0x%04x data=%s%s",
                len,
                pi,
                pty,
                ta_tp,
                variant,
                slow_labelling,
                block_d,
                dump,
                len > 32 ? " ..." : ""
        );
        return;
    }

    log("rds", "ecc len=%d data=%s%s", len, dump, len > 32 ? " ..." : "");
}

// Handles fm_get_station_param_cb for asynchronous SINR/RMSSI responses.
void HalHeliumBackend::on_station_param(int value, int status) {
    HeliumMetricType type = HeliumMetricType::kNone;
    bool publish = false;
    {
        std::lock_guard<std::mutex> lock(state_lock_);

        type = state_.rmssi.type;
        const int requested_frequency = state_.rmssi.request_frequency_khz;
        publish = (type != HeliumMetricType::kNone) &&
                state_.enabled &&
                !state_.rmssi.paused &&
                !state_.scan.active &&
                requested_frequency == state_.current_frequency_khz;

        state_.rmssi.clear_request();
    }

    const unsigned int raw_value = static_cast<unsigned int>(value) & 0xffU;

    if (type == HeliumMetricType::kSinr) {
        const int sinr_val = static_cast<int8_t>(static_cast<unsigned char>(value));
        log("metric", "sinr status=%d val=%d raw=0x%02x", status, sinr_val, raw_value);

        if (status == 0 && publish) {
            radio_state_patch_t patch = radio_state_patch_empty();
            patch.sinr = sinr_val;
            patch.rmssi = RADIO_PATCH_CLEAR_METRIC;
            send_radio_state_patch(&patch);
        } else if (status != 0 && publish) {
            radio_state_patch_t patch = radio_state_patch_empty();
            patch.sinr = RADIO_PATCH_CLEAR_METRIC;
            send_radio_state_patch(&patch);
            request_rmssi();
        }
    } else if (type == HeliumMetricType::kRmssi) {
        const int rmssi_val = decode_rmssi(value);
        log("metric", "rmssi status=%d val=%d raw=0x%02x", status, rmssi_val, raw_value);

        if (status == 0 && publish) {
            radio_state_patch_t patch = radio_state_patch_empty();
            patch.rmssi = rmssi_val;
            patch.sinr = RADIO_PATCH_CLEAR_METRIC;
            send_radio_state_patch(&patch);
        }
    }
}

// Handles enable_slimbus_cb status updates.
void HalHeliumBackend::on_enable_slimbus(int status) {
    log("event", "slimbus status=%d", status);
    std::lock_guard<std::mutex> lock(state_lock_);
    state_.audio.slimbus_enabled = status == 0;
}

void HalHeliumBackend::enabled_cb() {
    if (HalHeliumBackend *self = active_instance_) {
        self->on_enabled();
    }
}

void HalHeliumBackend::disabled_cb() {
    if (HalHeliumBackend *self = active_instance_) {
        self->on_disabled();
    }
}

void HalHeliumBackend::tune_cb(int freq) {
    if (HalHeliumBackend *self = active_instance_) {
        self->on_tune(freq);
    }
}

void HalHeliumBackend::seek_complete_cb(int freq) {
    if (HalHeliumBackend *self = active_instance_) {
        self->on_seek_complete(freq);
    }
}

void HalHeliumBackend::scan_next_cb() {
    if (HalHeliumBackend *self = active_instance_) {
        self->on_scan_next();
    }
}

void HalHeliumBackend::search_list_cb(uint16_t *raw) {
    if (HalHeliumBackend *self = active_instance_) {
        self->on_search_list(raw);
    }
}

void HalHeliumBackend::stereo_status_cb(bool stereo) {
    if (HalHeliumBackend *self = active_instance_) {
        self->on_stereo_status(stereo);
    }
}

void HalHeliumBackend::rds_avail_status_cb(bool rds_available) {
    if (HalHeliumBackend *self = active_instance_) {
        self->on_rds_avail_status(rds_available);
    }
}

void HalHeliumBackend::af_update_cb(uint16_t *raw) {
    if (HalHeliumBackend *self = active_instance_) {
        self->on_af_update(raw);
    }
}

void HalHeliumBackend::rt_update_cb(char *rt) {
    if (HalHeliumBackend *self = active_instance_) {
        self->on_rt_update(rt);
    }
}

void HalHeliumBackend::ps_update_cb(char *ps) {
    if (HalHeliumBackend *self = active_instance_) {
        self->on_ps_update(ps);
    }
}

void HalHeliumBackend::oda_update_cb() {
    if (HalHeliumBackend *self = active_instance_) {
        self->on_oda_update();
    }
}

void HalHeliumBackend::rt_plus_update_cb(char *payload) {
    if (HalHeliumBackend *self = active_instance_) {
        self->on_rt_plus_update(payload);
    }
}

void HalHeliumBackend::ert_update_cb(char *payload) {
    if (HalHeliumBackend *self = active_instance_) {
        self->on_ert_update(payload);
    }
}

void HalHeliumBackend::rds_grp_cntrs_rsp_cb(char *payload) {
    log_length_prefixed_payload("rds", "rds_grp_cntrs_rsp_cb", payload);
}

void HalHeliumBackend::rds_grp_cntrs_ext_rsp_cb(char *payload) {
    log_length_prefixed_payload("rds", "rds_grp_cntrs_ext_rsp_cb", payload);
}

void HalHeliumBackend::fm_peek_rsp_cb(char *payload) {
    log_length_prefixed_payload("vendor", "fm_peek_rsp_cb", payload);
}

void HalHeliumBackend::fm_ssbi_peek_rsp_cb(char *payload) {
    log_length_prefixed_payload("vendor", "fm_ssbi_peek_rsp_cb", payload);
}

void HalHeliumBackend::fm_agc_gain_rsp_cb(char *payload) {
    log_length_prefixed_payload("vendor", "fm_agc_gain_rsp_cb", payload);
}

void HalHeliumBackend::fm_ch_det_th_rsp_cb(char *payload) {
    log_length_prefixed_payload("vendor", "fm_ch_det_th_rsp_cb", payload);
}

void HalHeliumBackend::ext_country_code_cb(char *payload) {
    if (HalHeliumBackend *self = active_instance_) {
        self->on_ext_country_code(payload);
    }
}

void HalHeliumBackend::thread_evt_cb(unsigned int evt) {
    kHeliumLog("event", "thread event=%u", evt);
}

void HalHeliumBackend::fm_get_sig_thres_cb(int value, int status) {
    kHeliumLog("vendor", "fm_get_sig_thres_cb value=%d status=%d", value, status);
}

void HalHeliumBackend::fm_get_ch_det_thr_cb(int value, int status) {
    kHeliumLog("vendor", "fm_get_ch_det_thr_cb value=%d status=%d", value, status);
}

void HalHeliumBackend::fm_def_data_read_cb(int value, int status) {
    kHeliumLog("vendor", "fm_def_data_read_cb value=%d status=%d", value, status);
}

void HalHeliumBackend::fm_get_blend_cb(int value, int status) {
    kHeliumLog("vendor", "fm_get_blend_cb value=%d status=%d", value, status);
}

void HalHeliumBackend::fm_set_ch_det_thr_cb(int status) {
    kHeliumLog("vendor", "fm_set_ch_det_thr_cb status=%d", status);
}

void HalHeliumBackend::fm_def_data_write_cb(int status) {
    kHeliumLog("vendor", "fm_def_data_write_cb status=%d", status);
}

void HalHeliumBackend::fm_set_blend_cb(int status) {
    kHeliumLog("vendor", "fm_set_blend_cb status=%d", status);
}

void HalHeliumBackend::fm_get_station_param_cb(int value, int status) {
    if (HalHeliumBackend *self = active_instance_) {
        self->on_station_param(value, status);
    }
}

void HalHeliumBackend::fm_get_station_debug_param_cb(int value, int status) {
    kHeliumLog("vendor", "fm_get_station_debug_param_cb value=%d status=%d", value, status);
}

void HalHeliumBackend::enable_slimbus_cb(int status) {
    if (HalHeliumBackend *self = active_instance_) {
        self->on_enable_slimbus(status);
    }
}

void HalHeliumBackend::enable_softmute_cb(int status) {
    kHeliumLog("audio", "soft_mute status=%d", status);
}

Backend *create_hal_helium_backend() {
    static HalHeliumBackend backend;
    return &backend;
}
