#pragma once

#include <chrono>
#include <mutex>
#include <string>

#include "../../backend.h"
#include "hal_helium_types.h"

class HalHeliumBackend final : public Backend {
public:
    HalHeliumBackend();
    ~HalHeliumBackend() override;

    HalHeliumBackend(const HalHeliumBackend &) = delete;
    HalHeliumBackend &operator=(const HalHeliumBackend &) = delete;
    HalHeliumBackend(HalHeliumBackend &&) = delete;
    HalHeliumBackend &operator=(HalHeliumBackend &&) = delete;

    BackendKind kind() const override {
        return BackendKind::kHelium;
    }

    bool init() override;
    bool enable(const StartupConfig &config) override;
    bool disable() override;
    bool set_frequency(uint32_t frequency_khz) override;
    bool jump(int direction, uint32_t *new_frequency) override;
    bool seek(int direction) override;
    bool set_power_mode(bool low_power) override;
    bool set_stereo(bool enabled) override;
    bool set_antenna(int antenna) override;
    bool set_region(StartupRegion region) override;
    bool set_spacing(int spacing_khz) override;
    bool search() override;
    bool cancel_search() override;
    bool set_auto_af(bool enabled) override;
    bool set_soft_mute(bool enabled) override;
    bool set_notch_filter(int mode) override;
    bool set_slimbus(bool enabled) override;
    bool request_signal_metric() override;
    void shutdown() override;
    const char *last_error() const override {
        return last_error_.c_str();
    }

    bool shutdown_with_timeout(int timeout_ms);
    HeliumRuntimeState &state() {
        return state_;
    }

    std::mutex &state_lock_for_test() {
        return state_lock_;
    }
    void reset_for_test(
            fm_interface_t *vendor,
            bool initialized,
            bool enabled,
            bool receiver_cleanup_needed
    );

    // Vendor C callback entrypoints exposed for tests; they route to active_instance_.
    static void enabled_cb();
    static void disabled_cb();
    static void tune_cb(int freq);
    static void fm_get_station_param_cb(int value, int status);

protected:
    const char *log_prefix() const override {
        return "hel";
    }

private:
    bool configure_startup(const StartupConfig &config);
    bool enable_receiver();
    bool wait_enabled(int timeout_ms);
    bool rollback_enable();
    bool set_status(bool ok);

    bool vendor_set(int id, int value, const char *message);
    bool vendor_get(int id, int *value, const char *message);
    bool apply_signal_threshold();
    bool apply_raw_rds_group_mask();
    bool apply_processed_rds_config(bool auto_af, int rds_standard);
    bool apply_runtime_config();
    bool apply_post_enable_config();
    bool log_snapshot(const char *reason);

    HeliumMetricRequestResult request_metric_internal(int ctrl_id, HeliumMetricType type, const char *name);
    HeliumMetricRequestResult request_sinr();
    HeliumMetricRequestResult request_rmssi();

    void set_error_locked(const char *message);
    void clear_error_locked();
    void reset_search_result_locked();
    void reset_scan_locked();
    void reset_rds_dedup_locked();
    void apply_region_profile_locked(StartupRegion region);
    void log_rt_plus_slices_locked();
    void evaluate_rt_plus_slices_locked(RdsRtPlusSlices *out);

    void on_enabled();
    void on_disabled();
    void on_tune(int freq);
    void on_seek_complete(int freq);
    void on_scan_next();
    void on_search_list(uint16_t *raw);
    void on_stereo_status(bool stereo);
    void on_rds_avail_status(bool rds_available);
    void on_af_update(uint16_t *raw);
    void on_rt_update(char *rt);
    void on_ps_update(char *ps);
    void on_oda_update();
    void on_rt_plus_update(char *payload);
    void on_ert_update(char *payload);
    void on_ext_country_code(char *payload);
    void on_station_param(int value, int status);
    void on_enable_slimbus(int status);

    static void seek_complete_cb(int freq);
    static void scan_next_cb();
    static void search_list_cb(uint16_t *raw);
    static void stereo_status_cb(bool stereo);
    static void rds_avail_status_cb(bool rds_available);
    static void af_update_cb(uint16_t *raw);
    static void rt_update_cb(char *rt);
    static void ps_update_cb(char *ps);
    static void oda_update_cb();
    static void rt_plus_update_cb(char *payload);
    static void ert_update_cb(char *payload);
    static void rds_grp_cntrs_rsp_cb(char *payload);
    static void rds_grp_cntrs_ext_rsp_cb(char *payload);
    static void fm_peek_rsp_cb(char *payload);
    static void fm_ssbi_peek_rsp_cb(char *payload);
    static void fm_agc_gain_rsp_cb(char *payload);
    static void fm_ch_det_th_rsp_cb(char *payload);
    static void ext_country_code_cb(char *payload);
    static void thread_evt_cb(unsigned int evt);
    static void fm_get_sig_thres_cb(int value, int status);
    static void fm_get_ch_det_thr_cb(int value, int status);
    static void fm_def_data_read_cb(int value, int status);
    static void fm_get_blend_cb(int value, int status);
    static void fm_set_ch_det_thr_cb(int status);
    static void fm_def_data_write_cb(int status);
    static void fm_set_blend_cb(int status);
    static void fm_get_station_debug_param_cb(int value, int status);
    static void enable_slimbus_cb(int status);
    static void enable_softmute_cb(int status);

    static HalHeliumBackend *active_instance_;
    static const fm_hal_callbacks_t kCallbacks;

    HeliumRuntimeState state_{};
    std::string last_error_;
    bool sinr_supported_ = true;
};
