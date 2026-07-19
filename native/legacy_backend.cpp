#include "backend.h"
#include "region_profile.h"

#include "legacy/fm_wrap.h"
#include "legacy/fm_ctl.h"

namespace {

constexpr const char *kErrFailed = "ERR_FAILED";
constexpr const char *kErrInvalidAntenna = "ERR_UNV_ANT";
constexpr const char *kErrCantSetRegion = "ERR_CNS_REG";

channel_space_t map_spacing_to_vendor(int spacing_khz) {
    switch (spacing_khz) {
        case 50:
            return FM_RX_SPACE_50KHZ;
        case 200:
            return FM_RX_SPACE_200KHZ;
        case 100:
        default:
            return FM_RX_SPACE_100KHZ;
    }
}

class LegacyBackend final : public Backend {
public:
    BackendKind kind() const override {
        return BackendKind::kLegacy;
    }

    bool init() override {
        return set_status(fm_command_open() == FM_CMD_SUCCESS, kErrFailed);
    }

    bool enable(const StartupConfig &config) override {
        const RegionProfile &profile = get_region_profile(config.region);
        fm_config_data cfg_data = {
                .emphasis = static_cast<emphasis_t>(profile.emphasis),
                .spacing = map_spacing_to_vendor(config.spacing_khz),
                .antenna = static_cast<uint8>(config.antenna),
        };

        if (!set_status(fm_command_prepare(&cfg_data) == FM_CMD_SUCCESS, kErrFailed)) {
            return rollback_enable();
        }

        // Driver rejects RDS_ON if the band is configured first. Keep RDS
        // setup before fm_receiver_set_band(), then tune and unmute last.
        if (!set_status(fm_command_setup_rds(static_cast<rds_system_t>(profile.rds_standard)) == FM_CMD_SUCCESS, kErrFailed)) {
            return rollback_enable();
        }

        if (!set_status(
                fm_receiver_set_band(static_cast<radio_band_t>(profile.legacy_band)) == TRUE,
                kErrCantSetRegion
        ) || !set_stereo(config.stereo)) {
            return rollback_enable();
        }

        if (!set_auto_af(config.auto_af) || !set_frequency(config.frequency_khz)) {
            return rollback_enable();
        }

        if (!set_status(fm_command_set_mute_mode(FM_RX_NO_MUTE) == FM_CMD_SUCCESS, kErrFailed)) {
            return rollback_enable();
        }
        return true;
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

    bool set_stereo(bool enabled) override {
        return set_status(
                fm_command_set_stereo_mode(enabled ? FM_RX_STEREO : FM_RX_MONO) == FM_CMD_SUCCESS,
                kErrFailed
        );
    }

    bool set_antenna(int antenna) override {
        return set_status(fm_receiver_set_antenna(static_cast<uint8>(antenna)) == TRUE, kErrInvalidAntenna);
    }

    bool set_region(StartupRegion region) override {
        const RegionProfile &profile = get_region_profile(region);
        if (!set_status(
                fm_receiver_set_band(static_cast<radio_band_t>(profile.legacy_band)) == TRUE,
                kErrCantSetRegion
        )) {
            return false;
        }
        if (!set_status(
                fm_receiver_set_emphasis(static_cast<emphasis_t>(profile.emphasis)) == TRUE,
                kErrFailed
        )) {
            return false;
        }
        if (!set_status(
                fm_receiver_set_rds_system(static_cast<rds_system_t>(profile.rds_standard)) == TRUE,
                kErrFailed
        )) {
            return false;
        }

        const uint32_t current_frequency = fm_command_get_tuned_frequency();

        if (current_frequency == 0) {
            return true;
        }

        const uint32_t target_frequency = clamp_frequency_to_region(profile, current_frequency);

        return target_frequency == current_frequency || set_frequency(target_frequency);
    }

    bool set_spacing(int spacing_khz) override {
        return set_status(fm_receiver_set_spacing(map_spacing_to_vendor(spacing_khz)) == TRUE, kErrFailed);
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
    bool rollback_enable() {
        const char *error = last_error_;
        if (fm_command_disable() != FM_CMD_SUCCESS) {
            fm_receiver_set_state(OFF);
            fm_receiver_close();
        }
        last_error_ = error;
        return false;
    }

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
