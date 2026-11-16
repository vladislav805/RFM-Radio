#pragma once

#include "../../backend.h"
#include <linux/videodev2.h>
#include <sys/ioctl.h>

class V4l2BackendBase : public Backend {
public:
    virtual ~V4l2BackendBase() {
        close_fd();
    }

    bool init() override {
        return false;
    }

protected:
    void close_fd();
    bool set_status(bool ok, const char *error);

    int fd_radio_ = -1;
    const char *last_error_ = "";
};
