#include "v4l2_exynos_backend.h"
#include "s610_fm_controls.h"
#include "../../ctl_server.h"
#include "../../scan_result.h"

#include <algorithm>
#include <cerrno>
#include <csignal>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <linux/videodev2.h>
#include <poll.h>
#include <pthread.h>
#include <sys/ioctl.h>
#include <unistd.h>

namespace {

constexpr const char *kErrFailed = "ERR_FAILED";
constexpr const char *kErrCantSetRegion = "ERR_CNS_REG";
constexpr int kRdsPollTimeoutMs = 50;
constexpr int kRdsThreadStopMaxAttempts = 100;
constexpr int kRdsThreadStopPollUs = 10000;
constexpr size_t kRdsReadBufferBytes = 480;

void on_rds_interrupt_signal(int /*signo*/) {
}

void install_rds_interrupt_signal_handler() {
    static std::once_flag once;
    std::call_once(once, []() {
        struct sigaction sa = {};
        sa.sa_handler = on_rds_interrupt_signal;
        sigemptyset(&sa.sa_mask);
        sa.sa_flags = 0;
        sigaction(SIGUSR1, &sa, nullptr);
    });
}

int32_t map_region_to_s610_band(const StartupRegion region) {
    switch (region) {
        case StartupRegion::kJapan:
            return kS610BandJapan;
        case StartupRegion::kJapanWide:
            return kS610BandJapanWide;
        case StartupRegion::kEurope:
        case StartupRegion::kUnitedStates:
        default:
            return kS610BandEuUs;
    }
}

void get_exynos_band_bounds(
        const StartupRegion region,
        uint32_t *lower_khz,
        uint32_t *upper_khz
) {
    if (region == StartupRegion::kJapan) {
        // S610 band 1 (Japan) in radio-s610.c is 76000..90000 kHz.
        *lower_khz = 76000;
        *upper_khz = 90000;
        return;
    }
    const RegionProfile &profile = get_region_profile(region);
    *lower_khz = profile.lower_frequency_khz;
    *upper_khz = profile.upper_frequency_khz;
}

int32_t map_spacing_to_s610(const int spacing_khz) {
    if (spacing_khz <= 50) {
        return kS610Spacing50Khz;
    }
    if (spacing_khz <= 100) {
        return kS610Spacing100Khz;
    }
    return kS610Spacing200Khz;
}

int32_t map_emphasis_to_s610(const int emphasis) {
    return emphasis == kFmEmphasis75Microseconds
            ? V4L2_DEEMPHASIS_75_uS
            : V4L2_DEEMPHASIS_50_uS;
}

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

V4l2ExynosBackend::V4l2ExynosBackend() = default;

V4l2ExynosBackend::~V4l2ExynosBackend() {
    shutdown();
}

int V4l2ExynosBackend::poll_rds_fd(const int fd, const int timeout_ms) {
    struct pollfd pfd = {};
    pfd.fd = fd;
    pfd.events = POLLIN | POLLRDNORM;
    const int ready = poll(&pfd, 1, timeout_ms);
    if (ready > 0 && (pfd.revents & (POLLIN | POLLRDNORM)) != 0) {
        return 1;
    }
    return ready <= 0 ? ready : 0;
}

ssize_t V4l2ExynosBackend::read_rds_fd(const int fd, void *buf, const size_t count) {
    return read(fd, buf, count);
}

int V4l2ExynosBackend::get_fd_flags(const int fd) {
    return fcntl(fd, F_GETFL);
}

int V4l2ExynosBackend::set_fd_flags(const int fd, const int flags) {
    return fcntl(fd, F_SETFL, flags);
}

bool V4l2ExynosBackend::ensure_open() {
    if (fd_radio_ >= 0) {
        return true;
    }
    if (!open_device("/dev/radio0")) {
        return false;
    }

    struct v4l2_capability cap = {};
    if (v4l2_ioctl(fd_radio_, VIDIOC_QUERYCAP, &cap) < 0) {
        const int saved_errno = errno;
        log(
                "init",
                "VIDIOC_QUERYCAP failed, errno=%d (%s)",
                saved_errno,
                std::strerror(saved_errno)
        );
        close_device();
        return false;
    }

    log(
            "init",
            "driver=%s card=%s version=0x%x",
            reinterpret_cast<const char *>(cap.driver),
            reinterpret_cast<const char *>(cap.card),
            cap.version
    );
    return true;
}

bool V4l2ExynosBackend::init() {
    std::lock_guard<std::mutex> lock(ioctl_mutex_);
    return set_status(ensure_open(), kErrFailed);
}

bool V4l2ExynosBackend::apply_region(const StartupRegion region) {
    const int32_t band = map_region_to_s610_band(region);
    const RegionProfile &profile = get_region_profile(region);

    if (!set_v4l2_ctrl(kS610CidChBand, band)) {
        return false;
    }
    if (!set_v4l2_ctrl(kS610CidTuneDeemphasis, map_emphasis_to_s610(profile.emphasis))) {
        log("region", "warning: failed to set deemphasis");
    }

    region_ = region;
    return true;
}

bool V4l2ExynosBackend::apply_spacing(const int spacing_khz) {
    const int32_t step = map_spacing_to_s610(spacing_khz);
    if (!set_v4l2_ctrl(kS610CidChSpacing, step)) {
        return false;
    }
    spacing_khz_ = spacing_khz;
    return true;
}

bool V4l2ExynosBackend::apply_mute(const bool mute) {
    return set_v4l2_ctrl(kS610CidAudioMute, mute ? kS610MuteOn : kS610MuteOff);
}

bool V4l2ExynosBackend::apply_rds(const bool enable) {
    const int32_t mode = enable ? kS610RdsEnableRaw : kS610RdsDisable;
    if (!set_v4l2_ctrl(kS610CidRdsOn, mode)) {
        return false;
    }
    rds_enabled_.store(enable);
    return true;
}

void V4l2ExynosBackend::drain_rds_fifo() {
    if (fd_radio_ < 0) {
        return;
    }
    uint8_t discard[kRdsReadBufferBytes];
    const int orig_flags = get_fd_flags(fd_radio_);
    if (orig_flags >= 0 && (orig_flags & O_NONBLOCK) == 0) {
        set_fd_flags(fd_radio_, orig_flags | O_NONBLOCK);
    }
    // Called only before start_rds_thread() when no worker is reading fd_radio_.
    // s610_radio_fops_read blocks on wait_event_interruptible when is_rds_new == 0,
    // so poll() before draining any buffered packets.
    while (poll_rds_fd(fd_radio_, 0) > 0) {
        if (read_rds_fd(fd_radio_, discard, sizeof(discard)) <= 0) {
            break;
        }
    }
    if (orig_flags >= 0 && (orig_flags & O_NONBLOCK) == 0) {
        set_fd_flags(fd_radio_, orig_flags);
    }
}

bool V4l2ExynosBackend::tune_frequency_khz(const uint32_t frequency_khz) {
    uint32_t lower_khz = 0;
    uint32_t upper_khz = 0;
    get_exynos_band_bounds(region_, &lower_khz, &upper_khz);
    const uint32_t clamped = std::min(std::max(frequency_khz, lower_khz), upper_khz);

    struct v4l2_frequency freq = {};
    freq.tuner = 0;
    freq.type = V4L2_TUNER_RADIO;
    freq.frequency = clamped * kS610FreqMultiplier;

    if (v4l2_ioctl(fd_radio_, VIDIOC_S_FREQUENCY, &freq) < 0) {
        const int saved_errno = errno;
        log(
                "tune",
                "VIDIOC_S_FREQUENCY khz=%u failed, errno=%d (%s)",
                clamped,
                saved_errno,
                std::strerror(saved_errno)
        );
        return false;
    }

    uint32_t actual_khz = clamped;
    if (read_frequency_khz(&actual_khz)) {
        current_frequency_khz_ = actual_khz;
    } else {
        current_frequency_khz_ = clamped;
    }
    return true;
}

bool V4l2ExynosBackend::read_frequency_khz(uint32_t *frequency_khz) {
    if (frequency_khz == nullptr || fd_radio_ < 0) {
        return false;
    }

    struct v4l2_frequency freq = {};
    freq.tuner = 0;
    freq.type = V4L2_TUNER_RADIO;
    if (v4l2_ioctl(fd_radio_, VIDIOC_G_FREQUENCY, &freq) < 0) {
        return false;
    }

    *frequency_khz = freq.frequency / kS610FreqMultiplier;
    return true;
}

bool V4l2ExynosBackend::read_stereo_state(bool *is_stereo) {
    if (is_stereo == nullptr || fd_radio_ < 0) {
        return false;
    }

    struct v4l2_tuner tuner = {};
    tuner.index = 0;
    if (v4l2_ioctl(fd_radio_, VIDIOC_G_TUNER, &tuner) < 0) {
        return false;
    }

    *is_stereo = (tuner.audmode != V4L2_TUNER_MODE_MONO);
    return true;
}

bool V4l2ExynosBackend::enable(const StartupConfig &config) {
    log("enable", "requested freq=%u region=%s spacing=%d stereo=%d soft_mute=%d",
            config.frequency_khz,
            get_region_name(config.region),
            config.spacing_khz,
            config.stereo ? 1 : 0,
            config.soft_mute ? 1 : 0);

    stop_search_thread();
    if (!stop_rds_thread()) {
        log("enable", "rejected: previous RDS worker still active");
        return set_status(false, kErrFailed);
    }

    {
        std::lock_guard<std::mutex> lock(ioctl_mutex_);
        if (!ensure_open()) {
            return set_status(false, kErrFailed);
        }

        apply_mute(true);
        set_v4l2_ctrl(kS610CidRssiTh, kS610DefaultRssiThreshold);
        set_v4l2_ctrl(kS610CidIfCount1, kS610DefaultIfCount1);
        set_v4l2_ctrl(kS610CidIfCount2, kS610DefaultIfCount2);
        set_v4l2_ctrl(kS610CidSoftStereoBlendCoeff, kS610DefaultBlendCoeff);
        soft_mute_ = config.soft_mute;
        set_v4l2_ctrl(kS610CidSoftMuteCoeff, soft_mute_ ? kS610DefaultSoftMuteCoeff : 0);
        set_v4l2_ctrl(kS610CidSoftStereoBlend, 0);

        if (!apply_region(config.region)) {
            close_device();
            return set_status(false, kErrCantSetRegion);
        }
        if (!apply_spacing(config.spacing_khz)) {
            close_device();
            return set_status(false, kErrFailed);
        }

        struct v4l2_tuner tuner = {};
        tuner.index = 0;
        tuner.audmode = config.stereo ? V4L2_TUNER_MODE_STEREO : V4L2_TUNER_MODE_MONO;
        if (v4l2_ioctl(fd_radio_, VIDIOC_S_TUNER, &tuner) < 0) {
            log("enable", "warning: VIDIOC_S_TUNER failed");
        }

        {
            std::lock_guard<std::mutex> vol_lock(volume_mutex_);
            const int target = desired_volume_;
            if (set_v4l2_ctrl(kS610CidAudioVolume, target)) {
                applied_volume_ = target;
            }
        }

        reset_rds_state();
        if (!apply_rds(true)) {
            log("enable", "warning: failed to enable raw RDS");
        }

        if (!tune_frequency_khz(config.frequency_khz)) {
            apply_rds(false);
            close_device();
            return set_status(false, kErrFailed);
        }
        drain_rds_fifo();

        if (!apply_mute(false)) {
            apply_rds(false);
            close_device();
            return set_status(false, kErrFailed);
        }

        enabled_.store(true);
    }

    start_rds_thread();
    publish_state(
            static_cast<int>(current_frequency_khz_),
            config.stereo ? 1 : 0,
            RADIO_PATCH_ABSENT_METRIC,
            RADIO_PATCH_ABSENT_METRIC
    );
    request_signal_metric();
    return set_status(true, "");
}

bool V4l2ExynosBackend::disable() {
    log("disable", "requested");
    enabled_.store(false);
    stop_search_thread();

    {
        std::lock_guard<std::mutex> lock(ioctl_mutex_);
        if (fd_radio_ >= 0) {
            apply_mute(true);
            apply_rds(false);
        }
    }

    const bool rds_stopped = stop_rds_thread();
    {
        std::lock_guard<std::mutex> lock(ioctl_mutex_);
        if (fd_radio_ >= 0 && rds_stopped) {
            close_device();
            std::lock_guard<std::mutex> vol_lock(volume_mutex_);
            applied_volume_ = -1;
        }
    }

    send_native_event("disabled");
    return set_status(rds_stopped, kErrFailed);
}

bool V4l2ExynosBackend::set_frequency(const uint32_t frequency_khz) {
    if (is_scan_or_seek_active()) {
        return set_status(false, kErrFailed);
    }

    bool ok = false;
    {
        std::lock_guard<std::mutex> lock(ioctl_mutex_);
        if (fd_radio_ < 0) {
            return set_status(false, kErrFailed);
        }
        reset_rds_state();
        ok = tune_frequency_khz(frequency_khz);
        if (ok) {
            rds_generation_.fetch_add(1, std::memory_order_acq_rel);
        }
    }

    if (ok) {
        publish_state(
                static_cast<int>(current_frequency_khz_),
                RADIO_PATCH_ABSENT_INT,
                RADIO_PATCH_ABSENT_METRIC,
                RADIO_PATCH_ABSENT_METRIC
        );
    }
    return set_status(ok, kErrFailed);
}

bool V4l2ExynosBackend::jump(const int direction, uint32_t *new_frequency) {
    if (is_scan_or_seek_active()) {
        return set_status(false, kErrFailed);
    }

    uint32_t lower_khz = 0;
    uint32_t upper_khz = 0;
    get_exynos_band_bounds(region_, &lower_khz, &upper_khz);
    const int step = spacing_khz_ > 0 ? spacing_khz_ : 100;
    const int delta = (direction >= 0 ? 1 : -1) * step;

    int32_t target = static_cast<int32_t>(current_frequency_khz_) + delta;
    const int32_t lower = static_cast<int32_t>(lower_khz);
    const int32_t upper = static_cast<int32_t>(upper_khz);

    if (target > upper) {
        target = lower;
    } else if (target < lower) {
        target = upper;
    }

    if (!set_frequency(static_cast<uint32_t>(target))) {
        return false;
    }

    if (new_frequency != nullptr) {
        *new_frequency = current_frequency_khz_;
    }
    return true;
}

bool V4l2ExynosBackend::perform_hw_seek(const bool upward, const bool wrap_around, int *out_errno) {
    if (out_errno != nullptr) {
        *out_errno = 0;
    }

    if (fd_radio_ < 0) {
        if (out_errno != nullptr) {
            *out_errno = EBADF;
        }

        return false;
    }

    // s610_radio_s_hw_freq_seek returns -EAGAIN if O_NONBLOCK is set on fd.
    const int orig_flags = get_fd_flags(fd_radio_);
    if (orig_flags >= 0 && (orig_flags & O_NONBLOCK) != 0) {
        set_fd_flags(fd_radio_, orig_flags & ~O_NONBLOCK);
    }

    // Stock-HAL ritual before each seek: s_ctrl maps control value 4 to
    // FM_TUNER_AUTONOMOUS_SEARCH_MODE_NEXT and only records radio->seek_mode
    // (read back via G_CTRL); the tuner mode itself is programmed by the seek
    // ioctl, which also clears any FM_TUNER_STOP_SEARCH_MODE left by SEEK_CANCEL.
    set_v4l2_ctrl(kS610CidSeekMode, kS610SeekModeNext);

    struct v4l2_hw_freq_seek hw_seek = {};
    hw_seek.tuner = 0;
    hw_seek.type = V4L2_TUNER_RADIO;
    hw_seek.seek_upward = upward ? 1 : 0;
    hw_seek.wrap_around = wrap_around ? 1 : 0;
    hw_seek.spacing = static_cast<uint32_t>(spacing_khz_ * 1000);

    const int ret = v4l2_ioctl(fd_radio_, VIDIOC_S_HW_FREQ_SEEK, &hw_seek);
    const int saved_errno = errno;

    if (orig_flags >= 0 && (orig_flags & O_NONBLOCK) != 0) {
        set_fd_flags(fd_radio_, orig_flags);
    }

    if (ret < 0) {
        if (out_errno != nullptr) {
            *out_errno = saved_errno;
        }

        log(
                "seek",
                "VIDIOC_S_HW_FREQ_SEEK upward=%d wrap=%d failed, errno=%d (%s)",
                upward ? 1 : 0,
                wrap_around ? 1 : 0,
                saved_errno,
                std::strerror(saved_errno)
        );
        return false;
    }
    return true;
}

bool V4l2ExynosBackend::seek(const int direction) {
    if (!enabled_.load() || is_scan_or_seek_active()) {
        return set_status(false, kErrFailed);
    }

    stop_search_thread();
    search_cancel_.store(false);
    seek_active_.store(true);
    search_thread_ = std::thread(&V4l2ExynosBackend::seek_worker, this, direction);
    return set_status(true, "");
}

void V4l2ExynosBackend::seek_worker(const int direction) {
    uint32_t found_khz = current_frequency_khz_;
    bool ok = false;
    bool cancelled = false;

    {
        std::lock_guard<std::mutex> lock(ioctl_mutex_);
        if (fd_radio_ >= 0 && enabled_.load() && !search_cancel_.load()) {
            apply_mute(true);
            reset_rds_state();
            ok = perform_hw_seek(direction >= 0, true);
            cancelled = search_cancel_.load() || !enabled_.load();
            if (ok && !cancelled && read_frequency_khz(&found_khz)) {
                current_frequency_khz_ = found_khz;
            }
            rds_generation_.fetch_add(1, std::memory_order_acq_rel);
            {
                std::lock_guard<std::mutex> vol_lock(volume_mutex_);
                const int target = desired_volume_;
                if (set_v4l2_ctrl(kS610CidAudioVolume, target)) {
                    applied_volume_ = target;
                }
            }
            apply_mute(false);
        }
    }

    const bool emit_done = ok && !cancelled && enabled_.load();
    if (emit_done) {
        publish_state(
                static_cast<int>(current_frequency_khz_),
                RADIO_PATCH_ABSENT_INT,
                RADIO_PATCH_ABSENT_METRIC,
                RADIO_PATCH_ABSENT_METRIC
        );
        send_native_event("seek_done");
    }

    finish_scan_or_seek_volume_sync(&seek_active_);

    if (emit_done) {
        request_signal_metric();
    }
}

bool V4l2ExynosBackend::set_power_mode(const bool low_power) {
    if (is_scan_or_seek_active()) {
        return set_status(false, kErrFailed);
    }

    std::lock_guard<std::mutex> lock(ioctl_mutex_);
    if (fd_radio_ < 0) {
        return set_status(false, kErrFailed);
    }
    const bool ok = apply_rds(!low_power);
    return set_status(ok, kErrFailed);
}

bool V4l2ExynosBackend::set_stereo(const bool enabled) {
    if (is_scan_or_seek_active()) {
        return set_status(false, kErrFailed);
    }

    bool ok = false;
    {
        std::lock_guard<std::mutex> lock(ioctl_mutex_);
        if (fd_radio_ < 0) {
            return set_status(false, kErrFailed);
        }

        struct v4l2_tuner tuner = {};
        tuner.index = 0;
        tuner.audmode = enabled ? V4L2_TUNER_MODE_STEREO : V4L2_TUNER_MODE_MONO;
        ok = v4l2_ioctl(fd_radio_, VIDIOC_S_TUNER, &tuner) == 0;
    }

    if (ok) {
        publish_state(
                RADIO_PATCH_ABSENT_INT,
                enabled ? 1 : 0,
                RADIO_PATCH_ABSENT_METRIC,
                RADIO_PATCH_ABSENT_METRIC
        );
    }
    return set_status(ok, kErrFailed);
}

bool V4l2ExynosBackend::set_volume(const int volume) {
    const int clamped = std::max(0, std::min(15, volume));
    {
        std::lock_guard<std::mutex> vol_lock(volume_mutex_);
        desired_volume_ = clamped;
        // Do not block on ioctl_mutex_ while a full-band scan is running;
        // respond "ok" immediately (hardware mute is active during scan) and let
        // search_worker() sync desired_volume_ before unmuting/completing.
        if (is_scan_or_seek_active()) {
            return set_status(true, "");
        }
    }

    std::lock_guard<std::mutex> ioctl_lock(ioctl_mutex_);
    std::lock_guard<std::mutex> vol_lock(volume_mutex_);
    if (fd_radio_ < 0) {
        return set_status(true, "");
    }
    const int target = desired_volume_;
    const bool ok = set_v4l2_ctrl(kS610CidAudioVolume, target);
    if (ok) {
        applied_volume_ = target;
    }
    return set_status(ok, kErrFailed);
}

bool V4l2ExynosBackend::set_antenna(const int /*antenna*/) {
    return set_status(true, "");
}

bool V4l2ExynosBackend::set_region(const StartupRegion region) {
    if (is_scan_or_seek_active()) {
        return set_status(false, kErrFailed);
    }

    uint32_t retuned_khz = 0;
    {
        std::lock_guard<std::mutex> lock(ioctl_mutex_);
        if (fd_radio_ < 0) {
            return set_status(false, kErrFailed);
        }
        if (!apply_region(region)) {
            return set_status(false, kErrCantSetRegion);
        }
        uint32_t lower_khz = 0;
        uint32_t upper_khz = 0;
        get_exynos_band_bounds(region, &lower_khz, &upper_khz);
        const uint32_t clamped = std::min(std::max(current_frequency_khz_, lower_khz), upper_khz);
        if (clamped != current_frequency_khz_) {
            reset_rds_state();
            if (!tune_frequency_khz(clamped)) {
                return set_status(false, kErrFailed);
            }
            rds_generation_.fetch_add(1, std::memory_order_acq_rel);
            retuned_khz = current_frequency_khz_;
        }
    }

    if (retuned_khz > 0) {
        publish_state(
                static_cast<int>(retuned_khz),
                RADIO_PATCH_ABSENT_INT,
                RADIO_PATCH_ABSENT_METRIC,
                RADIO_PATCH_ABSENT_METRIC
        );
    }
    return set_status(true, "");
}

bool V4l2ExynosBackend::set_spacing(const int spacing_khz) {
    if (is_scan_or_seek_active()) {
        return set_status(false, kErrFailed);
    }

    std::lock_guard<std::mutex> lock(ioctl_mutex_);
    if (fd_radio_ < 0) {
        return set_status(false, kErrFailed);
    }
    return set_status(apply_spacing(spacing_khz), kErrFailed);
}

bool V4l2ExynosBackend::search() {
    if (fd_radio_ < 0) {
        return set_status(false, kErrFailed);
    }
    if (is_scan_or_seek_active()) {
        return set_status(false, kErrFailed);
    }

    stop_search_thread();
    search_cancel_.store(false);
    search_active_.store(true);
    search_thread_ = std::thread(&V4l2ExynosBackend::search_worker, this);
    return set_status(true, "");
}

void V4l2ExynosBackend::search_worker() {
    ScanResultCollector collector;
    uint32_t lower_khz = 0;
    uint32_t upper_khz = 0;
    get_exynos_band_bounds(region_, &lower_khz, &upper_khz);
    const uint32_t saved_khz = current_frequency_khz_;
    bool scan_failed = false;
    bool cancelled = false;
    bool publish_restored_state = false;
    uint32_t restored_khz = saved_khz;

    {
        std::lock_guard<std::mutex> lock(ioctl_mutex_);

        if (fd_radio_ < 0) {
            scan_failed = true;
        } else {
            apply_mute(true);

            if (!tune_frequency_khz(lower_khz)) {
                log("search", "failed to tune to lower band edge %u", lower_khz);
                scan_failed = true;
            } else {
                uint32_t prev_khz = lower_khz;

                while (!search_cancel_.load() && enabled_.load()) {
                    int seek_errno = 0;

                    if (!perform_hw_seek(true, false, &seek_errno)) {
                        if (search_cancel_.load() || !enabled_.load() || seek_errno == ECANCELED) {
                            cancelled = true;
                        } else if (seek_errno != EAGAIN && seek_errno != ENODATA && seek_errno != ERANGE) {
                            log("search", "seek step failed with errno=%d", seek_errno);
                            scan_failed = true;
                        }
                        break;
                    }

                    uint32_t found_khz = 0;

                    if (!read_frequency_khz(&found_khz)) {
                        log("search", "failed to read frequency after seek step");
                        scan_failed = true;
                        break;
                    }

                    if (found_khz <= prev_khz || found_khz > upper_khz) {
                        break;
                    }

                    collector.add(static_cast<int>(found_khz));
                    prev_khz = found_khz;

                    if (found_khz >= upper_khz) {
                        break;
                    }
                }
            }

            if (search_cancel_.load() || !enabled_.load()) {
                cancelled = true;
            }

            reset_rds_state();

            if (enabled_.load()) {
                if (!tune_frequency_khz(saved_khz)) {
                    log("search", "failed to restore frequency_khz=%u after scan", saved_khz);
                    scan_failed = true;
                    uint32_t actual_khz = 0;

                    if (read_frequency_khz(&actual_khz)) {
                        current_frequency_khz_ = actual_khz;
                        restored_khz = actual_khz;
                        publish_restored_state = true;
                    }
                }

                rds_generation_.fetch_add(1, std::memory_order_acq_rel);
                {
                    std::lock_guard<std::mutex> vol_lock(volume_mutex_);
                    const int target = desired_volume_;
                    if (set_v4l2_ctrl(kS610CidAudioVolume, target)) {
                        applied_volume_ = target;
                    }
                }
                apply_mute(false);
            }
        }
    }

    if (publish_restored_state) {
        publish_state(
                static_cast<int>(restored_khz),
                RADIO_PATCH_ABSENT_INT,
                RADIO_PATCH_ABSENT_METRIC,
                RADIO_PATCH_ABSENT_METRIC
        );
    }

    if (enabled_.load() && !cancelled) {
        if (scan_failed) {
            send_native_event("search_failed");
        } else {
            ScanResult result;
            collector.copy_sorted(&result);
            send_search_done(result.frequencies_khz, result.count);
        }
    }

    finish_scan_or_seek_volume_sync(&search_active_);
}

void V4l2ExynosBackend::finish_scan_or_seek_volume_sync(std::atomic<bool> *active_flag) {
    bool needs_volume_sync = false;
    {
        std::lock_guard<std::mutex> vol_lock(volume_mutex_);
        active_flag->store(false);
        needs_volume_sync = enabled_.load() && desired_volume_ != applied_volume_;
    }
    if (needs_volume_sync) {
        std::lock_guard<std::mutex> ioctl_lock(ioctl_mutex_);
        std::lock_guard<std::mutex> vol_lock(volume_mutex_);
        if (fd_radio_ >= 0 && enabled_.load() && desired_volume_ != applied_volume_) {
            const int target = desired_volume_;
            if (set_v4l2_ctrl(kS610CidAudioVolume, target)) {
                applied_volume_ = target;
            }
        }
    }
}

bool V4l2ExynosBackend::cancel_search() {
    stop_search_thread();
    return set_status(true, "");
}

void V4l2ExynosBackend::stop_search_thread() {
    search_cancel_.store(true);
    if (is_scan_or_seek_active() && fd_radio_ >= 0) {
        // S610 seeks check seek_status without acquiring s610_core_lock on the caller side.
        set_v4l2_ctrl(kS610CidSeekCancel, 1);
    }
    if (search_thread_.joinable()) {
        search_thread_.join();
    }
    search_active_.store(false);
    seek_active_.store(false);
}

bool V4l2ExynosBackend::set_auto_af(const bool /*enabled*/) {
    return set_status(true, "");
}

bool V4l2ExynosBackend::set_soft_mute(const bool enabled) {
    if (is_scan_or_seek_active()) {
        return set_status(false, kErrFailed);
    }

    std::lock_guard<std::mutex> lock(ioctl_mutex_);
    if (fd_radio_ < 0) {
        return set_status(false, kErrFailed);
    }
    soft_mute_ = enabled;
    const bool ok = set_v4l2_ctrl(
            kS610CidSoftMuteCoeff,
            enabled ? kS610DefaultSoftMuteCoeff : 0
    );
    return set_status(ok, kErrFailed);
}

bool V4l2ExynosBackend::set_notch_filter(const int /*mode*/) {
    return set_status(true, "");
}

bool V4l2ExynosBackend::set_slimbus(const bool /*enabled*/) {
    return set_status(true, "");
}

bool V4l2ExynosBackend::request_signal_metric() {
    if (!enabled_.load() || search_active_.load() || seek_active_.load()) {
        return set_status(false, kErrFailed);
    }

    int32_t raw_rssi = 0;
    int32_t raw_snr = 0;
    bool is_stereo = false;
    bool have_rssi = false;
    bool have_snr = false;
    bool have_stereo = false;

    {
        std::lock_guard<std::mutex> lock(ioctl_mutex_);
        if (fd_radio_ < 0) {
            return set_status(false, kErrFailed);
        }
        have_rssi = get_v4l2_ctrl(kS610CidRssiCurr, &raw_rssi);
        have_snr = get_v4l2_ctrl(kS610CidSnrCurr, &raw_snr);
        have_stereo = read_stereo_state(&is_stereo);
    }

    if (!have_rssi && !have_snr && !have_stereo) {
        return set_status(false, kErrFailed);
    }

    const int rmssi = have_rssi
            ? static_cast<int>(static_cast<int8_t>(raw_rssi & 0xFF))
            : RADIO_PATCH_ABSENT_METRIC;
    const int sinr = have_snr
            ? std::max(-128, std::min(127, static_cast<int>(raw_snr)))
            : RADIO_PATCH_ABSENT_METRIC;
    const int stereo = have_stereo
            ? (is_stereo ? 1 : 0)
            : RADIO_PATCH_ABSENT_INT;

    publish_state(RADIO_PATCH_ABSENT_INT, stereo, rmssi, sinr);
    return set_status(true, "");
}

void V4l2ExynosBackend::shutdown() {
    enabled_.store(false);
    stop_search_thread();

    {
        std::lock_guard<std::mutex> lock(ioctl_mutex_);
        if (fd_radio_ >= 0) {
            apply_mute(true);
            apply_rds(false);
        }
    }

    const bool rds_stopped = stop_rds_thread();
    std::lock_guard<std::mutex> lock(ioctl_mutex_);
    if (fd_radio_ >= 0 && rds_stopped) {
        close_device();
        std::lock_guard<std::mutex> vol_lock(volume_mutex_);
        applied_volume_ = -1;
    }
}

void V4l2ExynosBackend::start_rds_thread() {
    if (!stop_rds_thread()) {
        return;
    }
    rds_stop_.store(false);
    rds_thread_running_.store(true);
    rds_thread_ = std::thread(&V4l2ExynosBackend::rds_worker, this);
}

bool V4l2ExynosBackend::stop_rds_thread() {
    rds_stop_.store(true);
    if (rds_thread_.joinable()) {
        install_rds_interrupt_signal_handler();
        for (int attempt = 0; attempt < kRdsThreadStopMaxAttempts; ++attempt) {
            if (!rds_thread_running_.load()) {
                break;
            }
            pthread_kill(rds_thread_.native_handle(), SIGUSR1);
            usleep(kRdsThreadStopPollUs);
        }
        if (!rds_thread_running_.load()) {
            rds_thread_.join();
            return true;
        }
        log("rds", "RDS worker did not exit within teardown bound");
        rds_thread_.detach();
        return false;
    }
    return !rds_thread_running_.load();
}

void V4l2ExynosBackend::rds_worker() {
    install_rds_interrupt_signal_handler();
    sigset_t unblock_set;
    sigemptyset(&unblock_set);
    sigaddset(&unblock_set, SIGUSR1);
    pthread_sigmask(SIG_UNBLOCK, &unblock_set, nullptr);

    uint8_t buf[kRdsReadBufferBytes] = {};

    while (!rds_stop_.load() && enabled_.load()) {
        if (fd_radio_ < 0 || !rds_enabled_.load() || search_active_.load() || seek_active_.load()) {
            usleep(20000);
            continue;
        }

        const uint64_t gen = rds_generation_.load(std::memory_order_acquire);
        const int ready = poll_rds_fd(fd_radio_, kRdsPollTimeoutMs);
        if (ready <= 0) {
            continue;
        }
        if (rds_stop_.load() || !enabled_.load() || search_active_.load() || seek_active_.load()
                || gen != rds_generation_.load(std::memory_order_acquire)) {
            continue;
        }

        const ssize_t nread = read_rds_fd(fd_radio_, buf, sizeof(buf));
        if (nread <= 0) {
            continue;
        }
        if (rds_stop_.load() || !enabled_.load() || search_active_.load() || seek_active_.load()
                || gen != rds_generation_.load(std::memory_order_acquire)) {
            continue;
        }

        const ssize_t usable = (nread / kS610RdsBlockSize) * kS610RdsBlockSize;
        for (ssize_t offset = 0; offset < usable; offset += kS610RdsBlockSize) {
            if (rds_stop_.load() || !enabled_.load()
                    || gen != rds_generation_.load(std::memory_order_acquire)) {
                break;
            }
            const uint16_t data = static_cast<uint16_t>(buf[offset])
                    | (static_cast<uint16_t>(buf[offset + 1]) << 8);
            const uint8_t status = buf[offset + 2];
            const uint8_t blk_type = status & kS610RdsBlkTypeMask;
            const uint8_t errors = (status >> kS610RdsErrorsShift) & kS610RdsErrorsMask;
            process_rds_block(data, blk_type, errors);
        }
    }

    rds_thread_running_.store(false);
}

void V4l2ExynosBackend::reset_rds_state() {
    rds_generation_.fetch_add(1, std::memory_order_acq_rel);
    std::lock_guard<std::mutex> lock(rds_mutex_);
    rds_ = RdsDecoderState{};

    radio_state_patch_t patch = radio_state_patch_empty();
    patch.ps = "";
    patch.rt = "";
    patch.pi = "";
    patch.country = "";
    patch.pty = 0;
    patch.rt_plus_title = "";
    patch.rt_plus_artist = "";
    patch.rt_plus_album = "";
    send_radio_state_patch(&patch);
}

void V4l2ExynosBackend::process_rds_block(
        const uint16_t data,
        const uint8_t blk_type,
        const uint8_t errors
) {
    std::lock_guard<std::mutex> lock(rds_mutex_);

    // Reject any block with error-correction bits to avoid false-positive CRC
    // passes on PI, ECC, and text segments, and reset the in-flight group.
    if (errors != 0) {
        std::memset(rds_.have_block, 0, sizeof(rds_.have_block));
        return;
    }

    if (blk_type == kS610RdsBlkA) {
        std::memset(rds_.have_block, 0, sizeof(rds_.have_block));
        rds_.blocks[0] = data;
        rds_.have_block[0] = true;
        return;
    }

    if (!rds_.have_block[0]) {
        return;
    }

    if (blk_type == kS610RdsBlkB) {
        rds_.blocks[1] = data;
        rds_.have_block[1] = true;
        return;
    }

    if (!rds_.have_block[1]) {
        std::memset(rds_.have_block, 0, sizeof(rds_.have_block));
        return;
    }

    if (blk_type == kS610RdsBlkC || blk_type == kS610RdsBlkCPrime) {
        rds_.blocks[2] = data;
        rds_.have_block[2] = true;
        return;
    }

    if (blk_type == kS610RdsBlkD) {
        rds_.blocks[3] = data;
        rds_.have_block[3] = true;
        flush_rds_group();
        std::memset(rds_.have_block, 0, sizeof(rds_.have_block));
    }
}

void V4l2ExynosBackend::flush_rds_group() {
    if (!rds_.have_block[0] || !rds_.have_block[1]) {
        return;
    }

    const uint16_t a = rds_.blocks[0];
    const uint16_t b = rds_.blocks[1];
    const int group_type = (b >> 12) & 0x0F;
    const bool version_b = ((b >> 11) & 0x01) != 0;
    const int pty = (b >> 5) & 0x1F;

    radio_state_patch_t patch = radio_state_patch_empty();
    char pi_buf[8] = {};
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
        std::snprintf(pi_buf, sizeof(pi_buf), "%04X", a);
        patch.pi = pi_buf;
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
        if (dirty) {
            send_radio_state_patch(&patch);
        }
        return;
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
            rds_.rtplus_tags_valid = false;
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
            std::memcpy(rds_.rt_committed_raw, rds_.rt_raw, static_cast<size_t>(trimmed));
            rds_.rt_committed_raw_len = trimmed;
            std::memset(rds_.rt_raw, ' ', sizeof(rds_.rt_raw));
            if (std::strcmp(rds_.rt_utf8, rt_buf) != 0) {
                std::snprintf(rds_.rt_utf8, sizeof(rds_.rt_utf8), "%s", rt_buf);
                patch.rt = rds_.rt_utf8;
                dirty = true;
                // New RadioText string invalidates RT+ tag offsets from the previous message.
                rds_.rtplus_tags_valid = false;
                clear_rt_plus_slices(&patch, &dirty);
            } else if (rds_.rtplus_tags_valid && !rds_.rtplus_ert) {
                apply_rt_plus_slices(&patch, &dirty);
            }
        }
    } else if (group_type == 3 && !version_b && rds_.have_block[2] && rds_.have_block[3]) {
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
                rds_.rtplus_ert = ((c >> 13) & 0x01) != 0;
            }
        }
    } else if (rds_.rtplus_group >= 0
            && group_type == rds_.rtplus_group
            && version_b == rds_.rtplus_group_is_b
            && rds_.have_block[2]
            && rds_.have_block[3]) {
        const uint16_t c = rds_.blocks[2];
        const uint16_t d = rds_.blocks[3];
        const int type1 = ((b & 0x07) << 3) | ((c >> 13) & 0x07);
        const int start1 = (c >> 7) & 0x3F;
        const int len1 = ((c >> 1) & 0x3F) + 1;
        const int type2 = ((c & 0x01) << 5) | ((d >> 11) & 0x1F);
        const int start2 = (d >> 5) & 0x3F;
        const int len2 = (d & 0x1F) + 1;

        rds_.rtplus_tags[0] = {type1, start1, len1};
        rds_.rtplus_tags[1] = {type2, start2, len2};
        rds_.rtplus_tags_valid = true;

        if (!rds_.rtplus_ert && rds_.rt_committed_raw_len > 0) {
            apply_rt_plus_slices(&patch, &dirty);
        }
    }

    if (dirty) {
        send_radio_state_patch(&patch);
    }
}

