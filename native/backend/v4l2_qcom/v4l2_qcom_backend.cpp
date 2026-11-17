#include "v4l2_qcom_backend.h"
#include "../qcom_fm_controls.h"
#include "../../ctl_server.h"
#include "../../frequency_format.h"
#include "../../hex_dump.h"
#include "../../monotonic_clock.h"
#include "../../rds_parser.h"
#include "../../region_profile.h"
#include "../../rmssi.h"

#include <algorithm>
#include <cstring>
#include <errno.h>
#include <fcntl.h>
#include <linux/videodev2.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#ifdef __ANDROID_API__
#    include <sys/system_properties.h>
#endif

namespace {

constexpr const char *kErrFailed = "ERR_FAILED";
constexpr const char *kErrInvalidAntenna = "ERR_UNV_ANT";
constexpr const char *kErrCantSetRegion = "ERR_CNS_REG";
constexpr uint64_t kMetricSettleMs = 750;

constexpr int kV4l2QcomRdsMask = 23;
// Group 3A (0x40, RT+/ERT ODA discovery) | Group 1A (0x04, ECC slow labelling).
constexpr int kV4l2QcomRds1aGroupMask = 0x04;
constexpr int kV4l2QcomRdsRawGroupMask = 0x40 | kV4l2QcomRds1aGroupMask;
constexpr size_t kPropertyValueBufferSize = 128;

// Tavarua V4L2 event numbers emitted by the Qualcomm kernel driver.
enum tavarua_evt_t {
    TAVARUA_EVT_RADIO_READY = 0,
    TAVARUA_EVT_TUNE_SUCC,
    TAVARUA_EVT_SEEK_COMPLETE,
    TAVARUA_EVT_SCAN_NEXT,
    TAVARUA_EVT_NEW_RAW_RDS,
    TAVARUA_EVT_NEW_RT_RDS,
    TAVARUA_EVT_NEW_PS_RDS,
    TAVARUA_EVT_ERROR,
    TAVARUA_EVT_BELOW_TH,
    TAVARUA_EVT_ABOVE_TH,
    TAVARUA_EVT_STEREO,
    TAVARUA_EVT_MONO,
    TAVARUA_EVT_RDS_AVAIL,
    TAVARUA_EVT_RDS_NOT_AVAIL,
    TAVARUA_EVT_NEW_SRCH_LIST,
    TAVARUA_EVT_NEW_AF_LIST,
    TAVARUA_EVT_RADIO_DISABLED = 18,
    TAVARUA_EVT_NEW_ODA,
    TAVARUA_EVT_NEW_RT_PLUS,
    TAVARUA_EVT_NEW_ERT,
};

// Maps frequency spacing in kHz to Qualcomm channel spacing enum.
channel_space_t map_spacing_to_vendor(int spacing_khz) {
    switch (spacing_khz) {
        case 50:
            return FM_RX_SPACE_50KHZ;
        case 200:
            return FM_RX_SPACE_200KHZ;
        case 100:
        default:
            return FM_RX_SPACE_100KHZ;
    }
}

// Reads an Android system property into a buffer in a safe way.
int read_system_property(const char *name, char *value) {
#ifdef __ANDROID_API__
    const int length = __system_property_get(name, value);
    if (length <= 0) {
        value[0] = '\0';
    }
    return length;
#else
    (void)name;
    value[0] = '\0';
    return 0;
#endif
}

// Checks if the Qualcomm FM patch downloader binary exists on disk.
bool has_fm_patch_downloader() {
    constexpr const char *paths[] = {
            "/system/bin/fm_qsoc_patches",
            "/vendor/bin/fm_qsoc_patches",
            "/system/xbin/fm_qsoc_patches",
    };

    for (const char *path : paths) {
        if (access(path, X_OK) == 0) {
            return true;
        }
    }

    return false;
}

// Returns true if the file or device node exists.
bool file_exists(const char *file) {
    struct stat sb;
    return stat(file, &sb) == 0;
}

// Fills lower and upper frequency boundaries for a radio band.
void make_frequency_limit_by_band(radio_band_t band, band_limit_freq *limits) {
    switch (band) {
        case FM_RX_JAPAN_STANDARD: {
            limits->lower_limit = 76000;
            limits->upper_limit = 95000;
            break;
        }

        case FM_RX_JAPAN_WIDE: {
            limits->lower_limit = 76000;
            limits->upper_limit = 108000;
            break;
        }

        case FM_RX_US_EUROPE:
        default: {
            limits->lower_limit = 87500;
            limits->upper_limit = 108000;
            break;
        }
    }
}

}  // namespace

V4l2QcomBackend::V4l2QcomBackend() {
    fm_storage_.frequency = 0;
    fm_storage_.mute_mode = FM_RX_NO_MUTE;
    fm_storage_.stereo_type = FM_RX_STEREO;
    fm_storage_.space_type = FM_RX_SPACE_100KHZ;
    fm_storage_.band_type = FM_RX_US_EUROPE;
    fm_storage_.state = OFF;
    fm_storage_.rds.program_id = 0;
    fm_storage_.rds.program_type = 0;
    fm_storage_.rds.program_name[0] = '\0';
    fm_storage_.rds.radio_text[0] = '\0';
}

V4l2QcomBackend::~V4l2QcomBackend() {
    shutdown();
    if (qsoc_poweron_path_ != nullptr) {
        free(qsoc_poweron_path_);
        qsoc_poweron_path_ = nullptr;
    }
}

void V4l2QcomBackend::clear_direct_init_marker() {
#ifdef __ANDROID_API__
    if (direct_v4l2_transport_ && __system_property_set("hw.fm.init", "0") != 0) {
        log("setup", "failed to clear hw.fm.init");
    }
#endif
}

// Checks if Bluetooth HCI transport is SMD.
bool V4l2QcomBackend::is_smd_transport_layer() {
    char buf[40] = {0};
    read_system_property("ro.qualcomm.bt.hci_transport", buf);
    return std::strcmp(buf, "smd") == 0;
}

// Checks if Bluetooth chip is Rome.
bool V4l2QcomBackend::is_rome_chip() {
    char buf[40] = {0};

    read_system_property("vendor.bluetooth.soc", buf);
    if (std::strcmp(buf, "rome") == 0) {
        return true;
    }

    buf[0] = '\0';
    read_system_property("qcom.bluetooth.soc", buf);
    return std::strcmp(buf, "rome") == 0;
}

// Initializes the backend and opens the device node.
bool V4l2QcomBackend::init() {
    return set_status(command_open() == FM_CMD_SUCCESS, kErrFailed);
}

// Enables the FM receiver with the given startup configuration.
bool V4l2QcomBackend::enable(const StartupConfig &config) {
    const RegionProfile &profile = get_region_profile(config.region);
    fm_config_data cfg_data = {
            .emphasis = static_cast<emphasis_t>(profile.emphasis),
            .spacing = map_spacing_to_vendor(config.spacing_khz),
            .antenna = static_cast<uint8_t>(config.antenna),
    };

    log("enable", "requested");
    log("setup", "runtime region_vendor=%d lower_khz=%u upper_khz=%u spacing_vendor=%d emphasis=%d rds_vendor=%d stereo=%d soft_mute=%d antenna=%d",
            profile.legacy_band,
            profile.lower_frequency_khz,
            profile.upper_frequency_khz,
            cfg_data.spacing,
            cfg_data.emphasis,
            profile.rds_standard,
            config.stereo ? 1 : 0,
            config.soft_mute ? 1 : 0,
            config.antenna);

    if (!set_status(command_prepare(&cfg_data) == FM_CMD_SUCCESS, kErrFailed)) {
        return rollback_enable();
    }

    // Driver rejects RDS_ON if the band is configured first. Keep RDS
    // setup before receiver_set_band(), then tune and unmute last.
    if (!set_status(command_setup_rds(static_cast<rds_system_t>(profile.rds_standard)) == FM_CMD_SUCCESS, kErrFailed)) {
        return rollback_enable();
    }

    if (!set_status(
            receiver_set_band(static_cast<radio_band_t>(profile.legacy_band)),
            kErrCantSetRegion
    ) || !set_stereo(config.stereo)) {
        return rollback_enable();
    }

    if (!set_auto_af(config.auto_af) ||
        !set_soft_mute(config.soft_mute) ||
        !set_frequency(config.frequency_khz)) {
        return rollback_enable();
    }

    if (!set_status(command_set_mute_mode(FM_RX_NO_MUTE) == FM_CMD_SUCCESS, kErrFailed)) {
        return rollback_enable();
    }

    log("enable", "accepted");
    enabled_ = true;
    return true;
}

