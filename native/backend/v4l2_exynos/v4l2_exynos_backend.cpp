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

constexpr Logger kExynosLog{"exynos"};
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

}  // namespace

V4l2ExynosBackend::V4l2ExynosBackend() : V4l2BackendBase(kExynosLog), rds_decoder_(log) {}

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
        // Keep the driver's default soft-mute coefficient (0x1B16): Samsung's S610
        // kernel driver (drivers/media/radio/s610/fm_low.c) hardcodes use_soft_mute = TRUE,
        // so overwriting kS610CidSoftMuteCoeff only distorts noise gain.
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
                        } else if (seek_errno == EINVAL) {
                            // S610 returns EINVAL for FM_EVENT_BD_LMT on a non-wrapping seek.
                            // exynos/seek   : VIDIOC_S_HW_FREQ_SEEK upward=1 wrap=0 failed, errno=22 (Invalid argument)
                            // exynos/search : bounded seek EINVAL: stopped_khz=108000 upper_khz=108000
                            uint32_t stopped_khz = 0;
                            scan_failed = !read_frequency_khz(&stopped_khz) || stopped_khz != upper_khz;
                            log("search", "bounded seek EINVAL: stopped_khz=%u upper_khz=%u", stopped_khz, upper_khz);
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

                    if (collector.add(static_cast<int>(found_khz)) == ScanAddResult::kAdded) {
                        send_search_progress(collector.count());
                    }
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

bool V4l2ExynosBackend::set_soft_mute(const bool /*enabled*/) {
    // use_soft_mute is hardcoded TRUE in Samsung's S610 kernel driver
    // (drivers/media/radio/s610/fm_low.c); soft mute cannot be toggled.
    return set_status(true, "");
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
            std::lock_guard<std::mutex> lock(rds_mutex_);
            if (
                    rds_stop_.load() ||
                    !enabled_.load() ||
                    gen != rds_generation_.load(std::memory_order_acquire)
            ) {
                break;
            }
            const uint16_t data = static_cast<uint16_t>(buf[offset])
                    | (static_cast<uint16_t>(buf[offset + 1]) << 8);
            const uint8_t status = buf[offset + 2];
            const uint8_t blk_type = status & kS610RdsBlkTypeMask;
            const uint8_t errors = (status >> kS610RdsErrorsShift) & kS610RdsErrorsMask;
            if (const auto patch = rds_decoder_.process_block(data, blk_type, errors)) {
                send_radio_state_patch(&*patch);
            }
        }
    }

    rds_thread_running_.store(false);
}

void V4l2ExynosBackend::reset_rds_state() {
    rds_generation_.fetch_add(1, std::memory_order_acq_rel);
    std::lock_guard<std::mutex> lock(rds_mutex_);
    rds_decoder_.reset();

    const radio_state_patch_t patch = make_station_metadata_reset_patch();
    send_radio_state_patch(&patch);
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