void V4l2ExynosBackend::apply_rt_plus_slices(radio_state_patch_t *patch, bool *dirty) {
    RdsRtPlusSlices slices;
    evaluate_rds_rt_plus_slices(
            true,
            rds_.rtplus_tags,
            rds_.rt_committed_raw,
            rds_.rt_committed_raw_len,
            &slices
    );
    if (std::strcmp(rds_.rtplus_slices.title, slices.title) != 0) {
        std::snprintf(rds_.rtplus_slices.title, sizeof(rds_.rtplus_slices.title), "%s", slices.title);
        patch->rt_plus_title = rds_.rtplus_slices.title;
        *dirty = true;
    }
    if (std::strcmp(rds_.rtplus_slices.artist, slices.artist) != 0) {
        std::snprintf(rds_.rtplus_slices.artist, sizeof(rds_.rtplus_slices.artist), "%s", slices.artist);
        patch->rt_plus_artist = rds_.rtplus_slices.artist;
        *dirty = true;
    }
    if (std::strcmp(rds_.rtplus_slices.album, slices.album) != 0) {
        std::snprintf(rds_.rtplus_slices.album, sizeof(rds_.rtplus_slices.album), "%s", slices.album);
        patch->rt_plus_album = rds_.rtplus_slices.album;
        *dirty = true;
    }
}