// Disables the FM receiver and stops active searches.
bool V4l2QcomBackend::disable() {
    const bool accepted = command_disable() == FM_CMD_SUCCESS;

    if (accepted) {
        enabled_ = false;
        command_abandon_search();
    }

    return set_status(accepted, kErrFailed);
}

// Tunes the receiver to a specific frequency in kHz.
bool V4l2QcomBackend::set_frequency(uint32_t frequency_khz) {
    if (command_search_busy()) {
        return set_status(false, kErrFailed);
    }

    const bool accepted = command_tune_frequency(frequency_khz) == FM_CMD_SUCCESS;

    if (accepted) {
        metric_resume_at_ms_ = monotonic_ms() + kMetricSettleMs;
    }

    return set_status(accepted, kErrFailed);
}

// Moves frequency by one channel step in the requested direction.
bool V4l2QcomBackend::jump(int direction, uint32_t *new_frequency) {
    if (command_search_busy()) {
        return set_status(false, kErrFailed);
    }

    if (!set_status(command_tune_frequency_by_delta(direction >= 0 ? 1 : -1) == FM_CMD_SUCCESS, kErrFailed)) {
        return false;
    }

    metric_resume_at_ms_ = monotonic_ms() + kMetricSettleMs;

    if (new_frequency != nullptr) {
        *new_frequency = command_get_tuned_frequency();
    }

    return true;
}

// Starts hardware seek up or down.
bool V4l2QcomBackend::seek(int direction) {
    return set_status(command_start_seek(direction >= 0 ? 1 : 0, 7), kErrFailed);
}

// Sets power mode (normal vs low power).
bool V4l2QcomBackend::set_power_mode(bool low_power) {
    if (command_search_busy()) {
        return set_status(false, kErrFailed);
    }

    log("setup", "set low_power=%d", low_power ? 1 : 0);
    return set_status(
            receiver_set_power_mode(low_power ? FM_RX_POWER_MODE_LOW : FM_RX_POWER_MODE_NORMAL),
            kErrFailed
    );
}

// Sets stereo vs mono audio reception.
bool V4l2QcomBackend::set_stereo(bool enabled) {
    log("audio", "set stereo=%d", enabled ? 1 : 0);
    return set_status(
            command_set_stereo_mode(enabled ? FM_RX_STEREO : FM_RX_MONO) == FM_CMD_SUCCESS,
            kErrFailed
    );
}

// Sets antenna type.
bool V4l2QcomBackend::set_antenna(int antenna) {
    log("setup", "set antenna=%d", antenna);
    return set_status(receiver_set_antenna(static_cast<uint8_t>(antenna)), kErrInvalidAntenna);
}

// Configures radio region, frequency bounds, and emphasis.
bool V4l2QcomBackend::set_region(StartupRegion region) {
    if (command_search_busy()) {
        return set_status(false, kErrFailed);
    }

    const RegionProfile &profile = get_region_profile(region);
    log("setup", "set region=%s", get_region_name(region));
    log("setup", "apply region_vendor=%d lower_khz=%u upper_khz=%u emphasis=%d rds_vendor=%d",
            profile.legacy_band,
            profile.lower_frequency_khz,
            profile.upper_frequency_khz,
            profile.emphasis,
            profile.rds_standard);

    if (!set_status(
            receiver_set_band(static_cast<radio_band_t>(profile.legacy_band)),
            kErrCantSetRegion
    )) {
        return false;
    }

    if (!set_status(
            receiver_set_emphasis(static_cast<emphasis_t>(profile.emphasis)),
            kErrFailed
    )) {
        return false;
    }

    if (!set_status(
            receiver_set_rds_system(static_cast<rds_system_t>(profile.rds_standard)),
            kErrFailed
    )) {
        return false;
    }

    const uint32_t current_frequency = command_get_tuned_frequency();

    if (current_frequency == 0) {
        return true;
    }

    const uint32_t target_frequency = clamp_frequency_to_region(profile, current_frequency);
    return target_frequency == current_frequency || set_frequency(target_frequency);
}

// Sets channel spacing in kHz.
bool V4l2QcomBackend::set_spacing(int spacing_khz) {
    if (command_search_busy()) {
        return set_status(false, kErrFailed);
    }

    log("setup", "set spacing_khz=%d", spacing_khz);
    return set_status(receiver_set_spacing(map_spacing_to_vendor(spacing_khz)), kErrFailed);
}

// Starts full scan across the band.
bool V4l2QcomBackend::search() {
    return set_status(command_start_scan(), kErrFailed);
}

// Cancels ongoing scan or seek operation.
bool V4l2QcomBackend::cancel_search() {
    return set_status(command_cancel_scan(), kErrFailed);
}

// Enables or disables automatic AF jump.
bool V4l2QcomBackend::set_auto_af(bool enabled) {
    log("af", "set auto=%d", enabled ? 1 : 0);
    return set_status(receiver_toggle_af_jump(enabled ? 1 : 0), kErrFailed);
}

// Enables or disables soft mute.
bool V4l2QcomBackend::set_soft_mute(bool enabled) {
    log("audio", "set soft_mute=%d", enabled ? 1 : 0);
    return set_status(set_v4l2_ctrl(kV4l2CtrlSoftMute, enabled ? 1 : 0), kErrFailed);
}

// Toggles slimbus transport (no-op on V4L2).
bool V4l2QcomBackend::set_slimbus(bool /*enabled*/) {
    return set_status(true, nullptr);
}

// Requests signal metrics (SINR or RMSSI) from the hardware.
bool V4l2QcomBackend::request_signal_metric() {
    if (!enabled_ || command_search_busy() || monotonic_ms() < metric_resume_at_ms_) {
        return false;
    }

    if (sinr_supported_) {
        if (command_publish_sinr()) {
            return true;
        }
        log("legacy", "SINR not supported via V4L2, falling back to RMSSI");
        sinr_supported_ = false;
    }

    return command_publish_rmssi();
}

// Performs process teardown and turns off the receiver.
void V4l2QcomBackend::shutdown() {
    command_shutdown(enabled_);
    enabled_ = false;
}

// Restores state when enable sequence fails midway.
bool V4l2QcomBackend::rollback_enable() {
    enabled_ = false;
    const char *error = last_error_;

    if (command_disable() != FM_CMD_SUCCESS) {
        receiver_set_state(OFF);
        receiver_close();
    }

    last_error_ = error;
    return false;
}

// Opens the V4L2 device node.
bool V4l2QcomBackend::receiver_open() {
    return open_device("/dev/radio0");
}

// Closes the V4L2 device node.
void V4l2QcomBackend::receiver_close() {
    close_device();
}

// Sets the V4L2 tuner state.
bool V4l2QcomBackend::receiver_set_state(fm_tuner_state tuner_state) {
    return set_v4l2_ctrl(kV4l2CtrlState, tuner_state);
}

