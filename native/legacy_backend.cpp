#include "backend.h"

extern "C" {
#include "legacy/fm_wrap.h"
#include "legacy/fm_ctl.h"
}

namespace {

constexpr const char *kErrFailed = "ERR_FAILED";
constexpr const char *kErrInvalidAntenna = "ERR_UNV_ANT";
constexpr const char *kErrCantSetRegion = "ERR_CNS_REG";

class LegacyBackend final : public Backend {
public:
    BackendKind kind() const override {
        return BackendKind::kLegacy;
    }

    bool init() override {
        return set_status(fm_command_open() == FM_CMD_SUCCESS, kErrFailed);
    }

    bool enable() override {
        fm_config_data cfg_data = {
                .band = FM_RX_US_EUROPE,
                .emphasis = FM_RX_EMP75,
                .spacing = FM_RX_SPACE_100KHZ,
                .rds_system = FM_RX_RDS_SYSTEM,
        };

        if (!set_status(fm_command_prepare(&cfg_data) == FM_CMD_SUCCESS, kErrFailed)) {
            return false;
        }

        return set_status(fm_command_set_mute_mode(FM_RX_NO_MUTE) == FM_CMD_SUCCESS, kErrFailed);
    }

    bool disable() override {
        return set_status(fm_command_disable() == FM_CMD_SUCCESS, kErrFailed);
    }

    bool set_frequency(uint32_t frequency_khz) override {
        return set_status(fm_command_tune_frequency(frequency_khz) == FM_CMD_SUCCESS, kErrFailed);
    }

    bool jump(int direction, uint32_t *new_frequency) override {
        if (!set_status(fm_command_tune_frequency_by_delta(direction >= 0 ? 1 : -1) == FM_CMD_SUCCESS, kErrFailed)) {
            return false;
        }

        if (new_frequency != nullptr) {
            *new_frequency = fm_command_get_tuned_frequency();
        }
        return true;
    }

    bool seek(int direction) override {
        return set_status(fm_receiver_search_station_seek(SEEK, direction >= 0 ? 1 : 0, 7) == TRUE, kErrFailed);
    }

    bool set_power_mode(bool low_power) override {
        return set_status(
                fm_receiver_set_power_mode(low_power ? FM_RX_POWER_MODE_LOW : FM_RX_POWER_MODE_NORMAL) == TRUE,
                kErrFailed
        );
    }

    bool set_rds(bool enabled) override {
        return set_status(
                fm_command_setup_rds(enabled ? FM_RX_RDS_SYSTEM : FM_RX_NO_RDS_SYSTEM) == FM_CMD_SUCCESS,
                kErrFailed
        );
    }

    bool set_stereo(bool enabled) override {
        return set_status(
                fm_command_set_stereo_mode(enabled ? FM_RX_STEREO : FM_RX_MONO) == FM_CMD_SUCCESS,
                kErrFailed
        );
    }

    bool set_antenna(int antenna) override {
        return set_status(fm_receiver_set_antenna(static_cast<uint8>(antenna)) == TRUE, kErrInvalidAntenna);
    }

    bool set_region(int region) override {
        return set_status(fm_receiver_set_band(static_cast<radio_band_t>(region)) == TRUE, kErrCantSetRegion);
    }

    bool set_spacing(int spacing) override {
        return set_status(fm_receiver_set_spacing(static_cast<channel_space_t>(spacing)) == TRUE, kErrFailed);
    }

    bool search() override {
        fm_search_list_stations options = {
                .search_mode = SCAN_FOR_STRONG,
                .search_dir = 0,
                .srch_list_max = 20,
                .program_type = 0,
        };
        return set_status(fm_receiver_search_station_list(options) == TRUE, kErrFailed);
    }

    bool cancel_search() override {
        return set_status(fm_receiver_cancel_search() == TRUE, kErrFailed);
    }

    bool set_auto_af(bool enabled) override {
        return set_status(fm_receiver_toggle_af_jump(enabled ? 1 : 0) == TRUE, kErrFailed);
    }

    bool set_slimbus(bool /*enabled*/) override {
        return set_status(true, nullptr);
    }

    const char *last_error() const override {
        return last_error_;
    }

private:
    bool set_status(bool ok, const char *error) {
        last_error_ = ok ? "" : (error != nullptr ? error : kErrFailed);
        return ok;
    }

    const char *last_error_ = "";
};

}  // namespace

Backend *create_legacy_backend() {
    static LegacyBackend backend;
    return &backend;
}
