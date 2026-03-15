#include "backend.h"

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

    bool enable() override {
        if (!set_status(fm2_backend_enable())) {
            return false;
        }

        return set_status(fm2_backend_wait_enabled(2000));
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

    bool set_rds(bool enabled) override {
        return set_status(fm2_backend_set_rds(enabled));
    }

    bool set_stereo(bool enabled) override {
        return set_status(fm2_backend_set_stereo(enabled));
    }

    bool set_antenna(int antenna) override {
        return set_status(fm2_backend_set_antenna(antenna));
    }

    bool set_region(int region) override {
        return set_status(fm2_backend_set_region_app_value(region));
    }

    bool set_spacing(int spacing) override {
        return set_status(fm2_backend_set_spacing_app_value(spacing));
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
        return last_error_;
    }

private:
    bool set_status(bool ok) {
        last_error_ = ok
                ? ""
                : (fm2_backend_last_error()[0] ? fm2_backend_last_error() : kErrFailed);
        return ok;
    }

    const char *last_error_ = "";
};

}  // namespace

Backend *create_hal_backend() {
    static HalBackend backend;
    return &backend;
}