// Sets De-emphasis filter.
bool V4l2QcomBackend::receiver_set_emphasis(emphasis_t emp_type) {
    return set_v4l2_ctrl(kV4l2CtrlEmphasis, emp_type);
}

// Sets channel spacing.
bool V4l2QcomBackend::receiver_set_spacing(channel_space_t spacing) {
    if (!set_v4l2_ctrl(kV4l2CtrlChannelSpacing, spacing)) {
        return false;
    }

    fm_storage_.space_type = spacing;
    return true;
}

// Toggles RDS reception.
bool V4l2QcomBackend::receiver_set_rds_state(bool enable) {
    return set_v4l2_ctrl(kV4l2CtrlRdsOn, enable);
}

// Sets radio band limits and region control.
bool V4l2QcomBackend::receiver_set_band(radio_band_t band) {
    band_limit_freq limits = {};
    make_frequency_limit_by_band(band, &limits);

    struct v4l2_tuner tuner = {};
    tuner.index = 0;
    tuner.type = V4L2_TUNER_RADIO;
    tuner.rangelow = khz_to_freq(limits.lower_limit);
    tuner.rangehigh = khz_to_freq(limits.upper_limit);
    tuner.signal = 0;

    if (ioctl(fd_radio_, VIDIOC_S_TUNER, &tuner) < 0) {
        return false;
    }

    if (set_v4l2_ctrl(kV4l2CtrlRegion, band)) {
        fm_storage_.band_type = band;
        return true;
    }

    return false;
}

// Configures RDS system standard.
bool V4l2QcomBackend::receiver_set_rds_system(rds_system_t system) {
    return set_v4l2_ctrl(kV4l2CtrlRdsStandard, system);
}

// Sets active antenna.
bool V4l2QcomBackend::receiver_set_antenna(uint8_t antenna) {
    return set_v4l2_ctrl(kV4l2CtrlAntenna, antenna);
}

// Sets tuned frequency via VIDIOC_S_FREQUENCY.
bool V4l2QcomBackend::receiver_set_tuned_frequency(uint32_t frequency_khz) {
    if (fd_radio_ < 0) {
        return false;
    }

    struct v4l2_frequency freq_struct = {};
    freq_struct.type = V4L2_TUNER_RADIO;
    freq_struct.frequency = khz_to_freq(frequency_khz);

    if (ioctl(fd_radio_, VIDIOC_S_FREQUENCY, &freq_struct) < 0) {
        const int saved_errno = errno;
        log("tune", "set frequency_khz=%d failed, errno=%d (%s)", frequency_khz, saved_errno, strerror(saved_errno));
        return false;
    }

    return true;
}

// Reads tuned frequency via VIDIOC_G_FREQUENCY.
uint32_t V4l2QcomBackend::receiver_get_tuned_frequency() {
    if (fd_radio_ < 0) {
        return 0;
    }

    struct v4l2_frequency freq_struct = {};
    freq_struct.type = V4L2_TUNER_RADIO;

    if (ioctl(fd_radio_, VIDIOC_G_FREQUENCY, &freq_struct) == 0) {
        return freq_to_khz(freq_struct.frequency);
    }

    const int saved_errno = errno;
    log("tune", "get frequency failed, errno=%d (%s)", saved_errno, strerror(saved_errno));
    return 0;
}

// Reads RMSSI value from VIDIOC_G_TUNER.
bool V4l2QcomBackend::receiver_get_rmssi(int *rmssi) {
    if (fd_radio_ < 0 || rmssi == nullptr) {
        return false;
    }

    struct v4l2_tuner tuner = {};
    tuner.index = 0;
    tuner.type = V4L2_TUNER_RADIO;

    if (ioctl(fd_radio_, VIDIOC_G_TUNER, &tuner) < 0) {
        const int saved_errno = errno;
        log("metric", "get rmssi failed, errno=%d (%s)", saved_errno, strerror(saved_errno));
        return false;
    }

    *rmssi = decode_rmssi(tuner.signal);
    return true;
}

// Reads SINR value from private control.
bool V4l2QcomBackend::receiver_get_sinr(int *sinr) {
    if (fd_radio_ < 0 || sinr == nullptr) {
        return false;
    }

    int32_t val = 0;
    if (!get_v4l2_ctrl(kV4l2CtrlIrisSinr, &val)) {
        const int saved_errno = errno;
        log("metric", "get sinr failed, errno=%d (%s)", saved_errno, strerror(saved_errno));
        return false;
    }

    *sinr = static_cast<int8_t>(static_cast<unsigned char>(val));
    return true;
}

// Sets audio mute mode via standard V4L2 CID.
bool V4l2QcomBackend::receiver_set_mute_mode(mute_t mode) {
    return set_v4l2_ctrl(V4L2_CID_AUDIO_MUTE, mode);
}

// Toggles automatic AF jump.
bool V4l2QcomBackend::receiver_toggle_af_jump(uint8_t enable) {
    return set_v4l2_ctrl(kV4l2CtrlAfJump, enable);
}

// Sets power mode.
bool V4l2QcomBackend::receiver_set_power_mode(power_mode_t mode) {
    return set_v4l2_ctrl(kV4l2CtrlLowPowerMode, mode);
}

// Sets stereo/mono mode.
bool V4l2QcomBackend::receiver_set_stereo_mode(stereo_t mode) {
    if (fd_radio_ < 0) {
        return false;
    }

    struct v4l2_tuner tuner = {};
    tuner.index = 0;
    tuner.type = V4L2_TUNER_RADIO;
    tuner.audmode = mode;

    if (ioctl(fd_radio_, VIDIOC_S_TUNER, &tuner) < 0) {
        const int saved_errno = errno;
        log("audio", "set stereo failed, errno=%d (%s)", saved_errno, strerror(saved_errno));
        return false;
    }

    return true;
}

// Starts hardware frequency seek.
bool V4l2QcomBackend::receiver_search_station_seek(search_t mode, int8_t search_dir, uint8_t dwell_period) {
    log("search", "seek requested direction=%d dwell=%d", search_dir, dwell_period);

    if (fd_radio_ < 0) {
        return false;
    }

    if (!set_v4l2_ctrl(kV4l2CtrlSearchMode, mode)) {
        log("search", "set seek mode failed");
        return false;
    }

    if (!set_v4l2_ctrl(kV4l2CtrlScanDwell, dwell_period)) {
        log("search", "set scan dwell failed");
        return false;
    }

    struct v4l2_hw_freq_seek hw_seek = {};
    hw_seek.type = V4L2_TUNER_RADIO;
    hw_seek.seek_upward = static_cast<uint32_t>(search_dir);

    if (ioctl(fd_radio_, VIDIOC_S_HW_FREQ_SEEK, &hw_seek) < 0) {
        const int saved_errno = errno;
        log("search", "seek failed, errno=%d (%s)", saved_errno, strerror(saved_errno));
        return false;
    }

    log("search", "seek direction=%d accepted", search_dir);
    return true;
}

// Requests hardware scan for station lists.
bool V4l2QcomBackend::receiver_search_station_list(fm_search_list_stations options) {
    log("search", "scan requested mode=%d direction=%d count=%d pty=%d",
            options.search_mode, options.search_dir, options.srch_list_max, options.program_type);

    if (fd_radio_ < 0) {
        return false;
    }

    if (!set_v4l2_ctrl(kV4l2CtrlSearchMode, options.search_mode)) {
        log("search", "set list scan mode failed");
        return false;
    }

    if (!set_v4l2_ctrl(kV4l2CtrlSearchCount, options.srch_list_max)) {
        log("search", "set list scan count failed");
        return false;
    }

    if (!set_v4l2_ctrl(kV4l2CtrlSearchPty, options.program_type)) {
        log("search", "set list scan PTY failed");
        return false;
    }

    struct v4l2_hw_freq_seek hwseek = {};
    hwseek.type = V4L2_TUNER_RADIO;
    hwseek.seek_upward = static_cast<uint32_t>(options.search_dir);

    if (ioctl(fd_radio_, VIDIOC_S_HW_FREQ_SEEK, &hwseek) < 0) {
        const int saved_errno = errno;
        log("search", "scan failed, errno=%d (%s)", saved_errno, strerror(saved_errno));
        return false;
    }

    log("search", "scan accepted");
    return true;
}

