#pragma once

#include "../v4l2_base/v4l2_backend_base.h"
#include "../../rds_parser.h"
#include "v4l2_qcom_types.h"
#include "v4l2_scan_state.h"

#include <atomic>
#include <mutex>
#include <pthread.h>
#include <string>

// Bootstrap transport selection strategy for Qualcomm V4L2 receivers.
enum class V4l2BootstrapMode {
    kDirect,
    kFirmwareThenDirect,
};

// Classifies the V4L2 bootstrap mode from pure probe inputs.
V4l2BootstrapMode classify_v4l2_bootstrap_mode(
        const char *fm_dl_service_state,
        bool has_patch_downloader
);

// Selects the V4L2 bootstrap mode from init.svc.fm_dl and fm_qsoc_patches presence.
V4l2BootstrapMode select_v4l2_bootstrap_mode();

// Qualcomm V4L2 implementation of Backend for Iris/Tavarua receivers.
class V4l2QcomBackend : public V4l2BackendBase {
public:
    V4l2QcomBackend();
    ~V4l2QcomBackend() override;

    BackendKind kind() const override {
        return BackendKind::kV4l2Qcom;
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
    const char *last_error() const override { return last_error_; }

protected:
    const char *log_prefix() const override {
        return "qcom";
    }

    // Sleeps for the requested microseconds; virtual for host test seams.
    virtual void sleep_us(unsigned int usec) {
        usleep(usec);
    }

    // Interrupts an in-flight event read on the event thread; virtual for host test seams.
    virtual void interrupt_event_read();

    // Reports whether an event thread handle is currently joinable.
    bool is_event_thread_started() const {
        return event_thread_started_;
    }

    // Clears the post-tune metric settle deadline for host tests.
    void clear_metric_settle_for_test() {
        metric_resume_at_ms_ = 0;
    }

private:
    bool rollback_enable();

    // Command layer
    fm_cmd_status_t command_open();
    fm_cmd_status_t command_prepare(fm_config_data *config_ptr);
    fm_cmd_status_t command_setup_receiver(fm_config_data *ptr);
    fm_cmd_status_t command_setup_rds(rds_system_t system);
    fm_cmd_status_t command_disable();
    void command_shutdown(bool disable_receiver);
    fm_cmd_status_t command_tune_frequency(uint32_t frequency);
    fm_cmd_status_t command_tune_frequency_by_delta(int16_t direction);
    uint32_t command_get_tuned_frequency();
    bool command_publish_sinr();
    bool command_publish_rmssi();
    bool command_start_seek(int8_t direction, uint8_t dwell_period);
    bool command_start_scan();
    bool command_cancel_scan();
    bool command_search_busy();
    void command_abandon_search();
    fm_cmd_status_t command_set_mute_mode(mute_t mode);
    fm_cmd_status_t command_set_stereo_mode(stereo_t is_stereo);

    // Receiver / V4L2 layer
    bool receiver_open();
    bool receiver_set_state(fm_tuner_state tuner_state);
    bool receiver_set_emphasis(emphasis_t emp_type);
    bool receiver_set_spacing(channel_space_t spacing);
    bool receiver_set_rds_state(bool enable);
    bool receiver_set_band(radio_band_t band);
    bool receiver_set_rds_system(rds_system_t system);
    bool receiver_set_antenna(uint8_t antenna);
    bool receiver_set_tuned_frequency(uint32_t frequency_khz);
    uint32_t receiver_get_tuned_frequency();
    bool receiver_get_rmssi(int *rmssi);
    bool receiver_get_sinr(int *sinr);
    bool receiver_set_mute_mode(mute_t mode);
    bool receiver_toggle_af_jump(uint8_t enable);
    bool receiver_set_power_mode(power_mode_t mode);
    bool receiver_set_stereo_mode(stereo_t mode);
    bool receiver_search_station_seek(search_t mode, int8_t search_dir, uint8_t dwell_period);
    bool receiver_search_station_list(fm_search_list_stations options);
    bool receiver_cancel_search();
    void receiver_close();

    // RDS extraction
    int32_t read_buffer(uint8_t *buf, uint32_t buffer_size, int index);
    bool extract_program_service(fm_rds_storage *storage);
    bool extract_radio_text(fm_rds_storage *storage);
    bool extract_raw_rds();
    bool extract_rt_plus();
    bool extract_ert();
    uint8_t extract_rds_af_list(uint32_t *list);
    uint8_t extract_search_station_list(uint32_t *list);
    void clear_rt_plus_tags();
    void reset_rt_context();
    void log_rt_plus_slices();
    void evaluate_rt_plus_slices(struct RdsRtPlusSlices *out);

    // Hardware detector helpers
    static bool is_smd_transport_layer();
    static bool is_rome_chip();
    void clear_direct_init_marker();

    // Event thread
    static void *event_thread_stub(void *arg);
    void event_thread();
    bool stop_event_thread();

    // Member State
    std::atomic<bool> enabled_{false};
    uint64_t metric_resume_at_ms_ = 0;
    bool sinr_supported_ = true;

    char qsoc_poweron_cmd_[64] = {};
    bool firmware_bootstrap_failed_ = false;
    bool direct_v4l2_transport_ = false;
    V4l2ScanState legacy_scan_;
    std::atomic<bool> power_on_completed_{false};
    pthread_t q_event_thread_ = 0;
    bool event_thread_started_ = false;
    std::atomic<bool> event_thread_running_{false};
    std::atomic<bool> thread_running_{false};

    fm_current_storage fm_storage_{};

    // RT+ state
    bool rt_plus_tags_valid_ = false;
    RdsRtPlusTag rt_plus_tags_[2]{};
    unsigned char rt_raw_text_[65] = {};
    int rt_raw_text_len_ = 0;
};
