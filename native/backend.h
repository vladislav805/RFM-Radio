#pragma once

#include <stdint.h>

#include <mutex>

#include "startup_config.h"
#include "backend_log.h"

enum class BackendKind {
    kNone = 0,
    kV4l2Qcom,
    kHelium,
};

class Backend {
public:
    virtual ~Backend() = default;

    virtual BackendKind kind() const = 0;
    virtual bool init() = 0;
    virtual bool enable(const StartupConfig &config) = 0;
    virtual bool disable() = 0;
    virtual bool set_frequency(uint32_t frequency_khz) = 0;
    virtual bool jump(int direction, uint32_t *new_frequency) = 0;
    virtual bool seek(int direction) = 0;
    virtual bool set_power_mode(bool low_power) = 0;
    virtual bool set_stereo(bool enabled) = 0;
    virtual bool set_antenna(int antenna) = 0;
    virtual bool set_region(StartupRegion region) = 0;
    virtual bool set_spacing(int spacing_khz) = 0;
    virtual bool search() = 0;
    virtual bool cancel_search() = 0;
    virtual bool set_auto_af(bool enabled) = 0;
    virtual bool set_soft_mute(bool enabled) = 0;
    virtual bool set_notch_filter(int mode) = 0;
    virtual bool set_slimbus(bool enabled) = 0;
    virtual bool request_signal_metric() = 0;
    virtual void shutdown() = 0;
    virtual const char *last_error() const = 0;

protected:
    virtual const char *log_prefix() const = 0;

    void log(const char *scope, const char *fmt, ...) const __attribute__((format(printf, 3, 4))) {
        va_list args;
        va_start(args, fmt);
        backend_vlog(log_prefix(), scope, fmt, args);
        va_end(args);
    }

    // Shared mutex protecting backend state across command and callback/event threads.
    mutable std::mutex state_lock_;
};
