#include <gtest/gtest.h>

#include <algorithm>
#include <cerrno>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <deque>
#include <map>
#include <mutex>
#include <optional>
#include <string>
#include <thread>
#include <unistd.h>
#include <vector>

#include "backend/qcom_fm_controls.h"
#include "backend/v4l2_qcom/v4l2_qcom_backend.h"
#include "ctl_server.h"

namespace {

constexpr uint8_t kEvtRadioReady = 0;
constexpr uint8_t kEvtTuneSucc = 1;
constexpr uint8_t kEvtRadioDisabled = 18;

// Captures JSON messages written to STDOUT_FILENO during a test scope.
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

std::vector<std::string> split_lines(const std::string &text) {
    std::vector<std::string> lines;
    size_t start = 0;
    while (start < text.size()) {
        size_t end = text.find('\n', start);
        if (end == std::string::npos) {
            lines.push_back(text.substr(start));
            break;
        }
        lines.push_back(text.substr(start, end - start));
        start = end + 1;
    }
    return lines;
}

// Fake V4L2 device harness overriding file descriptors, ioctls, and sleeps.
class FakeV4l2QcomBackend : public V4l2QcomBackend {
public:
    bool emit_ready_on_rx = true;
    bool emit_disabled_on_off = true;
    bool fail_open = false;
    bool fail_set_tuner = false;
    bool emit_disabled_on_tuner_fail = false;
    bool ignore_event_interrupt = false;
    bool fail_get_sinr = false;
    int sinr_value = 12;
    int rmssi_signal = -70;
    std::optional<uint32_t> reported_frequency_khz;

    ~FakeV4l2QcomBackend() override {
        {
            std::lock_guard<std::mutex> lock(mu_);
            ignore_event_interrupt = false;
            dqbuf_interrupted_ = true;
        }
        cv_.notify_all();
        shutdown();
    }

    void push_event(uint8_t event_id) {
        std::lock_guard<std::mutex> lock(mu_);
        events_.push_back(event_id);
        cv_.notify_all();
    }

    void clear_metric_settle() {
        clear_metric_settle_for_test();
    }

    int open_calls() const {
        std::lock_guard<std::mutex> lock(mu_);
        return open_calls_;
    }

    int close_calls() const {
        std::lock_guard<std::mutex> lock(mu_);
        return close_calls_;
    }

    uint32_t last_frequency_khz() const {
        std::lock_guard<std::mutex> lock(mu_);
        return last_frequency_khz_;
    }

    bool event_thread_started() const {
        return is_event_thread_started();
    }

    bool device_fd_open() const {
        return fd_radio_.load() >= 0;
    }

protected:
    int open_device_fd(const char *) override {
        std::lock_guard<std::mutex> lock(mu_);
        if (fail_open) {
            errno = ENODEV;
            return -1;
        }
        device_open_ = true;
        active_fd_ = next_fd_++;
        ++open_calls_;
        events_.clear();
        dqbuf_interrupted_ = false;
        dqbuf_waiting_ = false;
        return active_fd_;
    }

    void close_device_fd(int fd) override {
        std::lock_guard<std::mutex> lock(mu_);
        if (device_open_ && fd == active_fd_) {
            device_open_ = false;
            active_fd_ = -1;
            ++close_calls_;
        }
    }

    void sleep_us(unsigned int usec) override {
        if (usec == 10000) {
            std::this_thread::sleep_for(std::chrono::microseconds(100));
        }
    }

    void interrupt_event_read() override {
        V4l2QcomBackend::interrupt_event_read();
        std::lock_guard<std::mutex> lock(mu_);
        if (!ignore_event_interrupt) {
            dqbuf_interrupted_ = true;
            cv_.notify_all();
        }
    }