// Cancels active hardware search.
bool V4l2QcomBackend::receiver_cancel_search() {
    return set_v4l2_ctrl(kV4l2CtrlSearchOn, 0);
}

// Reads a V4L2 private buffer index via VIDIOC_DQBUF.
int32_t V4l2QcomBackend::read_buffer(uint8_t *buf, uint32_t buffer_size, int index) {
    if (fd_radio_ < 0 || buf == nullptr || buffer_size == 0) {
        return -1;
    }

    struct v4l2_buffer v4l2_buf = {};
    v4l2_buf.index = static_cast<uint32_t>(index);
    v4l2_buf.type = V4L2_BUF_TYPE_PRIVATE;
    v4l2_buf.memory = V4L2_MEMORY_USERPTR;
    v4l2_buf.m.userptr = reinterpret_cast<unsigned long>(buf);
    v4l2_buf.length = buffer_size;

    if (ioctl(fd_radio_, VIDIOC_DQBUF, &v4l2_buf) < 0) {
        const int saved_errno = errno;
        log("v4l2", "read buffer index=0x%x failed, errno=%d (%s)", index, saved_errno, strerror(saved_errno));
        return -1;
    }

    return static_cast<int32_t>(v4l2_buf.bytesused);
}

// Clears cached RT+ tags.
void V4l2QcomBackend::clear_rt_plus_tags() {
    rt_plus_tags_valid_ = false;
    rt_plus_tags_[0] = {};
    rt_plus_tags_[1] = {};
}

// Resets RT text context.
void V4l2QcomBackend::reset_rt_context() {
    clear_rt_plus_tags();
    rt_raw_text_len_ = 0;
    memset(rt_raw_text_, 0, sizeof(rt_raw_text_));
}

// Logs RT+ slices against current raw EBU Latin RT buffer.
void V4l2QcomBackend::log_rt_plus_slices() {
    if (!rt_plus_tags_valid_) {
        return;
    }

    for (const RdsRtPlusTag &tag : rt_plus_tags_) {
        if (tag.content_type == 0) {
            continue;
        }

        // tag.start/tag.length are indices into the RAW EBU Latin RT buffer, not
        // the UTF-8 text. Slice raw bytes and convert only the slice.
        if (tag.start >= 0 && tag.length > 0 && tag.start + tag.length <= rt_raw_text_len_) {
            char text[65 * 3 + 1];
            convert_ebu_latin_to_utf8(rt_raw_text_ + tag.start, tag.length, text, static_cast<int>(sizeof(text)));
            log("rds", "rt+ slice %s=`%s`", get_rds_rt_plus_content_type_name(tag.content_type), text);
        } else {
            log("rds", "rt+ tag %s ct=%d start=%d len=%d rt_len=%d",
                    get_rds_rt_plus_content_type_name(tag.content_type), tag.content_type, tag.start, tag.length, rt_raw_text_len_);
        }
    }
}

// Evaluates RT+ slices against current raw text.
void V4l2QcomBackend::evaluate_rt_plus_slices(RdsRtPlusSlices *out) {
    evaluate_rds_rt_plus_slices(
            rt_plus_tags_valid_,
            rt_plus_tags_,
            rt_raw_text_,
            rt_raw_text_len_,
            out
    );
}

// Extracts Program Service payload from V4L2 buffer.
bool V4l2QcomBackend::extract_program_service(fm_rds_storage *storage) {
    uint8_t buf[64] = {0};
    int32_t bytes = read_buffer(buf, sizeof(buf), kTavaruaBufPsRds);

    if (bytes < 5) {
        return false;
    }

    RdsTextPayload parsed;
    if (!parse_rds_text_payload(
            buf,
            bytes,
            RdsTextPayloadKind::kProgramService,
            sizeof(storage->program_name) - 1,
            &parsed
    )) {
        return false;
    }

    storage->program_id = parsed.pi;
    storage->program_type = parsed.pty;
    snprintf(storage->program_name, sizeof(storage->program_name), "%s", parsed.text);

    log("rds", "ps count=%d pi=%04x pty=%d text=`%s` (len=%d)",
            parsed.block_count, storage->program_id, storage->program_type, storage->program_name, parsed.text_len);
    return true;
}

// Extracts Radio Text payload from V4L2 buffer.
bool V4l2QcomBackend::extract_radio_text(fm_rds_storage *storage) {
    uint8_t buf[128] = {0};
    int32_t bytes = read_buffer(buf, sizeof(buf), kTavaruaBufRtRds);

    if (bytes < 5) {
        return false;
    }

    RdsTextPayload parsed;
    if (!parse_rds_text_payload(
            buf,
            bytes,
            RdsTextPayloadKind::kRadioText,
            sizeof(storage->radio_text) - 1,
            &parsed
    )) {
        return false;
    }

    snprintf(storage->radio_text, sizeof(storage->radio_text), "%s", parsed.text);
    // Keep the RAW EBU Latin bytes: RT+ tag indices refer to raw characters,
    // and rt_raw_text_ is what log_rt_plus_slices() slices.
    rt_raw_text_len_ = parsed.raw_text_len;
    memcpy(rt_raw_text_, parsed.raw_text, static_cast<size_t>(parsed.raw_text_len));
    log("rds", "rt text=`%s` (len=%d)", storage->radio_text, parsed.text_len);
    log_rt_plus_slices();

    return true;
}

// Reads raw RDS buffer.
bool V4l2QcomBackend::extract_raw_rds() {
    uint8_t buf[256] = {0};
    const int32_t bytes = read_buffer(buf, sizeof(buf), kTavaruaBufRawRds);

    if (bytes <= 0) {
        log("rds", "raw read failed bytes=%d", bytes);
        return false;
    }

    char dump[3 * 32 + 1];
    format_hex_dump(reinterpret_cast<const char *>(buf), bytes, dump, sizeof(dump));
    log("rds", "raw len=%d data=%s%s", bytes, dump, bytes > 32 ? " ..." : "");

    char country[3] = "";
    if (parse_v4l2_raw_rds_ecc(buf, bytes, country)) {
        log("rds", "ecc country=%s", country);
        radio_state_patch_t patch = radio_state_patch_empty();
        patch.country = country;
        send_radio_state_patch(&patch);
    }

    return true;
}

