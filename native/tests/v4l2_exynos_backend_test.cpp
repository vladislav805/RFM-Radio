#include <gtest/gtest.h>

#include <cerrno>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <fcntl.h>
#include <linux/videodev2.h>
#include <mutex>
#include <string>
#include <unistd.h>
#include <unordered_map>
#include <vector>

#include "backend/v4l2_exynos/s610_fm_controls.h"
#include "backend/v4l2_exynos/v4l2_exynos_backend.h"
#include "ctl_server.h"

namespace {

class StdoutCapture {
public:
    StdoutCapture() {
        if (pipe(pipe_fds_) == 0) {
            saved_stdout_ = dup(STDOUT_FILENO);
            if (saved_stdout_ >= 0) {
                dup2(pipe_fds_[1], STDOUT_FILENO);
            }
        }
    }

    ~StdoutCapture() {
        restore();
        if (pipe_fds_[0] >= 0) {
            close(pipe_fds_[0]);
            pipe_fds_[0] = -1;
        }
    }

    std::string read_all() {
        restore();
        std::string output;
        if (pipe_fds_[0] >= 0) {
            char buffer[512];
            ssize_t n = 0;
            while ((n = read(pipe_fds_[0], buffer, sizeof(buffer))) > 0) {
                output.append(buffer, static_cast<size_t>(n));
            }
        }
        return output;
    }

private:
    void restore() {
        if (saved_stdout_ >= 0) {
            dup2(saved_stdout_, STDOUT_FILENO);
            close(saved_stdout_);
            saved_stdout_ = -1;
        }
        if (pipe_fds_[1] >= 0) {
            close(pipe_fds_[1]);
            pipe_fds_[1] = -1;
        }
    }

    int pipe_fds_[2] = {-1, -1};
    int saved_stdout_ = -1;
};

class FakeV4l2ExynosBackend : public V4l2ExynosBackend {
public:
    int32_t rssi_curr = -65;
    int32_t snr_curr = 200;
    uint32_t stereo_audmode = V4L2_TUNER_MODE_STEREO;
    uint32_t seek_target_khz = 102500;

    uint32_t raw_frequency_units() const {
        std::lock_guard<std::mutex> lock(mu_);
        return raw_frequency_;
    }

    int32_t get_written_ctrl(uint32_t id, int32_t fallback = -9999) const {
        std::lock_guard<std::mutex> lock(mu_);
        const auto it = controls_.find(id);
        return it != controls_.end() ? it->second : fallback;
    }

    bool saw_blocking_seek() const {
        std::lock_guard<std::mutex> lock(mu_);
        return saw_blocking_seek_;
    }

protected:
    int open_device_fd(const char *) override {
        std::lock_guard<std::mutex> lock(mu_);
        device_open_ = true;
        fd_flags_ = O_RDWR | O_NONBLOCK;
        return 42;
    }

    void close_device_fd(int) override {
        std::lock_guard<std::mutex> lock(mu_);
        device_open_ = false;
    }

    int poll_rds_fd(int, int) override {
        return 0;
    }

    ssize_t read_rds_fd(int, void *, size_t) override {
        return 0;
    }

    int get_fd_flags(int) override {
        std::lock_guard<std::mutex> lock(mu_);
        return fd_flags_;
    }

    int set_fd_flags(int, int flags) override {
        std::lock_guard<std::mutex> lock(mu_);
        fd_flags_ = flags;
        return 0;
    }

