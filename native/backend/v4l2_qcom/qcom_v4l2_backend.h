#pragma once

#include "../../../backend.h"
#include "../v4l2_base/v4l2_backend_base.h"
#include "fmcommon.h"
#include "scan_state.h"

#include <pthread.h>

class QcomV4l2Backend final : public V4l2BackendBase {
public:
    QcomV4l2Backend();
    ~QcomV4l2Backend() override;

    BackendKind kind() const override { return BackendKind::kV4l2Qcom; }

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
    bool set_slimbus(bool enabled) override;
    bool request_signal_metric() override;
    void shutdown() override;
    const char *last_error() const override { return last_error_; }

    // Formerly in fm_wrap/fm_ctl
    fm_cmd_status_t fm_command_open();
    fm_cmd_status_t fm_command_prepare(fm_config_data* config_ptr);
    fm_cmd_status_t fm_command_setup_rds(rds_system_t system);
    fm_cmd_status_t fm_command_disable();
    void fm_command_shutdown(bool disable_receiver);
    fm_cmd_status_t fm_command_tune_frequency(uint32_t frequency);
    fm_cmd_status_t fm_command_tune_frequency_by_delta(int16_t direction);
    uint32_t fm_command_get_tuned_frequency();
    bool fm_command_publish_sinr();
    bool fm_command_publish_rmssi();
    bool fm_command_start_seek(int8_t direction, uint8_t dwell_period);
    bool fm_command_start_scan();
    bool fm_command_cancel_scan();
    bool fm_command_search_busy();
    void fm_command_abandon_search();
    fm_cmd_status_t fm_command_set_mute_mode(mute_t mode);
    fm_cmd_status_t fm_command_set_stereo_mode(stereo_t is_stereo);

private:
    bool rollback_enable();

    // from fm_ctl
    bool set_v4l2_ctrl(uint32_t id, int32_t value);
    bool fm_receiver_open();
    bool fm_receiver_set_state(fm_tuner_state tuner_state);
    bool fm_receiver_set_emphasis(emphasis_t emp_type);
    bool fm_receiver_set_spacing(channel_space_t spacing);
    bool fm_receiver_set_rds_state(bool enable);
    bool fm_receiver_set_band(radio_band_t band);
    bool fm_receiver_set_rds_system(rds_system_t system);
    bool fm_receiver_set_antenna(uint8_t antenna);
    bool fm_receiver_set_tuned_frequency(uint32_t frequency_khz);
    uint32_t fm_receiver_get_tuned_frequency();
    bool fm_receiver_get_rmssi(int *rmssi);
    bool fm_receiver_get_sinr(int *sinr);
    bool fm_receiver_set_mute_mode(mute_t mode);
    bool fm_receiver_toggle_af_jump(uint8_t enable);
    bool fm_receiver_set_power_mode(power_mode_t mode);
    bool fm_receiver_set_stereo_mode(stereo_t mode);
    bool fm_receiver_search_station_seek(search_t mode, int8_t search_dir, uint8_t dwell_period);
    bool fm_receiver_search_station_list(fm_search_list_stations options);
    bool fm_receiver_cancel_search();
    void fm_receiver_close();

    // RDS stuff
    int32_t read_data_from_v4l2(uint8_t *buf, uint32_t buffer_size, int index);
    bool extract_program_service(fm_rds_storage* storage);
    bool extract_radio_text(fm_rds_storage* storage);
    bool extract_raw_rds();
    bool extract_rt_plus();
    bool extract_ert();
    uint8_t extract_rds_af_list(uint32_t* list);
    uint8_t extract_search_station_list(uint32_t* list);
    void clear_rt_plus_tags();
    void reset_legacy_rt_context();
    void evaluate_legacy_rt_plus_slices(struct RdsRtPlusSlices *out);
    bool set_rds_data_buffer_size(uint32_t size);

    // Event thread
    static void *fm_event_thread_stub(void *arg);
    void fm_event_thread();

    // Globals converted to members
    bool enabled_ = false;
    uint64_t metric_resume_at_ms_ = 0;
    bool sinr_supported_ = true;

    char *qsoc_poweron_path_ = nullptr;
    bool g_direct_v4l2_transport_ = false;
    V4l2ScanState legacy_scan_;
    pthread_mutex_t legacy_scan_lock_ = PTHREAD_MUTEX_INITIALIZER;
    bool is_toggling_power_ = false;
    pthread_t q_event_thread_;
    volatile bool thread_alive_ = false;
    volatile bool thread_running_ = false;
    volatile bool radio_enabled_ = false;

    fm_current_storage fm_storage_;
    bool is_hw_seek_ = false;
};