// Extracts RT+ payload from V4L2 buffer.
bool V4l2QcomBackend::extract_rt_plus() {
    uint8_t buf[32] = {0};
    const int32_t bytes = read_buffer(buf, sizeof(buf), kTavaruaBufRtPlus);

    if (bytes <= 1) {
        log("rds", "rt+ read failed bytes=%d", bytes);
        return false;
    }

    char dump[3 * 32 + 1];
    format_hex_dump(reinterpret_cast<const char *>(buf), bytes, dump, sizeof(dump));
    const int payload_len = buf[0] < bytes ? buf[0] : bytes;
    const int rt_ert_flag = buf[1];
    log("rds", "rt+ len=%d rt_ert=%d data=%s%s", payload_len, rt_ert_flag, dump, bytes > 32 ? " ..." : "");

    clear_rt_plus_tags();
    RdsRtPlusPayload parsed;

    if (parse_rds_rt_plus_payload(buf, payload_len, RdsRtPlusKind::kV4l2Qcom, &parsed)) {
        if (parsed.ert) {
            log("rds", "rt+ tags belong to eRT (unsupported), ignoring ordinary RT slicing");
            radio_state_patch_t patch = radio_state_patch_empty();
            patch.rt_plus_title = "";
            patch.rt_plus_artist = "";
            patch.rt_plus_album = "";
            send_radio_state_patch(&patch);
            return true;
        }

        rt_plus_tags_[0] = parsed.tag_count > 0 ? parsed.tags[0] : RdsRtPlusTag{};
        rt_plus_tags_[1] = parsed.tag_count > 1 ? parsed.tags[1] : RdsRtPlusTag{};
        rt_plus_tags_valid_ = parsed.tag_count > 0;
        log_rt_plus_slices();

        RdsRtPlusSlices slices;
        evaluate_rt_plus_slices(&slices);
        radio_state_patch_t patch = radio_state_patch_empty();
        patch.rt_plus_title = slices.title;
        patch.rt_plus_artist = slices.artist;
        patch.rt_plus_album = slices.album;
        send_radio_state_patch(&patch);
    }

    return true;
}

// Extracts eRT payload from V4L2 buffer.
bool V4l2QcomBackend::extract_ert() {
    uint8_t buf[256] = {0};
    const int32_t bytes = read_buffer(buf, sizeof(buf), kTavaruaBufErt);

    if (bytes <= 3) {
        log("rds", "ert read failed bytes=%d", bytes);
        return false;
    }

    char dump[3 * 32 + 1];
    format_hex_dump(reinterpret_cast<const char *>(buf), bytes, dump, sizeof(dump));
    const int text_len = buf[0] < bytes - 3 ? buf[0] : bytes - 3;
    char text[256];
    snprintf(text, sizeof(text), "%.*s", text_len, buf + 3);
    log("rds", "ert len=%d utf8=%d formatting=%d text=`%s` data=%s%s",
            text_len, buf[1], buf[2], text, dump, bytes > 32 ? " ..." : "");
    return true;
}

// Extracts Alternative Frequencies list from V4L2 buffer.
uint8_t V4l2QcomBackend::extract_rds_af_list(uint32_t *frequencies) {
    uint8_t buf[0xff];
    const int32_t bytes = read_buffer(buf, sizeof(buf), kTavaruaBufAfList);

    if (bytes < 7) {
        return 0;
    }

    RdsAfList af;
    if (!parse_rds_af_payload(buf, bytes, &af)) {
        log("af", "invalid list marker=%d count=%d", buf[4], buf[4] & 0xff);
        return 0;
    }

    for (int i = 0; i < af.count; ++i) {
        frequencies[i] = af.frequencies_khz[i];
    }

    log("af", "parsed %d frequencies", af.count);
    return static_cast<uint8_t>(af.count);
}

// Extracts search station list from V4L2 buffer.
uint8_t V4l2QcomBackend::extract_search_station_list(uint32_t *list) {
    uint8_t buf[100] = {0};
    uint32_t lower_limit, upper_limit;

    struct v4l2_tuner tuner = {};
    tuner.index = 0;

    if (ioctl(fd_radio_, VIDIOC_G_TUNER, &tuner) == 0) {
        lower_limit = freq_to_khz(tuner.rangelow);
        upper_limit = freq_to_khz(tuner.rangehigh);
    } else {
        lower_limit = kDefaultLowerFrequencyKhz;
        upper_limit = kDefaultUpperFrequencyKhz;
    }

    if (lower_limit < 65000 || lower_limit > kDefaultUpperFrequencyKhz || upper_limit <= lower_limit || upper_limit > kDefaultUpperFrequencyKhz) {
        log("search", "invalid limits %d-%d, fallback to %d-%d", lower_limit, upper_limit, kDefaultLowerFrequencyKhz, kDefaultUpperFrequencyKhz);
        lower_limit = kDefaultLowerFrequencyKhz;
        upper_limit = kDefaultUpperFrequencyKhz;
    }

    int32_t bytes = read_buffer(buf, sizeof(buf), kTavaruaBufSearchList);

    if (bytes < 1) {
        log("search", "read station list failed");
        return 0;
    }

    RdsSearchList stations;
    if (!parse_rds_search_list_payload(
            buf,
            bytes,
            RdsSearchListKind::kAbsoluteChannels,
            0,
            &stations
    )) {
        log("search", "parse station list failed");
        return 0;
    }

    uint8_t valid_count = 0;

    for (int i = 0; i < stations.count; i++) {
        const uint32_t frequency = stations.frequencies_khz[i];

        if (frequency < lower_limit || frequency > upper_limit) {
            log("search", "skip station[%d]=%d out of range", i, frequency);
            continue;
        }

        list[valid_count] = frequency;
        valid_count++;
    }

    return valid_count;
}

// Stub function for launching event thread.
void *V4l2QcomBackend::event_thread_stub(void *arg) {
    auto *self = static_cast<V4l2QcomBackend *>(arg);
    self->event_thread();
    return nullptr;
}

