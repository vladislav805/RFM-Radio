#pragma once

#include "../../backend.h"
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
    // Opens the V4L2 character device file descriptor in non-blocking mode.
    bool open_device(const char *path = "/dev/radio0") {
        if (fd_radio_ >= 0) {
            return true;
        }
        fd_radio_ = open(path, O_RDWR | O_NONBLOCK);
        if (fd_radio_ < 0) {
            const int saved_errno = errno;
            log("open", "path=%s failed, errno=%d (%s)", path, saved_errno, std::strerror(saved_errno));
            return false;
        }
        log("open", "path=%s fd=%d", path, fd_radio_);
        return true;
    }

    // Closes the open V4L2 character device file descriptor.
    void close_device() {
        if (fd_radio_ >= 0) {
            close(fd_radio_);
            fd_radio_ = -1;
        }
    }

    // Writes a V4L2 control value using VIDIOC_S_CTRL.
    bool set_v4l2_ctrl(uint32_t id, int32_t value) {
        if (fd_radio_ < 0) {
            return false;
        }
        struct v4l2_control control = {};
        control.id = id;
        control.value = value;
        const int err = ioctl(fd_radio_, VIDIOC_S_CTRL, &control);
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
        if (fd_radio_ < 0 || value == nullptr) {
            return false;
        }
        struct v4l2_control control = {};
        control.id = id;
        const int err = ioctl(fd_radio_, VIDIOC_G_CTRL, &control);
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

    int fd_radio_ = -1;
    const char *last_error_ = "";
};
