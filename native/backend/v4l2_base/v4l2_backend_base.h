#pragma once

#include "../../backend.h"
#include <atomic>
#include <cerrno>
#include <cstring>
#include <fcntl.h>
#include <linux/videodev2.h>
#include <sys/ioctl.h>
#include <unistd.h>

// Base class for V4L2-based radio backends providing device handle management,
// standard V4L2 control I/O, and V4L2 frequency unit conversions.
class V4l2BackendBase : public Backend {
public:
    V4l2BackendBase() = default;

    ~V4l2BackendBase() override {
        close_device();
    }

    // V4L2 tuner frequency multiplier (1 MHz / 62.5 Hz = 16000).
    static constexpr uint32_t kTuneMultiplier = 16000;

    // Converts a V4L2 tuner frequency unit to kHz.
    static uint32_t freq_to_khz(uint32_t freq) {
        return (freq * 1000) / kTuneMultiplier;
    }

    // Converts a frequency in kHz to V4L2 tuner units.
    static uint32_t khz_to_freq(uint32_t khz) {
        return (khz * kTuneMultiplier) / 1000;
    }

protected:
    // Opens the underlying file descriptor; virtual for host test seams.
    virtual int open_device_fd(const char *path) {
        return open(path, O_RDWR | O_NONBLOCK);
    }

    // Closes the underlying file descriptor; virtual for host test seams.
    virtual void close_device_fd(int fd) {
        close(fd);
    }

    // Performs a V4L2 ioctl on the open file descriptor; virtual for host test seams.
    virtual int v4l2_ioctl(int fd, unsigned long request, void *arg) {
#ifdef __ANDROID__
        return ioctl(fd, static_cast<int>(request), arg);
#else
        return ioctl(fd, request, arg);
#endif
    }

    // Opens the V4L2 character device file descriptor in non-blocking mode.
    bool open_device(const char *path = "/dev/radio0") {
        if (fd_radio_.load() >= 0) {
            return true;
        }
        const int fd = open_device_fd(path);
        if (fd < 0) {
            const int saved_errno = errno;
            log("open", "path=%s failed, errno=%d (%s)", path, saved_errno, std::strerror(saved_errno));
            return false;
        }
        int expected = -1;
        if (!fd_radio_.compare_exchange_strong(expected, fd)) {
            close_device_fd(fd);
            return true;
        }
        log("open", "path=%s fd=%d", path, fd);
        return true;
    }

    // Atomically claims and closes the open V4L2 character device file descriptor.
    void close_device() {
        const int fd = fd_radio_.exchange(-1);
        if (fd >= 0) {
            close_device_fd(fd);
        }
    }

    // Writes a V4L2 control value using VIDIOC_S_CTRL.
    bool set_v4l2_ctrl(uint32_t id, int32_t value) {
        const int fd = fd_radio_.load();
        if (fd < 0) {
            return false;
        }
        struct v4l2_control control = {
                .id = id,
                .value = value,
        };
        const int err = v4l2_ioctl(fd, VIDIOC_S_CTRL, &control);
        if (err < 0) {
            const int saved_errno = errno;
            log("v4l2", "set id=0x%x value=%d failed, ret=%d errno=%d (%s)",
                    id, value, err, saved_errno, std::strerror(saved_errno));
            return false;
        }
        return true;
    }

    // Reads a V4L2 control value using VIDIOC_G_CTRL.
    bool get_v4l2_ctrl(uint32_t id, int32_t *value) {
        const int fd = fd_radio_.load();
        if (fd < 0 || value == nullptr) {
            return false;
        }
        struct v4l2_control control = {
                .id = id,
        };
        const int err = v4l2_ioctl(fd, VIDIOC_G_CTRL, &control);
        if (err < 0) {
            const int saved_errno = errno;
            log("v4l2", "get id=0x%x failed, ret=%d errno=%d (%s)",
                    id, err, saved_errno, std::strerror(saved_errno));
            return false;
        }
        *value = control.value;
        return true;
    }

    // Sets the human-readable last error string and returns the boolean result.
    bool set_status(bool ok, const char *error) {
        last_error_ = ok ? "" : (error != nullptr ? error : "ERR_FAILED");
        return ok;
    }

    std::atomic<int> fd_radio_{-1};
    const char *last_error_ = "";
};