// Background thread that continuously reads V4L2 events.
void V4l2QcomBackend::event_thread() {
    log("event", "interrupt thread started");
    uint8_t buf[128] = {0};

    while (thread_running_) {
        int32_t bytes = read_buffer(buf, sizeof(buf), kTavaruaBufEvents);

        if (bytes < 0) {
            pthread_mutex_lock(&legacy_scan_lock_);
            legacy_scan_.reset();
            pthread_mutex_unlock(&legacy_scan_lock_);
            break;
        }

        for (int i = 0; i < bytes; i++) {
            const uint8_t event_buf = buf[i];
            bool continue_loop = true;

            switch (event_buf) {
                case TAVARUA_EVT_RADIO_READY: {
                    log("event", "FM enabled");
                    fm_storage_.state = RX;
                    power_on_completed_ = true;
                    break;
                }

                case TAVARUA_EVT_RADIO_DISABLED: {
                    log("event", "FM disabled");
                    pthread_mutex_lock(&legacy_scan_lock_);
                    legacy_scan_.reset();
                    pthread_mutex_unlock(&legacy_scan_lock_);
                    fm_storage_.state = OFF;
                    power_on_completed_ = false;
                    clear_direct_init_marker();
                    receiver_close();
                    send_native_event("disabled");
                    continue_loop = false;
                    break;
                }

                case TAVARUA_EVT_TUNE_SUCC: {
                    fm_storage_.frequency = receiver_get_tuned_frequency();
                    pthread_mutex_lock(&legacy_scan_lock_);
                    legacy_scan_.on_tune(static_cast<int>(fm_storage_.frequency));
                    pthread_mutex_unlock(&legacy_scan_lock_);

                    fm_storage_.rds.program_name[0] = '\0';
                    fm_storage_.rds.radio_text[0] = '\0';
                    reset_rt_context();

                    log("event", "tune frequency_khz=%d", fm_storage_.frequency);

                    radio_state_patch_t patch = radio_state_patch_empty();
                    patch.frequency_khz = fm_storage_.frequency;
                    patch.ps = patch.rt = patch.pi = patch.country = patch.rt_plus_title = patch.rt_plus_artist = patch.rt_plus_album = "";
                    patch.af_count = patch.pty = 0;
                    send_radio_state_patch(&patch);

                    // The tune event is authoritative for the new station. Full scans
                    // remain busy here and use the periodic refresh after completion.
                    if (!command_search_busy()) {
                        command_publish_sinr() || command_publish_rmssi();
                    }
                    break;
                }

                case TAVARUA_EVT_SEEK_COMPLETE: {
                    ScanResult result;
                    pthread_mutex_lock(&legacy_scan_lock_);
                    const V4l2ScanTerminal terminal = legacy_scan_.on_seek_complete(&result);
                    pthread_mutex_unlock(&legacy_scan_lock_);

                    if (terminal == V4l2ScanTerminal::kCompleted) {
                        const std::string frequencies = format_frequency_list_khz(result.frequencies_khz, result.count);
                        log("search", "scan complete count=%d frequencies_khz=%s", result.count, frequencies.c_str());
                        send_search_done(result.frequencies_khz, result.count);
                    } else if (terminal == V4l2ScanTerminal::kCancelled) {
                        log("search", "scan cancel complete");
                    } else {
                        // Some legacy drivers report search completion before switching
                        // to the found station. TUNE_SUCC carries the final frequency.
                        log("event", "seek operation complete, waiting for tune event");
                    }
                    break;
                }

                case TAVARUA_EVT_SCAN_NEXT: {
                    const int current_frequency = static_cast<int>(receiver_get_tuned_frequency());
                    pthread_mutex_lock(&legacy_scan_lock_);
                    const int collected_frequency = legacy_scan_.on_scan_next(current_frequency);
                    pthread_mutex_unlock(&legacy_scan_lock_);

                    log("search", "scan next frequency_khz=%d collected_frequency_khz=%d",
                            current_frequency, collected_frequency);
                    break;
                }

                case TAVARUA_EVT_NEW_RAW_RDS: {
                    log("event", "raw_rds_available=1");
                    extract_raw_rds();
                    break;
                }

                case TAVARUA_EVT_NEW_RT_RDS: {
                    if (extract_radio_text(&fm_storage_.rds)) {
                        RdsRtPlusSlices slices;
                        evaluate_rt_plus_slices(&slices);
                        radio_state_patch_t patch = radio_state_patch_empty();
                        patch.rt = fm_storage_.rds.radio_text;
                        patch.rt_plus_title = slices.title;
                        patch.rt_plus_artist = slices.artist;
                        patch.rt_plus_album = slices.album;
                        send_radio_state_patch(&patch);
                    } else {
                        log("rds", "failed to extract RT");
                    }
                    break;
                }

                case TAVARUA_EVT_NEW_PS_RDS: {
                    if (extract_program_service(&fm_storage_.rds)) {
                        char pi_str[16];
                        snprintf(pi_str, sizeof(pi_str), "%x", fm_storage_.rds.program_id);

                        radio_state_patch_t patch = radio_state_patch_empty();
                        patch.ps = fm_storage_.rds.program_name;
                        patch.pi = pi_str;
                        patch.pty = fm_storage_.rds.program_type;
                        send_radio_state_patch(&patch);
                    } else {
                        log("rds", "failed to extract PS");
                    }
                    break;
                }

                case TAVARUA_EVT_ERROR: {
                    log("event", "driver error");
                    pthread_mutex_lock(&legacy_scan_lock_);
                    legacy_scan_.reset();
                    pthread_mutex_unlock(&legacy_scan_lock_);
                    break;
                }

                case TAVARUA_EVT_BELOW_TH: {
                    log("event", "signal below threshold");
                    fm_storage_.avail = FM_SERVICE_NOT_AVAILABLE;
                    break;
                }

                case TAVARUA_EVT_ABOVE_TH: {
                    log("event", "signal above threshold");
                    fm_storage_.avail = FM_SERVICE_AVAILABLE;
                    break;
                }

                case TAVARUA_EVT_STEREO: {
                    log("event", "stereo=1");
                    fm_storage_.stereo_type = FM_RX_STEREO;
                    radio_state_patch_t patch = radio_state_patch_empty();
                    patch.stereo = 1;
                    send_radio_state_patch(&patch);
                    break;
                }

                case TAVARUA_EVT_MONO: {
                    log("event", "stereo=0");
                    fm_storage_.stereo_type = FM_RX_MONO;
                    radio_state_patch_t patch = radio_state_patch_empty();
                    patch.stereo = 0;
                    send_radio_state_patch(&patch);
                    break;
                }

                case TAVARUA_EVT_NEW_SRCH_LIST: {
                    log("event", "search_list_available=1");
                    uint32_t list[25];
                    uint8_t stations = extract_search_station_list(list);
                    const std::string frequencies = format_frequency_list_khz(list, stations);

                    log("search", "unexpected station-list result count=%d frequencies_khz=%s",
                            stations, frequencies.c_str());
                    break;
                }

                case TAVARUA_EVT_NEW_AF_LIST: {
                    uint32_t list[kMaxRdsAfCount];
                    uint8_t size = extract_rds_af_list(list);
                    const std::string frequencies = format_frequency_list_khz(list, size);

                    log("af", "result count=%d frequencies_khz=%s", size, frequencies.c_str());

                    int frequencies_khz[kMaxRdsAfCount];
                    for (uint8_t j = 0; j < size; ++j) {
                        frequencies_khz[j] = static_cast<int>(list[j]);
                    }

                    radio_state_patch_t patch = radio_state_patch_empty();
                    patch.af_khz = frequencies_khz;
                    patch.af_count = size;
                    send_radio_state_patch(&patch);
                    break;
                }

                case TAVARUA_EVT_RDS_AVAIL: {
                    log("event", "rds_sync=1");
                    break;
                }

                case TAVARUA_EVT_RDS_NOT_AVAIL: {
                    log("event", "rds_sync=0");
                    break;
                }

                case TAVARUA_EVT_NEW_ODA: {
                    log("event", "oda_available=1");
                    set_v4l2_ctrl(kV4l2CtrlRdsGroupMask, kV4l2QcomRds1aGroupMask);
                    break;
                }

                case TAVARUA_EVT_NEW_RT_PLUS: {
                    log("event", "rt_plus_available=1");
                    extract_rt_plus();
                    break;
                }

                case TAVARUA_EVT_NEW_ERT: {
                    log("event", "ert_available=1");
                    extract_ert();
                    break;
                }

                default: {
                    log("event", "unknown event=%d", event_buf);
                    break;
                }
            }

            if (!continue_loop) {
                thread_running_ = false;
                break;
            }
        }
    }

    log("event", "interrupt thread exited");
}

// Joins the event thread if it was started from another thread.
void V4l2QcomBackend::join_event_thread() {
    if (event_thread_started_ && !pthread_equal(pthread_self(), q_event_thread_)) {
        pthread_join(q_event_thread_, nullptr);
        event_thread_started_ = false;
    }
}

// Opens the V4L2 device and initiates firmware bootstrap if needed.
fm_cmd_status_t V4l2QcomBackend::command_open() {
    direct_v4l2_transport_ = false;
    char service[kPropertyValueBufferSize] = {};
    const int service_length = read_system_property("init.svc.fm_dl", service);
    const bool patch_downloader_available = has_fm_patch_downloader();

    // The legacy backend was already selected from /dev/radio0. If neither
    // bootstrap mechanism exists, use the stock no-firmware path. Decide this
    // before reading hw.fm.init because a killed previous direct session may
    // have left that process-external marker at 1.
    if (service_length == 0 && !patch_downloader_available) {
        direct_v4l2_transport_ = true;
        log("open", "mode=direct_v4l2 reason=fm_dl_absent");
    } else {
        int exit_code = system("setprop hw.fm.mode normal >/dev/null 2>/dev/null; setprop hw.fm.version 0 >/dev/null 2>/dev/null; setprop ctl.start fm_dl >/dev/null 2>/dev/null");
        log("open", "setprop exit=%d", exit_code);

        if (file_exists("/system/lib/modules/radio-iris-transport.ko")) {
            log("open", "found radio-iris-transport.ko, loading");
            system("insmod /system/lib/modules/radio-iris-transport.ko >/dev/null 2>/dev/null");
        }

        char value[kPropertyValueBufferSize] = {};
        int attempt;
        int init_success = 0;

        for (attempt = 0; attempt < 600; ++attempt) {
            read_system_property("hw.fm.init", value);
            if (value[0] == '1') {
                init_success = 1;
                break;
            }
            usleep(10000);
        }

        if (!init_success) {
            log("open", "fm_dl init failed after %d attempts", attempt);
            return FM_CMD_FAILURE;
        }
    }

    usleep(500000);
    log("open", "opening /dev/radio0");

    if (!receiver_open()) {
        log("open", "failed to open /dev/radio0");
        return FM_CMD_FAILURE;
    }

    usleep(700000);
    return FM_CMD_SUCCESS;
}