    int v4l2_ioctl(int, unsigned long request, void *arg) override {
        std::lock_guard<std::mutex> lock(mu_);
        if (!device_open_) {
            errno = EBADF;
            return -1;
        }

        if (request == VIDIOC_QUERYCAP) {
            auto *cap = static_cast<struct v4l2_capability *>(arg);
            std::memset(cap, 0, sizeof(*cap));
            std::snprintf(reinterpret_cast<char *>(cap->driver), sizeof(cap->driver), "s610-radio");
            cap->version = 1;
            return 0;
        }

        if (request == VIDIOC_S_CTRL) {
            auto *ctrl = static_cast<struct v4l2_control *>(arg);
            controls_[ctrl->id] = ctrl->value;
            return 0;
        }

        if (request == VIDIOC_G_CTRL) {
            auto *ctrl = static_cast<struct v4l2_control *>(arg);
            if (ctrl->id == kS610CidRssiCurr) {
                ctrl->value = rssi_curr & 0xFF;
                return 0;
            }
            if (ctrl->id == kS610CidSnrCurr) {
                ctrl->value = snr_curr;
                return 0;
            }
            errno = EINVAL;
            return -1;
        }

        if (request == VIDIOC_S_TUNER) {
            auto *tuner = static_cast<struct v4l2_tuner *>(arg);
            stereo_audmode = tuner->audmode;
            return 0;
        }

        if (request == VIDIOC_G_TUNER) {
            auto *tuner = static_cast<struct v4l2_tuner *>(arg);
            tuner->audmode = stereo_audmode;
            return 0;
        }

        if (request == VIDIOC_S_FREQUENCY) {
            auto *freq = static_cast<struct v4l2_frequency *>(arg);
            raw_frequency_ = freq->frequency;
            return 0;
        }

        if (request == VIDIOC_G_FREQUENCY) {
            auto *freq = static_cast<struct v4l2_frequency *>(arg);
            freq->frequency = raw_frequency_;
            return 0;
        }

        if (request == VIDIOC_S_HW_FREQ_SEEK) {
            if ((fd_flags_ & O_NONBLOCK) != 0) {
                errno = EAGAIN;
                return -1;
            }
            saw_blocking_seek_ = true;
            raw_frequency_ = seek_target_khz * kS610FreqMultiplier;
            return 0;
        }

        errno = ENOTTY;
        return -1;
    }

private:
    mutable std::mutex mu_;
    bool device_open_ = false;
    int fd_flags_ = O_RDWR | O_NONBLOCK;
    uint32_t raw_frequency_ = 0;
    bool saw_blocking_seek_ = false;
    std::unordered_map<uint32_t, int32_t> controls_;
};

StartupConfig make_default_config() {
    StartupConfig cfg;
    cfg.frequency_khz = 101700;
    cfg.region = StartupRegion::kEurope;
    cfg.spacing_khz = 100;
    cfg.stereo = true;
    cfg.soft_mute = true;
    cfg.antenna = 0;
    cfg.auto_af = true;
    return cfg;
}

void feed_group(
        FakeV4l2ExynosBackend *backend,
        uint16_t a,
        uint16_t b,
        uint16_t c,
        uint16_t d,
        uint8_t errors = 0
) {
    backend->feed_rds_block_for_test(a, kS610RdsBlkA, errors);
    backend->feed_rds_block_for_test(b, kS610RdsBlkB, errors);
    backend->feed_rds_block_for_test(c, kS610RdsBlkC, errors);
    backend->feed_rds_block_for_test(d, kS610RdsBlkD, errors);
}

TEST(V4l2ExynosBackendTest, UsesS610FrequencyMultiplierMutePolarityAndVolumeClamping) {
    reset_ctl_server_state_for_test();
    FakeV4l2ExynosBackend backend;
    const StartupConfig cfg = make_default_config();

    ASSERT_TRUE(backend.init());
    ASSERT_TRUE(backend.enable(cfg));
    EXPECT_EQ(backend.raw_frequency_units(), 101700u * 16000u);
    EXPECT_EQ(backend.get_written_ctrl(kS610CidAudioMute), kS610MuteOff);
    EXPECT_EQ(backend.get_written_ctrl(kS610CidAudioVolume), kS610DefaultVolume);

    EXPECT_TRUE(backend.set_volume(20));
    EXPECT_EQ(backend.get_written_ctrl(kS610CidAudioVolume), 15);
    EXPECT_TRUE(backend.set_volume(-5));
    EXPECT_EQ(backend.get_written_ctrl(kS610CidAudioVolume), 0);

    // Seek temporarily clears O_NONBLOCK so s610_radio_s_hw_freq_seek does not fail with EAGAIN.
    backend.seek_target_khz = 104200;
    EXPECT_TRUE(backend.seek(1));
    EXPECT_TRUE(backend.saw_blocking_seek());
    EXPECT_EQ(backend.raw_frequency_units(), 104200u * 16000u);

    // Switching to Japan (S610 band 1: 76000..90000 kHz) clamps and retunes from 104200 kHz to 90000 kHz.
    EXPECT_TRUE(backend.set_region(StartupRegion::kJapan));
    EXPECT_EQ(backend.raw_frequency_units(), 90000u * 16000u);

    EXPECT_TRUE(backend.disable());
    EXPECT_EQ(backend.get_written_ctrl(kS610CidAudioMute), kS610MuteOn);
    backend.shutdown();
    reset_ctl_server_state_for_test();
}

TEST(V4l2ExynosBackendTest, ClampsSnrToSignedByteRangeAndDecodesSignedRssi) {
    reset_ctl_server_state_for_test();
    FakeV4l2ExynosBackend backend;
    backend.rssi_curr = -72;
    backend.snr_curr = 195;

    ASSERT_TRUE(backend.init());
    std::string output;
    {
        StdoutCapture capture;
        ASSERT_TRUE(backend.enable(make_default_config()));
        output = capture.read_all();
    }

    backend.shutdown();
    reset_ctl_server_state_for_test();

    EXPECT_NE(output.find("\"rmssi\":-72"), std::string::npos) << output;
    EXPECT_NE(output.find("\"sinr\":127"), std::string::npos) << output;
}

TEST(V4l2ExynosBackendTest, StabilizesPiPsEccAndClearsStaleRtPlusOnNewRadioText) {
    reset_ctl_server_state_for_test();
    FakeV4l2ExynosBackend backend;

    std::string output;
    {
        StdoutCapture capture;
        constexpr uint16_t kPi = 0x7714;
        constexpr int kPty = 10;
        const uint16_t b_0a_base = static_cast<uint16_t>((0 << 12) | (kPty << 5));

        // Confirm PI & PTY with two Group 0A packets first (blocks before PI confirmation
        // do not populate PS/RT/ECC).
        const char *ps = "106.3   ";
        const uint16_t d0 = (static_cast<uint16_t>(ps[0]) << 8) | static_cast<uint16_t>(ps[1]);
        feed_group(&backend, kPi, b_0a_base, 0, d0);
        feed_group(&backend, kPi, b_0a_base, 0, d0);

        // First complete 4-segment PS cycle ("106.3   ") records PS candidate #1.
        for (int seg = 0; seg < 4; ++seg) {
            const uint16_t d = (static_cast<uint16_t>(ps[seg * 2]) << 8)
                    | static_cast<uint16_t>(ps[seg * 2 + 1]);
            feed_group(&backend, kPi, static_cast<uint16_t>(b_0a_base | seg), 0, d);
        }

        // Second identical 4-segment PS cycle confirms PS ("106.3").
        for (int seg = 0; seg < 4; ++seg) {
            const uint16_t d = (static_cast<uint16_t>(ps[seg * 2]) << 8)
                    | static_cast<uint16_t>(ps[seg * 2 + 1]);
            feed_group(&backend, kPi, static_cast<uint16_t>(b_0a_base | seg), 0, d);
        }

        // Group 2A RadioText: "Artist - Title\r" across segments 0..3, with trailing \r in seg 4.
        const char *rt1 = "Artist - Title\r \r\r\r\r";
        const uint16_t b_2a_base = static_cast<uint16_t>((2 << 12) | (kPty << 5));
        for (int seg = 0; seg < 5; ++seg) {
            const uint16_t c = (static_cast<uint16_t>(rt1[seg * 4]) << 8)
                    | static_cast<uint16_t>(rt1[seg * 4 + 1]);
            const uint16_t d = (static_cast<uint16_t>(rt1[seg * 4 + 2]) << 8)
                    | static_cast<uint16_t>(rt1[seg * 4 + 3]);
            feed_group(&backend, kPi, static_cast<uint16_t>(b_2a_base | seg), c, d);
        }

        // Group 3A announcing RT+ (AID 0x4BD7) on Group 11A (11 << 1 | 0 = 0x16).
        const uint16_t b_3a = static_cast<uint16_t>((3 << 12) | (kPty << 5) | (11 << 1));
        feed_group(&backend, kPi, b_3a, 0x0000, 0x4BD7);

        // Group 11A carrying RT+ tags:
        // tag1 = ITEM.ARTIST (4), start = 0, len = 6 (encoded len-1 = 5)
        // tag2 = ITEM.TITLE (1), start = 9, len = 5 (encoded len-1 = 4)
        const int type1 = 4, start1 = 0, len1_m1 = 5;
        const int type2 = 1, start2 = 9, len2_m1 = 4;
        const uint16_t b_11a = static_cast<uint16_t>((11 << 12) | (kPty << 5) | ((type1 >> 3) & 0x07));
        const uint16_t c_11a = static_cast<uint16_t>(
                ((type1 & 0x07) << 13) | (start1 << 7) | (len1_m1 << 1) | ((type2 >> 5) & 0x01)
        );
        const uint16_t d_11a = static_cast<uint16_t>(
                ((type2 & 0x1F) << 11) | (start2 << 5) | (len2_m1 & 0x1F)
        );
        feed_group(&backend, kPi, b_11a, c_11a, d_11a);

        // New Group 2A RadioText ("News Bulletin\r  ") must clear stale RT+ slices.
        const char *rt2 = "News Bulletin\r  ";
        for (int seg = 0; seg < 4; ++seg) {
            const uint16_t c = (static_cast<uint16_t>(rt2[seg * 4]) << 8)
                    | static_cast<uint16_t>(rt2[seg * 4 + 1]);
            const uint16_t d = (static_cast<uint16_t>(rt2[seg * 4 + 2]) << 8)
                    | static_cast<uint16_t>(rt2[seg * 4 + 3]);
            feed_group(&backend, kPi, static_cast<uint16_t>(b_2a_base | seg), c, d);
        }

        output = capture.read_all();
    }

    reset_ctl_server_state_for_test();

    EXPECT_NE(output.find("\"pi\":\"7714\""), std::string::npos) << output;
    EXPECT_NE(output.find("\"ps\":\"106.3\""), std::string::npos) << output;
    EXPECT_NE(output.find("\"rt\":\"Artist - Title\""), std::string::npos) << output;
    EXPECT_NE(output.find("\"title\":\"Title\",\"artist\":\"Artist\""), std::string::npos) << output;
    EXPECT_NE(
            output.find("\"rt\":\"News Bulletin\",\"rt_plus\":{\"title\":\"\",\"artist\":\"\",\"album\":\"\"}"),
            std::string::npos
    ) << output;
}

TEST(V4l2ExynosBackendTest, SetFrequencyDoesNotBlockOnRdsReadAndDiscardsStaleBlocks) {
    class BlockingRdsBackend : public FakeV4l2ExynosBackend {
    public:
        void arm_rds() {
            {
                std::lock_guard<std::mutex> lock(rds_gate_mu_);
                armed_ = true;
            }
            rds_gate_cv_.notify_all();
        }

        void wait_until_read_entered() {
            std::unique_lock<std::mutex> lock(rds_gate_mu_);
            ASSERT_TRUE(rds_gate_cv_.wait_for(
                    lock,
                    std::chrono::milliseconds(1500),
                    [this]() { return read_entered_; }
            ));
        }

        void release_read() {
            {
                std::lock_guard<std::mutex> lock(rds_gate_mu_);
                release_read_ = true;
            }
            rds_gate_cv_.notify_all();
        }

    protected:
        int poll_rds_fd(int, int) override {
            std::lock_guard<std::mutex> lock(rds_gate_mu_);
            return (armed_ && !served_packet_) ? 1 : 0;
        }

        ssize_t read_rds_fd(int, void *buf, size_t count) override {
            std::unique_lock<std::mutex> lock(rds_gate_mu_);
            if (served_packet_) {
                return 0;
            }
            read_entered_ = true;
            rds_gate_cv_.notify_all();
            rds_gate_cv_.wait_for(
                    lock,
                    std::chrono::milliseconds(2000),
                    [this]() { return release_read_; }
            );
            served_packet_ = true;

            // Two 0A groups carrying PI 0x7714 (would normally lock PI immediately).
            constexpr uint16_t kPi = 0x7714;
            constexpr uint16_t kGrp[4] = {kPi, 0x0000, 0x0000, 0x0000};
            const size_t total = 8 * kS610RdsBlockSize;
            if (count < total) {
                return 0;
            }
            auto *out = static_cast<uint8_t *>(buf);
            for (size_t i = 0; i < 8; ++i) {
                const uint16_t word = kGrp[i % 4];
                out[i * 3 + 0] = static_cast<uint8_t>(word & 0xFF);
                out[i * 3 + 1] = static_cast<uint8_t>((word >> 8) & 0xFF);
                out[i * 3 + 2] = static_cast<uint8_t>(i % 4);
            }
            return static_cast<ssize_t>(total);
        }

    private:
        std::mutex rds_gate_mu_;
        std::condition_variable rds_gate_cv_;
        bool armed_ = false;
        bool read_entered_ = false;
        bool release_read_ = false;
        bool served_packet_ = false;
    };

    reset_ctl_server_state_for_test();
    std::string output;
    {
        StdoutCapture capture;
        BlockingRdsBackend backend;
        ASSERT_TRUE(backend.init());
        ASSERT_TRUE(backend.enable(make_default_config()));
        backend.arm_rds();

        backend.wait_until_read_entered();

        const auto start = std::chrono::steady_clock::now();
        EXPECT_TRUE(backend.set_frequency(103200));
        const auto elapsed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now() - start
        ).count();
        EXPECT_LT(elapsed_ms, 250);

        backend.release_read();
        usleep(50000);
        ASSERT_TRUE(backend.disable());
        output = capture.read_all();
    }

