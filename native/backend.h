#pragma once

#include <stdint.h>

enum class BackendKind {
    kNone = 0,
    kLegacy,
    kHal,
};

class Backend {
public:
    virtual ~Backend() = default;

    virtual BackendKind kind() const = 0;
    virtual bool init() = 0;
    virtual bool enable() = 0;
    virtual bool disable() = 0;
    virtual bool set_frequency(uint32_t frequency_khz) = 0;
    virtual bool jump(int direction, uint32_t *new_frequency) = 0;
    virtual bool seek(int direction) = 0;
    virtual bool set_power_mode(bool low_power) = 0;
    virtual bool set_rds(bool enabled) = 0;
    virtual bool set_stereo(bool enabled) = 0;
    virtual bool set_antenna(int antenna) = 0;
    virtual bool set_region(int region) = 0;
    virtual bool set_spacing(int spacing) = 0;
    virtual bool search() = 0;
    virtual bool cancel_search() = 0;
    virtual bool set_auto_af(bool enabled) = 0;
    virtual bool set_slimbus(bool enabled) = 0;
    virtual const char *last_error() const = 0;
};