// Reads driver version capabilities and triggers patch download.
fm_cmd_status_t V4l2QcomBackend::command_prepare(fm_config_data *config_ptr) {
    char version_str[40] = {'\0'};
    struct v4l2_capability cap = {};

    log("prep", "reading driver version");

    if (ioctl(fd_radio_, VIDIOC_QUERYCAP, &cap) < 0) {
        log("prep", "query capabilities failed");
        return FM_CMD_FAILURE;
    }

    log("prep", "VIDIOC_QUERYCAP version=0x%x", cap.version);

    int ret = snprintf(version_str, sizeof(version_str), "%d", cap.version);

    if (ret >= static_cast<int>(sizeof(version_str))) {
        log("prep", "version string overflow");
        receiver_close();
        return FM_CMD_FAILURE;
    }

#ifdef __ANDROID_API__
    __system_property_set("hw.fm.version", version_str);
#endif
    log("prep", "hw.fm.version=%s", version_str);

    if (qsoc_poweron_path_ != nullptr) {
        free(qsoc_poweron_path_);
    }

    asprintf(&qsoc_poweron_path_, "fm_qsoc_patches %d 0", cap.version);

    if (qsoc_poweron_path_ != nullptr) {
        log("prep", "qsoc_onpath=%s", qsoc_poweron_path_);
    }

    log("prep", "completed");
    return command_setup_receiver(config_ptr);
}

// Configures initial receiver params and launches async event loop.
fm_cmd_status_t V4l2QcomBackend::command_setup_receiver(fm_config_data *ptr) {
    int thread_ret = 0;
    power_on_completed_ = false;
    join_event_thread();

#ifdef __ANDROID_API__
    if (direct_v4l2_transport_) {
        if (__system_property_set("hw.fm.init", "1") != 0) {
            log("setup", "failed to set hw.fm.init");
            return FM_CMD_FAILURE;
        }

        // Stock no-firmware libfmjni.so waits for the transport to settle
        // after publishing readiness and before starting the event listener.
        usleep(200000);
    } else if (is_smd_transport_layer()) {
        __system_property_set("ctl.start", "fm_dl");
        sleep(1);
    } else if (!is_rome_chip()) {
        if (qsoc_poweron_path_ == nullptr) {
            return FM_CMD_FAILURE;
        }

        int res = system(qsoc_poweron_path_);

        if (res != 0) {
            log("setup", "patch download failed, ret=%d", res);
            return FM_CMD_FAILURE;
        }
    }
#endif

    thread_running_ = true;

    // The newer no-firmware Qualcomm path starts a blocking event listener
    // before STATE=RX and then waits for RADIO_READY. Start it before the
    // power control here; the established legacy path keeps its old ordering.
    if (direct_v4l2_transport_) {
        thread_ret = pthread_create(&q_event_thread_, nullptr, event_thread_stub, this);

        if (thread_ret != 0) {
            log("event", "pthread_create failed ret=%d (%s)", thread_ret, strerror(thread_ret));
            thread_running_ = false;
            clear_direct_init_marker();
            return FM_CMD_FAILURE;
        }
        event_thread_started_ = true;
    }

    if (!receiver_set_state(RX)) {
        log("setup", "set radio state failed");
        thread_running_ = false;
        clear_direct_init_marker();
        receiver_close();
        join_event_thread();
        return FM_CMD_FAILURE;
    }

    if (direct_v4l2_transport_) {
        int ready_attempt = 0;

        while (!power_on_completed_ && ready_attempt < 500) {
            usleep(10000);
            ++ready_attempt;
        }

        if (!power_on_completed_) {
            log("setup", "RADIO_READY timeout attempts=%d state=%d", ready_attempt, fm_storage_.state);
            thread_running_ = false;
            clear_direct_init_marker();
            receiver_close();
            join_event_thread();
            return FM_CMD_FAILURE;
        }
    }

    if (!receiver_set_mute_mode(FM_RX_MUTE_BOTH)) {
        log("setup", "failed to mute receiver during setup");
        return FM_CMD_FAILURE;
    }

    log("setup", "emphasis=%d", ptr->emphasis);
    if (!receiver_set_emphasis(ptr->emphasis)) {
        log("setup", "failed to change emphasis");
        return FM_CMD_FAILURE;
    }

    log("setup", "spacing=%d", ptr->spacing);
    if (!receiver_set_spacing(ptr->spacing)) {
        log("setup", "failed to change channel spacing");
        return FM_CMD_FAILURE;
    }

    if (!receiver_set_antenna(ptr->antenna)) {
        log("setup", "failed to change antenna");
        return FM_CMD_FAILURE;
    }

    if (!direct_v4l2_transport_) {
        thread_ret = pthread_create(&q_event_thread_, nullptr, event_thread_stub, this);

        if (thread_ret != 0) {
            log("event", "pthread_create failed ret=%d (%s)", thread_ret, strerror(thread_ret));
            thread_running_ = false;
        } else {
            event_thread_started_ = true;
        }
    }

    if (!direct_v4l2_transport_) {
        power_on_completed_ = true;
    }
    return FM_CMD_SUCCESS;
}

// Configures RDS/RBDS group masks and raw buffers.
fm_cmd_status_t V4l2QcomBackend::command_setup_rds(rds_system_t system) {
    if (!receiver_set_rds_state(system != FM_RX_NO_RDS_SYSTEM) ||
        !receiver_set_rds_system(system)) {
        log("rds", "failed to setup RDS state or system");
        return FM_CMD_FAILURE;
    }

    log("rds", "system=%d", system);

    if (system != FM_RX_NO_RDS_SYSTEM) {
        // Qualcomm's legacy V4L2 path packs RT/PS/AF options into
        // RDSGROUP_PROC. RT+/ERT discovery starts from group 3A below; when
        // the driver discovers an ODA carrier it sends NEW_ODA and expects
        // userspace to set RDSGROUP_MASK again so it can OR in that carrier.
        int32_t group_proc = 0;
        if (!get_v4l2_ctrl(kV4l2CtrlRdsGroupProc, &group_proc)) {
            group_proc = 0;
        }

        uint8_t rds_group_mask = static_cast<uint8_t>(group_proc);
        rds_group_mask &= 0xC7;
        rds_group_mask |= (kV4l2QcomRdsMask & 0x07) << 3;

        log("rds", "set group options=0x%x", rds_group_mask);
        if (!set_v4l2_ctrl(kV4l2CtrlRdsGroupProc, rds_group_mask)) {
            log("rds", "failed to change RDS group options");
            return FM_CMD_FAILURE;
        }

        log("rds", "set raw group mask=0x%x", kV4l2QcomRdsRawGroupMask);
        if (!set_v4l2_ctrl(kV4l2CtrlRdsGroupMask, kV4l2QcomRdsRawGroupMask)) {
            log("rds", "failed to change raw RDS group mask");
            return FM_CMD_FAILURE;
        }

        log("rds", "set raw data buffer=1");
        if (!set_v4l2_ctrl(kV4l2CtrlRdsDataBuffer, 1)) {
            log("rds", "failed to change raw RDS data buffer");
            return FM_CMD_FAILURE;
        }

        const uint32_t ps_all = (kV4l2QcomRdsMask & (1 << 4)) >> 4;
        log("rds", "set ps all=0x%x", ps_all);
        if (!set_v4l2_ctrl(kV4l2CtrlPsAll, ps_all)) {
            log("rds", "failed to change ps all");
            return FM_CMD_FAILURE;
        }
    }

    return FM_CMD_SUCCESS;
}