    reset_ctl_server_state_for_test();
    EXPECT_EQ(output.find("\"pi\":\"7714\""), std::string::npos) << output;
}

class ScanFakeBackend : public FakeV4l2ExynosBackend {
public:
    struct SeekStep {
        int ret = -1;
        int err = EAGAIN;
        uint32_t freq_khz = 0;
        bool block_until_cancelled = false;
    };

    std::vector<SeekStep> seek_steps;
    int fail_set_freq_call_index = -1;  // 1-based across VIDIOC_S_FREQUENCY calls
    int fail_get_freq_call_index = -1;  // 1-based across VIDIOC_G_FREQUENCY calls
    uint32_t get_freq_override_on_restore_khz = 0;

    void wait_for_seek_count(int target) {
        std::unique_lock<std::mutex> lock(scan_mu_);
        scan_cv_.wait_for(lock, std::chrono::milliseconds(2000), [&]() {
            return seek_calls_ >= target;
        });
    }

protected:
    int v4l2_ioctl(int fd, unsigned long request, void *arg) override {
        if (request == VIDIOC_S_CTRL) {
            auto *ctrl = static_cast<struct v4l2_control *>(arg);
            if (ctrl->id == kS610CidSeekCancel) {
                std::lock_guard<std::mutex> lock(scan_mu_);
                seek_cancelled_ = true;
                scan_cv_.notify_all();
            }
            return FakeV4l2ExynosBackend::v4l2_ioctl(fd, request, arg);
        }

        if (request == VIDIOC_S_FREQUENCY) {
            int idx = 0;
            {
                std::lock_guard<std::mutex> lock(scan_mu_);
                idx = ++set_freq_calls_;
            }
            if (fail_set_freq_call_index == idx) {
                errno = EIO;
                return -1;
            }
            return FakeV4l2ExynosBackend::v4l2_ioctl(fd, request, arg);
        }

        if (request == VIDIOC_G_FREQUENCY) {
            int idx = 0;
            {
                std::lock_guard<std::mutex> lock(scan_mu_);
                idx = ++get_freq_calls_;
            }
            if (fail_get_freq_call_index == idx) {
                errno = EIO;
                return -1;
            }
            const int rc = FakeV4l2ExynosBackend::v4l2_ioctl(fd, request, arg);
            if (rc == 0 && get_freq_override_on_restore_khz > 0 && idx > 1) {
                auto *freq = static_cast<struct v4l2_frequency *>(arg);
                freq->frequency = get_freq_override_on_restore_khz * kS610FreqMultiplier;
            }
            return rc;
        }

        if (request == VIDIOC_S_HW_FREQ_SEEK) {
            SeekStep step;
            {
                std::unique_lock<std::mutex> lock(scan_mu_);
                const size_t step_idx = static_cast<size_t>(seek_calls_++);
                scan_cv_.notify_all();
                if (step_idx < seek_steps.size()) {
                    step = seek_steps[step_idx];
                }
                if (step.block_until_cancelled) {
                    scan_cv_.wait_for(lock, std::chrono::milliseconds(2000), [&]() {
                        return seek_cancelled_;
                    });
                    errno = ECANCELED;
                    return -1;
                }
            }
            if (step.ret == 0) {
                seek_target_khz = step.freq_khz;
                return FakeV4l2ExynosBackend::v4l2_ioctl(fd, request, arg);
            }
            errno = step.err;
            return -1;
        }

        return FakeV4l2ExynosBackend::v4l2_ioctl(fd, request, arg);
    }

private:
    std::mutex scan_mu_;
    std::condition_variable scan_cv_;
    int set_freq_calls_ = 0;
    int get_freq_calls_ = 0;
    int seek_calls_ = 0;
    bool seek_cancelled_ = false;
};

