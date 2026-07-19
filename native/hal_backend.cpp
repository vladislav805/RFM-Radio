#include "backend.h"

#include <string>

#include "hal/fm2_backend.h"

namespace {

constexpr const char *kErrFailed = "ERR_FAILED";

class HalBackend final : public Backend {
public:
    BackendKind kind() const override {
        return BackendKind::kHal;
    }

    bool init() override {
        return set_status(fm2_backend_init());
    }

    bool enable(const StartupConfig &config) override {
        if (!set_status(fm2_backend_configure_startup(config))) {
            return false;
        }

        if (!set_status(fm2_backend_enable())) {
            return rollback_enable();
        }

        if (!set_status(fm2_backend_set_frequency(config.frequency_khz))) {
            return rollback_enable();
        }
        return true;
    }

    bool disable() override {
        return set_status(fm2_backend_disable());
    }

    bool set_frequency(uint32_t frequency_khz) override {
        return set_status(fm2_backend_set_frequency(frequency_khz));
    }

    bool jump(int direction, uint32_t *new_frequency) override {
        return set_status(fm2_backend_jump(direction, new_frequency));
    }

    bool seek(int direction) override {
        return set_status(fm2_backend_seek(direction));
    }

    bool set_power_mode(bool low_power) override {
        return set_status(fm2_backend_set_power_mode(low_power));
    }

    bool set_stereo(bool enabled) override {
        return set_status(fm2_backend_set_stereo(enabled));
    }

    bool set_antenna(int antenna) override {
        return set_status(fm2_backend_set_antenna(antenna));
    }

    bool set_region(StartupRegion region) override {
        return set_status(fm2_backend_set_region(region));
    }

    bool set_spacing(int spacing_khz) override {
        return set_status(fm2_backend_set_spacing_khz(spacing_khz));
    }

    bool search() override {
        return set_status(fm2_backend_search());
    }

    bool cancel_search() override {
        return set_status(fm2_backend_cancel_search());
    }

    bool set_auto_af(bool enabled) override {
        return set_status(fm2_backend_set_auto_af(enabled));
    }

    bool set_slimbus(bool enabled) override {
        return set_status(fm2_backend_set_slimbus(enabled));
    }

    const char *last_error() const override {
        return last_error_.c_str();
    }

private:
    bool rollback_enable() {
        const std::string error = last_error_;
        fm2_backend_disable();
        last_error_ = error;
        return false;
    }

    bool set_status(bool ok) {
        last_error_ = ok
                ? ""
                : (fm2_backend_last_error()[0] ? fm2_backend_last_error() : kErrFailed);
        return ok;
    }

    std::string last_error_;
};

}  // namespace

Backend *create_hal_backend() {
    static HalBackend backend;
    return &backend;
}