// Sets radio state to OFF and stops patch downloader.
fm_cmd_status_t V4l2QcomBackend::command_disable() {
    log("disable", "requested");

    if (!power_on_completed_) {
        log("disable", "already disabled");
        return FM_CMD_FAILURE;
    }

    log("disable", "setting radio state off");

    if (!receiver_set_state(OFF)) {
        log("disable", "set radio state off failed");
        return FM_CMD_FAILURE;
    }

#ifdef __ANDROID_API__
    if (!direct_v4l2_transport_) {
        __system_property_set("ctl.stop", "fm_dl");
    } else {
        clear_direct_init_marker();
    }
#endif

    log("disable", "accepted");
    return FM_CMD_SUCCESS;
}

// Shuts down the backend and closes open handles.
void V4l2QcomBackend::command_shutdown(bool disable_receiver) {
    log("shutdown", "requested disable_receiver=%d", disable_receiver ? 1 : 0);
    command_abandon_search();

    if (disable_receiver && !receiver_set_state(OFF)) {
        log("shutdown", "failed to set radio state off");
    }

#ifdef __ANDROID_API__
    if (!direct_v4l2_transport_) {
        __system_property_set("ctl.stop", "fm_dl");
    } else {
        clear_direct_init_marker();
    }
#endif

    power_on_completed_ = false;
    thread_running_ = false;
    receiver_close();
    join_event_thread();
    log("shutdown", "completed");
}

// Tunes to a target frequency.
fm_cmd_status_t V4l2QcomBackend::command_tune_frequency(uint32_t frequency) {
    log("tune", "request frequency_khz=%d", frequency);

    if (!receiver_set_tuned_frequency(frequency)) {
        log("tune", "request frequency_khz=%d failed", frequency);
        return FM_CMD_FAILURE;
    }

    log("tune", "request frequency_khz=%d accepted", frequency);
    return FM_CMD_SUCCESS;
}

// Changes frequency by delta based on current channel spacing.
fm_cmd_status_t V4l2QcomBackend::command_tune_frequency_by_delta(int16_t direction) {
    uint32_t current_frequency_khz = receiver_get_tuned_frequency();
    int32_t delta = 100;

    switch (fm_storage_.space_type) {
        case FM_RX_SPACE_200KHZ: delta = 200; break;
        case FM_RX_SPACE_100KHZ: delta = 100; break;
        case FM_RX_SPACE_50KHZ: delta = 50; break;
    }

    uint32_t need_frequency_khz = current_frequency_khz + (delta * direction);
    return receiver_set_tuned_frequency(need_frequency_khz) ? FM_CMD_SUCCESS : FM_CMD_FAILURE;
}

// Returns current frequency in kHz.
uint32_t V4l2QcomBackend::command_get_tuned_frequency() {
    return receiver_get_tuned_frequency();
}

// Reads and publishes SINR signal quality metric.
bool V4l2QcomBackend::command_publish_sinr() {
    int sinr = 0;

    if (!receiver_get_sinr(&sinr)) {
        return false;
    }

    log("metric", "sinr=%d", sinr);
    radio_state_patch_t patch = radio_state_patch_empty();
    patch.sinr = sinr;
    send_radio_state_patch(&patch);
    return true;
}

// Reads and publishes RMSSI signal strength metric.
bool V4l2QcomBackend::command_publish_rmssi() {
    int rmssi = 0;

    if (!receiver_get_rmssi(&rmssi)) {
        return false;
    }

    log("metric", "rmssi=%d", rmssi);
    radio_state_patch_t patch = radio_state_patch_empty();
    patch.rmssi = rmssi;
    send_radio_state_patch(&patch);
    return true;
}

// Starts full band scan.
bool V4l2QcomBackend::command_start_scan() {
    pthread_mutex_lock(&legacy_scan_lock_);
    const bool started = legacy_scan_.start();
    pthread_mutex_unlock(&legacy_scan_lock_);

    if (!started) {
        log("search", "scan rejected because another scan is active");
        return false;
    }

    band_limit_freq limits = {};
    make_frequency_limit_by_band(fm_storage_.band_type, &limits);
    log("search", "scan start frequency_khz=%d", limits.lower_limit);

    if (command_tune_frequency(limits.lower_limit) != FM_CMD_SUCCESS) {
        pthread_mutex_lock(&legacy_scan_lock_);
        legacy_scan_.start_failed();
        pthread_mutex_unlock(&legacy_scan_lock_);
        return false;
    }

    log("search", "scan requested mode=%d direction=%d dwell=%d", SCAN, 1, 1);

    if (receiver_search_station_seek(SCAN, 1, 1)) {
        return true;
    }

    pthread_mutex_lock(&legacy_scan_lock_);
    legacy_scan_.start_failed();
    pthread_mutex_unlock(&legacy_scan_lock_);
    return false;
}

// Starts single seek operation.
bool V4l2QcomBackend::command_start_seek(int8_t direction, uint8_t dwell_period) {
    pthread_mutex_lock(&legacy_scan_lock_);
    const bool started = legacy_scan_.start_seek();
    pthread_mutex_unlock(&legacy_scan_lock_);

    if (!started) {
        log("search", "seek rejected because another search is active");
        return false;
    }

    if (receiver_search_station_seek(SEEK, direction, dwell_period)) {
        return true;
    }

    pthread_mutex_lock(&legacy_scan_lock_);
    legacy_scan_.seek_failed();
    pthread_mutex_unlock(&legacy_scan_lock_);
    return false;
}

// Cancels active search or scan.
bool V4l2QcomBackend::command_cancel_scan() {
    pthread_mutex_lock(&legacy_scan_lock_);
    const bool cancelling = legacy_scan_.begin_cancel();
    pthread_mutex_unlock(&legacy_scan_lock_);

    log("search", "cancel requested");

    if (receiver_cancel_search()) {
        return true;
    }

    if (cancelling) {
        pthread_mutex_lock(&legacy_scan_lock_);
        legacy_scan_.cancel_failed();
        pthread_mutex_unlock(&legacy_scan_lock_);
    }

    return false;
}

// Returns true if a search operation is in progress.
bool V4l2QcomBackend::command_search_busy() {
    pthread_mutex_lock(&legacy_scan_lock_);
    const bool busy = legacy_scan_.busy();
    pthread_mutex_unlock(&legacy_scan_lock_);
    return busy;
}

// Abandons current search and resets scan state.
void V4l2QcomBackend::command_abandon_search() {
    pthread_mutex_lock(&legacy_scan_lock_);
    legacy_scan_.reset();
    pthread_mutex_unlock(&legacy_scan_lock_);
}

// Sets audio mute mode.
fm_cmd_status_t V4l2QcomBackend::command_set_mute_mode(mute_t mode) {
    if (receiver_set_mute_mode(mode)) {
        log("audio", "set mute=%d accepted", mode);
        return FM_CMD_SUCCESS;
    }

    return FM_CMD_FAILURE;
}

// Sets stereo reception mode.
fm_cmd_status_t V4l2QcomBackend::command_set_stereo_mode(stereo_t is_stereo) {
    return receiver_set_stereo_mode(is_stereo) ? FM_CMD_SUCCESS : FM_CMD_FAILURE;
}

// Factory function.
Backend *create_v4l2_qcom_backend() {
    static V4l2QcomBackend backend;
    return &backend;
}