TEST(V4l2ExynosBackendTest, SearchStartTuneFailureEmitsSearchFailedWithoutSearchDone) {
    reset_ctl_server_state_for_test();
    std::string output;
    {
        StdoutCapture capture;
        ScanFakeBackend backend;
        ASSERT_TRUE(backend.init());
        ASSERT_TRUE(backend.enable(make_default_config()));
        // enable() already called VIDIOC_S_FREQUENCY once; fail the lower-edge tune (call #2).
        backend.fail_set_freq_call_index = 2;
        ASSERT_TRUE(backend.search());
        usleep(100000);
        ASSERT_TRUE(backend.disable());
        output = capture.read_all();
    }
    reset_ctl_server_state_for_test();
    EXPECT_NE(output.find("\"type\":\"search_failed\""), std::string::npos) << output;
    EXPECT_EQ(output.find("\"type\":\"search_done\""), std::string::npos) << output;
}

TEST(V4l2ExynosBackendTest, SearchSeekEioAfterStationsEmitsSearchFailedWithoutSearchDone) {
    reset_ctl_server_state_for_test();
    std::string output;
    {
        StdoutCapture capture;
        ScanFakeBackend backend;
        backend.seek_steps = {
                {0, 0, 90500, false},
                {-1, EIO, 0, false},
        };
        ASSERT_TRUE(backend.init());
        ASSERT_TRUE(backend.enable(make_default_config()));
        ASSERT_TRUE(backend.search());
        usleep(100000);
        ASSERT_TRUE(backend.disable());
        output = capture.read_all();
    }
    reset_ctl_server_state_for_test();
    EXPECT_NE(output.find("\"type\":\"search_failed\""), std::string::npos) << output;
    EXPECT_EQ(output.find("\"type\":\"search_done\""), std::string::npos) << output;
}

