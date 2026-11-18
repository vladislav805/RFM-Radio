#include <gtest/gtest.h>

#include <cerrno>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <deque>
#include <mutex>
#include <string>
#include <thread>

#include "backend/qcom_fm_controls.h"
#include "backend/v4l2_qcom/v4l2_qcom_backend.h"

namespace {

constexpr uint8_t kEvtRadioReady = 0;
constexpr uint8_t kEvtRadioDisabled = 18;

// Fake V4L2 device harness overriding file descriptors, ioctls, and sleeps.
class FakeV4l2QcomBackend : public V4l2QcomBackend {
public:
    bool emit_ready_on_rx = true;
    bool emit_disabled_on_off = true;
    bool fail_set_tuner = false;

    void push_event(uint8_t event_id) {
        std::lock_guard<std::mutex> lock(mu_);
        events_.push_back(event_id);
        cv_.notify_all();
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
        return fd_radio_ >= 0;
    }

protected:
    int open_device_fd(const char *) override {
        std::lock_guard<std::mutex> lock(mu_);
        device_open_ = true;
        active_fd_ = next_fd_++;
        ++open_calls_;
        events_.clear();
        return active_fd_;
    }

    void close_device_fd(int fd) override {
        std::lock_guard<std::mutex> lock(mu_);
        if (device_open_ && fd == active_fd_) {
            device_open_ = false;
            active_fd_ = -1;
            ++close_calls_;
            cv_.notify_all();
        }
    }

    void sleep_us(unsigned int usec) override {
        if (usec == 10000) {
            std::this_thread::sleep_for(std::chrono::microseconds(100));
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
            ctrl->value = 0;
            return 0;
        }

        if (request == VIDIOC_S_TUNER) {
            std::lock_guard<std::mutex> lock(mu_);
            if (!device_open_ || fd != active_fd_) {
                errno = EBADF;
                return -1;
            }
            if (fail_set_tuner) {
                errno = EINVAL;
                return -1;
            }
            return 0;
        }

        if (request == VIDIOC_G_TUNER) {
            std::lock_guard<std::mutex> lock(mu_);
            if (!device_open_ || fd != active_fd_) {
                errno = EBADF;
                return -1;
            }
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
            freq->frequency = khz_to_freq(last_frequency_khz_);
            return 0;
        }

        if (request == VIDIOC_DQBUF) {
            auto *buf = static_cast<struct v4l2_buffer *>(arg);
            std::unique_lock<std::mutex> lock(mu_);
            cv_.wait(lock, [&]() {
                return !device_open_ || fd != active_fd_ || !events_.empty();
            });
            if (!device_open_ || fd != active_fd_) {
                errno = EBADF;
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

} // namespace