void V4l2ExynosBackend::clear_rt_plus_slices(radio_state_patch_t *patch, bool *dirty) {
    if (rds_.rtplus_slices.title[0] != '\0') {
        rds_.rtplus_slices.title[0] = '\0';
        patch->rt_plus_title = rds_.rtplus_slices.title;
        *dirty = true;
    }
    if (rds_.rtplus_slices.artist[0] != '\0') {
        rds_.rtplus_slices.artist[0] = '\0';
        patch->rt_plus_artist = rds_.rtplus_slices.artist;
        *dirty = true;
    }
    if (rds_.rtplus_slices.album[0] != '\0') {
        rds_.rtplus_slices.album[0] = '\0';
        patch->rt_plus_album = rds_.rtplus_slices.album;
        *dirty = true;
    }
}

void V4l2ExynosBackend::publish_state(
        const int frequency_khz,
        const int stereo,
        const int rmssi,
        const int sinr
) {
    radio_state_patch_t patch = radio_state_patch_empty();
    patch.frequency_khz = frequency_khz;
    patch.stereo = stereo;
    patch.rmssi = rmssi;
    patch.sinr = sinr;
    send_radio_state_patch(&patch);
}

Backend *create_v4l2_exynos_backend() {
    return new V4l2ExynosBackend();
}