TEST(V4l2ExynosBackendTest, SearchFrequencyReadbackFailureEmitsSearchFailed) {
    reset_ctl_server_state_for_test();
    std::string output;
    {
        StdoutCapture capture;
        ScanFakeBackend backend;
        backend.seek_steps = {{0, 0, 90500, false}};
        // enable() and the lower-edge tune in search() each call VIDIOC_G_FREQUENCY once; fail the scan step readback (call #3).
        backend.fail_get_freq_call_index = 3;
        ASSERT_TRUE(backend.init());
        ASSERT_TRUE(backend.enable(make_default_config()));
        ASSERT_TRUE(backend.search());
        usleep(100000);
        ASSERT_TRUE(backend.disable());
        output = capture.read_all();
    }
    reset_ctl_server_state_for_test();
    EXPECT_NE(output.find("\"type\":\"search_failed\""), std::string::npos) << output;
    EXPECT_EQ(output.find("\"type\":\"search_done\""), std::string::npos) << output;
}

TEST(V4l2ExynosBackendTest, SearchRestoreFailureEmitsSearchFailedAndPublishesActualFrequency) {
    reset_ctl_server_state_for_test();
    std::string output;
    {
        StdoutCapture capture;
        ScanFakeBackend backend;
        backend.seek_steps = {
                {0, 0, 90500, false},
                {-1, EAGAIN, 0, false},
        };
        // #1: enable(), #2: lower-edge tune, #3: restore saved_khz -> fail.
        backend.fail_set_freq_call_index = 3;
        ASSERT_TRUE(backend.init());
        ASSERT_TRUE(backend.enable(make_default_config()));
        ASSERT_TRUE(backend.search());
        usleep(100000);
        ASSERT_TRUE(backend.disable());
        output = capture.read_all();
    }
    reset_ctl_server_state_for_test();
    EXPECT_NE(output.find("\"frequency\":90500"), std::string::npos) << output;
    EXPECT_NE(output.find("\"type\":\"search_failed\""), std::string::npos) << output;
    EXPECT_EQ(output.find("\"type\":\"search_done\""), std::string::npos) << output;
}