    int v4l2_ioctl(int fd, unsigned long request, void *arg) override {
        if (request == VIDIOC_QUERYCAP) {
            std::lock_guard<std::mutex> lock(mu_);
            if (!device_open_ || fd != active_fd_) {
                errno = EBADF;
                return -1;
            }
            auto *cap = static_cast<struct v4l2_capability *>(arg);
            std::memset(cap, 0, sizeof(*cap));
            cap->version = 1;
            return 0;
        }

        if (request == VIDIOC_S_CTRL) {
            auto *ctrl = static_cast<struct v4l2_control *>(arg);
            std::lock_guard<std::mutex> lock(mu_);
            if (!device_open_ || fd != active_fd_) {
                errno = EBADF;
                return -1;
            }
            if (ctrl->id == static_cast<uint32_t>(kV4l2CtrlState)) {
                if (ctrl->value == RX && emit_ready_on_rx) {
                    events_.push_back(kEvtRadioReady);
                    cv_.notify_all();
                } else if (ctrl->value == OFF && emit_disabled_on_off) {
                    events_.push_back(kEvtRadioDisabled);
                    cv_.notify_all();
                }
            }
            return 0;
        }

        if (request == VIDIOC_G_CTRL) {
            auto *ctrl = static_cast<struct v4l2_control *>(arg);
            std::lock_guard<std::mutex> lock(mu_);
            if (!device_open_ || fd != active_fd_) {
                errno = EBADF;
                return -1;
            }
            if (ctrl->id == static_cast<uint32_t>(kV4l2CtrlIrisSinr)) {
                if (fail_get_sinr) {
                    errno = EINVAL;
                    return -1;
                }
                ctrl->value = sinr_value;
                return 0;
            }
            ctrl->value = 0;
            return 0;
        }

        if (request == VIDIOC_S_TUNER) {
            std::unique_lock<std::mutex> lock(mu_);
            if (!device_open_ || fd != active_fd_) {
                errno = EBADF;
                return -1;
            }
            if (fail_set_tuner) {
                if (is_event_thread_started()) {
                    cv_.wait(lock, [&]() {
                        return dqbuf_waiting_ || !events_.empty();
                    });
                }
                if (emit_disabled_on_tuner_fail) {
                    events_.push_back(kEvtRadioDisabled);
                    cv_.notify_all();
                }
                errno = EINVAL;
                return -1;
            }
            return 0;
        }

        if (request == VIDIOC_G_TUNER) {
            auto *tuner = static_cast<struct v4l2_tuner *>(arg);
            std::lock_guard<std::mutex> lock(mu_);
            if (!device_open_ || fd != active_fd_) {
                errno = EBADF;
                return -1;
            }
            tuner->signal = rmssi_signal;
            return 0;
        }

        if (request == VIDIOC_S_FREQUENCY) {
            auto *freq = static_cast<struct v4l2_frequency *>(arg);
            std::lock_guard<std::mutex> lock(mu_);
            if (!device_open_ || fd != active_fd_) {
                errno = EBADF;
                return -1;
            }
            last_frequency_khz_ = freq_to_khz(freq->frequency);
            return 0;
        }

        if (request == VIDIOC_G_FREQUENCY) {
            auto *freq = static_cast<struct v4l2_frequency *>(arg);
            std::lock_guard<std::mutex> lock(mu_);
            if (!device_open_ || fd != active_fd_) {
                errno = EBADF;
                return -1;
            }
            freq->frequency = khz_to_freq(reported_frequency_khz.value_or(last_frequency_khz_));
            return 0;
        }

        if (request == VIDIOC_S_HW_FREQ_SEEK) {
            std::lock_guard<std::mutex> lock(mu_);
            if (!device_open_ || fd != active_fd_) {
                errno = EBADF;
                return -1;
            }
            return 0;
        }

        if (request == VIDIOC_DQBUF) {
            auto *buf = static_cast<struct v4l2_buffer *>(arg);
            std::unique_lock<std::mutex> lock(mu_);
            if (!device_open_ || fd != active_fd_) {
                errno = EBADF;
                return -1;
            }
            dqbuf_waiting_ = true;
            cv_.notify_all();
            cv_.wait(lock, [&]() {
                return dqbuf_interrupted_ || !events_.empty();
            });
            dqbuf_waiting_ = false;
            if (dqbuf_interrupted_ && events_.empty()) {
                dqbuf_interrupted_ = false;
                errno = EINTR;
                return -1;
            }
            auto *out = reinterpret_cast<uint8_t *>(buf->m.userptr);
            uint32_t copied = 0;
            while (!events_.empty() && copied < buf->length) {
                out[copied++] = events_.front();
                events_.pop_front();
            }
            buf->bytesused = copied;
            return 0;
        }

        errno = ENOTTY;
        return -1;
    }

private:
    mutable std::mutex mu_;
    std::condition_variable cv_;
    std::deque<uint8_t> events_;
    bool device_open_ = false;
    bool dqbuf_waiting_ = false;
    bool dqbuf_interrupted_ = false;
    int next_fd_ = 100;
    int active_fd_ = -1;
    int open_calls_ = 0;
    int close_calls_ = 0;
    uint32_t last_frequency_khz_ = 0;
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

TEST(V4l2QcomLifecycleTest, JumpReturnsTargetWhileDriverStillReportsPreviousFrequency) {
    FakeV4l2QcomBackend backend;
    auto cfg = make_default_config();
    cfg.frequency_khz = 88000;
    backend.reported_frequency_khz = cfg.frequency_khz;
    ASSERT_TRUE(backend.init());
    ASSERT_TRUE(backend.enable(cfg));

    uint32_t reply_khz = 0;
    ASSERT_TRUE(backend.jump(-1, &reply_khz));
    EXPECT_EQ(87900u, backend.last_frequency_khz());
    EXPECT_EQ(87900u, reply_khz);
}

TEST(V4l2QcomLifecycleTest, JumpRequiresAValidStartingFrequency) {
    FakeV4l2QcomBackend backend;
    ASSERT_TRUE(backend.init());

    uint32_t reply_khz = 0;
    EXPECT_FALSE(backend.jump(-1, &reply_khz));
    EXPECT_EQ(0u, backend.last_frequency_khz());
    EXPECT_EQ(0u, reply_khz);
}

TEST(V4l2QcomLifecycleTest, RepeatedEnableRejectsAndPreservesActiveSession) {
    FakeV4l2QcomBackend backend;
    const StartupConfig cfg = make_default_config();

    ASSERT_TRUE(backend.init());
    ASSERT_TRUE(backend.enable(cfg));
    EXPECT_TRUE(backend.event_thread_started());
    EXPECT_EQ(backend.open_calls(), 1);

    EXPECT_FALSE(backend.enable(cfg));
    EXPECT_STREQ(backend.last_error(), "ERR_ALREADY_ENABLED");
    EXPECT_EQ(backend.open_calls(), 1);
    EXPECT_TRUE(backend.event_thread_started());
    EXPECT_TRUE(backend.device_fd_open());

    EXPECT_TRUE(backend.set_frequency(93300));
    EXPECT_EQ(backend.last_frequency_khz(), 93300u);
    EXPECT_TRUE(backend.disable());

    backend.shutdown();
    EXPECT_FALSE(backend.event_thread_started());
    EXPECT_FALSE(backend.device_fd_open());
}

TEST(V4l2QcomLifecycleTest, DisableThenEnableReopensDeviceAndRestartsEventThread) {
    FakeV4l2QcomBackend backend;
    const StartupConfig cfg = make_default_config();

    ASSERT_TRUE(backend.init());
    ASSERT_TRUE(backend.enable(cfg));
    ASSERT_TRUE(backend.disable());

    ASSERT_TRUE(backend.enable(cfg));
    EXPECT_EQ(backend.open_calls(), 2);
    EXPECT_TRUE(backend.event_thread_started());
    EXPECT_TRUE(backend.device_fd_open());

    backend.shutdown();
    EXPECT_FALSE(backend.event_thread_started());
    EXPECT_FALSE(backend.device_fd_open());
}

TEST(V4l2QcomLifecycleTest, DisableWithoutEventThenEnableStopsLeftoverThread) {
    FakeV4l2QcomBackend backend;
    backend.emit_disabled_on_off = false;
    const StartupConfig cfg = make_default_config();

    ASSERT_TRUE(backend.init());
    ASSERT_TRUE(backend.enable(cfg));
    ASSERT_TRUE(backend.disable());

    ASSERT_TRUE(backend.enable(cfg));
    EXPECT_EQ(backend.open_calls(), 2);
    EXPECT_TRUE(backend.event_thread_started());

    backend.shutdown();
    EXPECT_FALSE(backend.event_thread_started());
    EXPECT_FALSE(backend.device_fd_open());
}

TEST(V4l2QcomLifecycleTest, RollbackOnMidSetupFailureStopsAndJoinsEventThread) {
    FakeV4l2QcomBackend backend;
    backend.fail_set_tuner = true;
    const StartupConfig cfg = make_default_config();

    ASSERT_TRUE(backend.init());
    EXPECT_FALSE(backend.enable(cfg));
    EXPECT_STREQ(backend.last_error(), "ERR_CNS_REG");
    EXPECT_EQ(backend.close_calls(), 1);
    EXPECT_FALSE(backend.event_thread_started());
    EXPECT_FALSE(backend.device_fd_open());

    backend.fail_set_tuner = false;
    EXPECT_TRUE(backend.enable(cfg));
    EXPECT_EQ(backend.open_calls(), 2);
    EXPECT_TRUE(backend.event_thread_started());

    backend.shutdown();
    EXPECT_FALSE(backend.event_thread_started());
    EXPECT_FALSE(backend.device_fd_open());
}

TEST(V4l2QcomLifecycleTest, RollbackWakesWaitingEventIoctlWhenOffAndCloseDoNot) {
    FakeV4l2QcomBackend backend;
    backend.fail_set_tuner = true;
    backend.emit_disabled_on_off = false;
    const StartupConfig cfg = make_default_config();

    ASSERT_TRUE(backend.init());
    EXPECT_FALSE(backend.enable(cfg));
    EXPECT_STREQ(backend.last_error(), "ERR_CNS_REG");
    EXPECT_EQ(backend.close_calls(), 1);
    EXPECT_FALSE(backend.event_thread_started());
    EXPECT_FALSE(backend.device_fd_open());

    backend.fail_set_tuner = false;
    ASSERT_TRUE(backend.enable(cfg));
    EXPECT_EQ(backend.open_calls(), 2);
    EXPECT_TRUE(backend.event_thread_started());
    EXPECT_TRUE(backend.set_frequency(93300));
    EXPECT_EQ(backend.last_frequency_khz(), 93300u);

    backend.shutdown();
    EXPECT_EQ(backend.close_calls(), 2);
    EXPECT_FALSE(backend.event_thread_started());
    EXPECT_FALSE(backend.device_fd_open());
}

TEST(V4l2QcomLifecycleTest, RadioDisabledRacingWithRollbackClosesOnceAndRecovers) {
    FakeV4l2QcomBackend backend;
    backend.fail_set_tuner = true;
    backend.emit_disabled_on_off = false;
    backend.emit_disabled_on_tuner_fail = true;
    const StartupConfig cfg = make_default_config();

    ASSERT_TRUE(backend.init());
    EXPECT_FALSE(backend.enable(cfg));
    EXPECT_STREQ(backend.last_error(), "ERR_CNS_REG");
    EXPECT_EQ(backend.close_calls(), 1);
    EXPECT_FALSE(backend.event_thread_started());
    EXPECT_FALSE(backend.device_fd_open());

    backend.fail_set_tuner = false;
    backend.emit_disabled_on_tuner_fail = false;
    ASSERT_TRUE(backend.enable(cfg));
    EXPECT_EQ(backend.open_calls(), 2);
    EXPECT_TRUE(backend.event_thread_started());
    EXPECT_TRUE(backend.set_frequency(98100));
    EXPECT_EQ(backend.last_frequency_khz(), 98100u);

    backend.shutdown();
    EXPECT_EQ(backend.close_calls(), 2);
    EXPECT_FALSE(backend.event_thread_started());
    EXPECT_FALSE(backend.device_fd_open());
}

TEST(V4l2QcomLifecycleTest, UnresponsiveEventIoctlBoundsRollbackAndBlocksReopenUntilStopped) {
    FakeV4l2QcomBackend backend;
    backend.fail_set_tuner = true;
    backend.emit_disabled_on_off = false;
    backend.ignore_event_interrupt = true;
    const StartupConfig cfg = make_default_config();

    ASSERT_TRUE(backend.init());
    EXPECT_FALSE(backend.enable(cfg));
    EXPECT_STREQ(backend.last_error(), "ERR_CNS_REG");
    EXPECT_TRUE(backend.event_thread_started());
    EXPECT_EQ(backend.close_calls(), 0);

    // Must not reopen the receiver while the previous event thread is still active.
    backend.fail_set_tuner = false;
    EXPECT_FALSE(backend.enable(cfg));
    EXPECT_STREQ(backend.last_error(), "ERR_FAILED");
    EXPECT_EQ(backend.open_calls(), 1);
    EXPECT_EQ(backend.close_calls(), 0);

    // Once the stuck event read becomes interruptible again, enable cleans up and recovers.
    backend.ignore_event_interrupt = false;
    ASSERT_TRUE(backend.enable(cfg));
    EXPECT_EQ(backend.close_calls(), 1);
    EXPECT_EQ(backend.open_calls(), 2);
    EXPECT_TRUE(backend.event_thread_started());
    EXPECT_TRUE(backend.set_frequency(95500));
    EXPECT_EQ(backend.last_frequency_khz(), 95500u);

    backend.shutdown();
    EXPECT_EQ(backend.close_calls(), 2);
    EXPECT_FALSE(backend.event_thread_started());
    EXPECT_FALSE(backend.device_fd_open());
}

TEST(V4l2QcomLifecycleTest, ReadyTimeoutStopsEventThreadAndCanRecover) {
    FakeV4l2QcomBackend backend;
    backend.emit_ready_on_rx = false;
    const StartupConfig cfg = make_default_config();

    ASSERT_TRUE(backend.init());
    EXPECT_FALSE(backend.enable(cfg));
    EXPECT_STREQ(backend.last_error(), "ERR_FAILED");
    EXPECT_FALSE(backend.event_thread_started());
    EXPECT_FALSE(backend.device_fd_open());

    backend.emit_ready_on_rx = true;
    EXPECT_TRUE(backend.enable(cfg));
    EXPECT_EQ(backend.open_calls(), 2);
    EXPECT_TRUE(backend.event_thread_started());

    backend.shutdown();
    EXPECT_FALSE(backend.event_thread_started());
    EXPECT_FALSE(backend.device_fd_open());
}

TEST(V4l2QcomLifecycleTest, MetricFallbackClearsSinrAndRecoveryClearsRmssi) {
    reset_ctl_server_state_for_test();
    FakeV4l2QcomBackend backend;
    const StartupConfig cfg = make_default_config();

    ASSERT_TRUE(backend.init());
    ASSERT_TRUE(backend.enable(cfg));
    backend.clear_metric_settle();

    std::string output;
    {
        StdoutCapture capture;
        backend.push_event(kEvtTuneSucc);
        for (int attempt = 0; attempt < 100; ++attempt) {
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }

        backend.fail_get_sinr = true;
        backend.rmssi_signal = -70;
        ASSERT_TRUE(backend.request_signal_metric());

        backend.rmssi_signal = -68;
        ASSERT_TRUE(backend.request_signal_metric());

        // Simulate SINR recovery on tune without frequency change -> clears stale RMSSI.
        backend.fail_get_sinr = false;
        backend.sinr_value = 15;
        backend.push_event(kEvtTuneSucc);
        for (int attempt = 0; attempt < 50; ++attempt) {
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
        output = capture.read_all();
    }

    backend.shutdown();
    reset_ctl_server_state_for_test();

    const std::vector<std::string> lines = split_lines(output);
    ASSERT_EQ(lines.size(), 5u) << output;
    EXPECT_EQ(lines[0], "{\"type\":\"state\",\"frequency\":101700,\"rds\":{\"pty\":0,\"af\":[]}}");
    EXPECT_EQ(lines[1], "{\"type\":\"state\",\"sinr\":12}");
    EXPECT_EQ(lines[2], "{\"type\":\"state\",\"rmssi\":-70,\"sinr\":null}");
    EXPECT_EQ(lines[3], "{\"type\":\"state\",\"rmssi\":-68}");
    EXPECT_EQ(lines[4], "{\"type\":\"state\",\"rmssi\":null,\"sinr\":15}");
}

TEST(V4l2QcomLifecycleTest, SkippedPollDuringSettleWindowPreservesSinrSupport) {
    reset_ctl_server_state_for_test();
    FakeV4l2QcomBackend backend;
    const StartupConfig cfg = make_default_config();

    ASSERT_TRUE(backend.init());
    ASSERT_TRUE(backend.enable(cfg));

    // Settle window active immediately after enable() must skip without clearing/fallback.
    backend.fail_get_sinr = true;
    EXPECT_FALSE(backend.request_signal_metric());

    backend.fail_get_sinr = false;
    backend.sinr_value = 14;
    backend.clear_metric_settle();

    std::string output;
    {
        StdoutCapture capture;
        ASSERT_TRUE(backend.request_signal_metric());
        output = capture.read_all();
    }

    backend.shutdown();
    reset_ctl_server_state_for_test();

    EXPECT_EQ(output, "{\"type\":\"state\",\"sinr\":14}\n");
}

TEST(V4l2QcomLifecycleTest, SameFrequencySeekEmitsSeekDoneAndUnblocksSearch) {
    reset_ctl_server_state_for_test();
    FakeV4l2QcomBackend backend;
    const StartupConfig cfg = make_default_config();

    ASSERT_TRUE(backend.init());
    ASSERT_TRUE(backend.enable(cfg));
    backend.clear_metric_settle();

    std::string output;
    {
        StdoutCapture capture;
        // Prime initial cached state at 101700 kHz.
        backend.push_event(kEvtTuneSucc);
        for (int attempt = 0; attempt < 50; ++attempt) {
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }

        ASSERT_TRUE(backend.seek(1));
        // Scan cannot start while seek is in flight.
        EXPECT_FALSE(backend.search());

        // Driver finishes seek back on the SAME frequency (101700 kHz).
        backend.push_event(kEvtTuneSucc);
        for (int attempt = 0; attempt < 50; ++attempt) {
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
        EXPECT_TRUE(backend.search());
        output = capture.read_all();
    }

    backend.shutdown();
    reset_ctl_server_state_for_test();

    const std::vector<std::string> lines = split_lines(output);
    EXPECT_TRUE(std::find(lines.begin(), lines.end(), "{\"type\":\"seek_done\"}") != lines.end())
            << output;
}

TEST(V4l2QcomLifecycleTest, DelayedSetfreqCallbackAfterSeekDoesNotEmitPrematureSeekDone) {
    reset_ctl_server_state_for_test();
    FakeV4l2QcomBackend backend;
    const StartupConfig cfg = make_default_config();

    ASSERT_TRUE(backend.init());
    ASSERT_TRUE(backend.enable(cfg));
    backend.clear_metric_settle();

    // Deliver the initial enable tune event.
    backend.push_event(kEvtTuneSucc);
    for (int attempt = 0; attempt < 50; ++attempt) {
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }

    // Issue setfreq 98100 without delivering its TUNE_SUCC yet, then immediately start seek.
    ASSERT_TRUE(backend.set_frequency(98100));
    ASSERT_TRUE(backend.seek(1));
    EXPECT_FALSE(backend.search());

    std::string early_output;
    {
        StdoutCapture capture;
        // Delayed TUNE_SUCC from the previous setfreq arrives after seek was registered.
        backend.push_event(kEvtTuneSucc);
        for (int attempt = 0; attempt < 50; ++attempt) {
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
        early_output = capture.read_all();
    }

    const std::vector<std::string> early_lines = split_lines(early_output);
    EXPECT_TRUE(std::find(early_lines.begin(), early_lines.end(), "{\"type\":\"seek_done\"}") == early_lines.end())
            << early_output;
    // Scan still cannot start because the real seek has not completed yet.
    EXPECT_FALSE(backend.search());

    std::string seek_output;
    {
        StdoutCapture capture;
        // Now the real seek completes at the same frequency (98100 kHz).
        backend.push_event(kEvtTuneSucc);
        for (int attempt = 0; attempt < 50; ++attempt) {
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
        seek_output = capture.read_all();
    }

    const std::vector<std::string> seek_lines = split_lines(seek_output);
    EXPECT_EQ(std::count(seek_lines.begin(), seek_lines.end(), "{\"type\":\"seek_done\"}"), 1)
            << seek_output;
    // Scan can start only after the matching seek finishes.
    EXPECT_TRUE(backend.search());

    backend.shutdown();
    reset_ctl_server_state_for_test();
}

TEST(V4l2QcomLifecycleTest, HardwareDisabledEventAllowsReEnableAndStopsMetricPolling) {
    FakeV4l2QcomBackend backend;
    const StartupConfig cfg = make_default_config();

    ASSERT_TRUE(backend.init());
    ASSERT_TRUE(backend.enable(cfg));

    backend.push_event(kEvtRadioDisabled);
    for (int attempt = 0; attempt < 200 && backend.close_calls() == 0; ++attempt) {
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    ASSERT_EQ(backend.close_calls(), 1);
    EXPECT_FALSE(backend.device_fd_open());
    EXPECT_FALSE(backend.request_signal_metric());

    EXPECT_TRUE(backend.enable(cfg));
    EXPECT_EQ(backend.open_calls(), 2);
    EXPECT_TRUE(backend.event_thread_started());
    EXPECT_TRUE(backend.device_fd_open());

    backend.shutdown();
    EXPECT_FALSE(backend.event_thread_started());
    EXPECT_FALSE(backend.device_fd_open());
}

TEST(V4l2QcomLifecycleTest, ClassifiesBootstrapModeAcrossAllProbeCombinations) {
    EXPECT_EQ(classify_v4l2_bootstrap_mode("", false), V4l2BootstrapMode::kDirect);
    EXPECT_EQ(classify_v4l2_bootstrap_mode(nullptr, false), V4l2BootstrapMode::kDirect);
    EXPECT_EQ(classify_v4l2_bootstrap_mode("stopped", false), V4l2BootstrapMode::kFirmwareThenDirect);
    EXPECT_EQ(classify_v4l2_bootstrap_mode("", true), V4l2BootstrapMode::kFirmwareThenDirect);
    EXPECT_EQ(classify_v4l2_bootstrap_mode("running", true), V4l2BootstrapMode::kFirmwareThenDirect);
}

TEST(V4l2QcomLifecycleTest, OpenFailureHardFails) {
    FakeV4l2QcomBackend backend;
    backend.fail_open = true;
    const StartupConfig cfg = make_default_config();

    EXPECT_FALSE(backend.init());
    EXPECT_STREQ(backend.last_error(), "ERR_FAILED");
    EXPECT_FALSE(backend.enable(cfg));
    EXPECT_STREQ(backend.last_error(), "ERR_FAILED");
}

} // namespace