TEST(V4l2ExynosBackendTest, SearchCancelDoesNotEmitSearchDoneOrSearchFailed) {
    reset_ctl_server_state_for_test();
    std::string output;
    {
        StdoutCapture capture;
        ScanFakeBackend backend;
        backend.seek_steps = {{-1, 0, 0, true}};
        ASSERT_TRUE(backend.init());
        ASSERT_TRUE(backend.enable(make_default_config()));
        ASSERT_TRUE(backend.search());
        backend.wait_for_seek_count(1);
        ASSERT_TRUE(backend.cancel_search());
        ASSERT_TRUE(backend.disable());
        output = capture.read_all();
    }
    reset_ctl_server_state_for_test();
    EXPECT_EQ(output.find("\"type\":\"search_done\""), std::string::npos) << output;
    EXPECT_EQ(output.find("\"type\":\"search_failed\""), std::string::npos) << output;
}

TEST(V4l2ExynosBackendTest, SearchZeroStationEndOfBandEmitsEmptySearchDone) {
    reset_ctl_server_state_for_test();
    std::string output;
    {
        StdoutCapture capture;
        ScanFakeBackend backend;
        backend.seek_steps = {{-1, EAGAIN, 0, false}};
        ASSERT_TRUE(backend.init());
        ASSERT_TRUE(backend.enable(make_default_config()));
        ASSERT_TRUE(backend.search());
        usleep(100000);
        ASSERT_TRUE(backend.disable());
        output = capture.read_all();
    }
    reset_ctl_server_state_for_test();
    EXPECT_NE(output.find("\"type\":\"search_done\",\"stations\":[]"), std::string::npos) << output;
    EXPECT_EQ(output.find("\"type\":\"search_failed\""), std::string::npos) << output;
}

TEST(V4l2ExynosBackendTest, RdsWorkerShutdownInterruptsSignalableBlockingRead) {
    class SignalableBlockingRdsBackend final : public FakeV4l2ExynosBackend {
    public:
        SignalableBlockingRdsBackend() {
            if (pipe(block_pipe_) == 0) {
                pipe_ready_ = true;
            }
        }
        ~SignalableBlockingRdsBackend() override {
            if (pipe_ready_) {
                close(block_pipe_[0]);
                close(block_pipe_[1]);
            }
        }

        void arm() {
            armed_.store(true);
        }

        void wait_until_read_entered() {
            std::unique_lock<std::mutex> lock(mu_);
            ASSERT_TRUE(cv_.wait_for(
                    lock,
                    std::chrono::milliseconds(1000),
                    [this]() { return read_entered_; }
            ));
        }

        bool saw_eintr() const {
            std::lock_guard<std::mutex> lock(mu_);
            return saw_eintr_;
        }

    protected:
        int poll_rds_fd(int /*fd*/, int /*timeout_ms*/) override {
            return (pipe_ready_ && armed_.load()) ? 1 : 0;
        }

        ssize_t read_rds_fd(int /*fd*/, void * /*buf*/, size_t /*count*/) override {
            {
                std::lock_guard<std::mutex> lock(mu_);
                read_entered_ = true;
                cv_.notify_all();
            }
            char dummy = 0;
            const ssize_t n = ::read(block_pipe_[0], &dummy, 1);
            if (n < 0 && errno == EINTR) {
                std::lock_guard<std::mutex> lock(mu_);
                saw_eintr_ = true;
            }
            return n;
        }

    private:
        mutable std::mutex mu_;
        std::condition_variable cv_;
        std::atomic<bool> armed_{false};
        int block_pipe_[2] = {-1, -1};
        bool pipe_ready_ = false;
        bool read_entered_ = false;
        bool saw_eintr_ = false;
    };

    reset_ctl_server_state_for_test();
    SignalableBlockingRdsBackend backend;
    ASSERT_TRUE(backend.init());
    ASSERT_TRUE(backend.enable(make_default_config()));
    backend.arm();
    backend.wait_until_read_entered();

    const auto start = std::chrono::steady_clock::now();
    EXPECT_TRUE(backend.disable());
    const auto elapsed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - start
    ).count();

    EXPECT_TRUE(backend.saw_eintr());
    EXPECT_LT(elapsed_ms, 500);
    reset_ctl_server_state_for_test();
}

TEST(V4l2ExynosBackendTest, RdsWorkerShutdownBoundsUninterruptibleReadAndBlocksReopenUntilStopped) {
    class UninterruptibleRdsBackend final : public FakeV4l2ExynosBackend {
    public:
        void arm() {
            armed_.store(true);
        }

        void disarm() {
            armed_.store(false);
        }

        void wait_until_read_entered() {
            std::unique_lock<std::mutex> lock(mu_);
            ASSERT_TRUE(cv_.wait_for(
                    lock,
                    std::chrono::milliseconds(1000),
                    [this]() { return read_entered_; }
            ));
        }

        void release_read_and_wait_exit() {
            std::unique_lock<std::mutex> lock(mu_);
            release_read_ = true;
            cv_.notify_all();
            ASSERT_TRUE(cv_.wait_for(
                    lock,
                    std::chrono::milliseconds(1000),
                    [this]() { return read_exited_; }
            ));
        }

    protected:
        int poll_rds_fd(int /*fd*/, int /*timeout_ms*/) override {
            return armed_.load() ? 1 : 0;
        }

        ssize_t read_rds_fd(int /*fd*/, void * /*buf*/, size_t /*count*/) override {
            std::unique_lock<std::mutex> lock(mu_);
            read_entered_ = true;
            cv_.notify_all();
            cv_.wait(lock, [this]() { return release_read_; });
            read_exited_ = true;
            cv_.notify_all();
            return 0;
        }

    private:
        std::mutex mu_;
        std::condition_variable cv_;
        std::atomic<bool> armed_{false};
        bool read_entered_ = false;
        bool release_read_ = false;
        bool read_exited_ = false;
    };

    reset_ctl_server_state_for_test();
    UninterruptibleRdsBackend backend;
    ASSERT_TRUE(backend.init());
    ASSERT_TRUE(backend.enable(make_default_config()));
    backend.arm();
    backend.wait_until_read_entered();

    const auto start = std::chrono::steady_clock::now();
    EXPECT_FALSE(backend.disable());
    const auto elapsed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - start
    ).count();
    EXPECT_GE(elapsed_ms, 900);
    EXPECT_LT(elapsed_ms, 2500);

    // While the detached worker is still stuck in read_rds_fd(), reopening must fail.
    backend.disarm();
    EXPECT_FALSE(backend.enable(make_default_config()));

    backend.release_read_and_wait_exit();
    usleep(50000);

    EXPECT_TRUE(backend.enable(make_default_config()));
    EXPECT_TRUE(backend.disable());
    reset_ctl_server_state_for_test();
}

TEST(V4l2ExynosBackendTest, JapanPresetClampsTo90000AndWrapsWithinDriverBand) {
    reset_ctl_server_state_for_test();
    std::string output;
    {
        StdoutCapture capture;
        FakeV4l2ExynosBackend backend;
        ASSERT_TRUE(backend.init());

        StartupConfig config = make_default_config();
        config.region = StartupRegion::kJapan;
        config.frequency_khz = 93000;
        ASSERT_TRUE(backend.enable(config));

        EXPECT_EQ(backend.raw_frequency_units(), 90000u * kS610FreqMultiplier);

        uint32_t jumped = 0;
        ASSERT_TRUE(backend.jump(1, &jumped));
        EXPECT_EQ(jumped, 76000u);

        ASSERT_TRUE(backend.jump(-1, &jumped));
        EXPECT_EQ(jumped, 90000u);

        ASSERT_TRUE(backend.disable());
        output = capture.read_all();
    }
    reset_ctl_server_state_for_test();

    EXPECT_NE(output.find("\"frequency\":90000"), std::string::npos) << output;
    EXPECT_EQ(output.find("\"frequency\":93000"), std::string::npos) << output;
    EXPECT_EQ(output.find("\"frequency\":95000"), std::string::npos) << output;
}

TEST(V4l2ExynosBackendTest, TuneReadsBackDriverClampedFrequency) {
    class ClampingDriverBackend final : public FakeV4l2ExynosBackend {
    protected:
        int v4l2_ioctl(int fd, unsigned long request, void *arg) override {
            const int ret = FakeV4l2ExynosBackend::v4l2_ioctl(fd, request, arg);
            if (ret == 0 && request == VIDIOC_S_FREQUENCY) {
                struct v4l2_frequency readback = {};
                readback.tuner = 0;
                readback.type = V4L2_TUNER_RADIO;
                readback.frequency = 88100u * kS610FreqMultiplier;
                FakeV4l2ExynosBackend::v4l2_ioctl(fd, VIDIOC_S_FREQUENCY, &readback);
            }
            return ret;
        }
    };

    reset_ctl_server_state_for_test();
    std::string output;
    {
        StdoutCapture capture;
        ClampingDriverBackend backend;
        ASSERT_TRUE(backend.init());
        ASSERT_TRUE(backend.enable(make_default_config()));
        ASSERT_TRUE(backend.set_frequency(88050));
        ASSERT_TRUE(backend.disable());
        output = capture.read_all();
    }
    reset_ctl_server_state_for_test();

    EXPECT_NE(output.find("\"frequency\":88100"), std::string::npos) << output;
    EXPECT_EQ(output.find("\"frequency\":88050"), std::string::npos) << output;
}

TEST(V4l2ExynosBackendTest, SetRegionJapanRetunesOutOfDriverBand) {
    reset_ctl_server_state_for_test();
    std::string output;
    {
        StdoutCapture capture;
        FakeV4l2ExynosBackend backend;
        ASSERT_TRUE(backend.init());

        StartupConfig config = make_default_config();
        config.region = StartupRegion::kJapanWide;
        config.frequency_khz = 93000;
        ASSERT_TRUE(backend.enable(config));
        EXPECT_EQ(backend.raw_frequency_units(), 93000u * kS610FreqMultiplier);

        ASSERT_TRUE(backend.set_region(StartupRegion::kJapan));
        EXPECT_EQ(backend.raw_frequency_units(), 90000u * kS610FreqMultiplier);

        ASSERT_TRUE(backend.disable());
        output = capture.read_all();
    }
    reset_ctl_server_state_for_test();

    EXPECT_NE(output.find("\"frequency\":90000"), std::string::npos) << output;
}

}  // namespace
