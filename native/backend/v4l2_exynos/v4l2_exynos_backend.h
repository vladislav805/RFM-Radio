#pragma once

#include "../v4l2_base/v4l2_backend_base.h"
#include "s610_fm_controls.h"
#include "../../ctl_server.h"
#include "../../rds_parser.h"
#include "../../region_profile.h"

#include <atomic>
#include <mutex>
#include <sys/types.h>
#include <thread>

// Samsung Exynos S610 V4L2 implementation of Backend (/dev/radio0).
class V4l2ExynosBackend : public V4l2BackendBase {
public:
    V4l2ExynosBackend();
    ~V4l2ExynosBackend() override;

    BackendKind kind() const override {
        return BackendKind::kV4l2Exynos;
    }

    bool init() override;
    bool enable(const StartupConfig &config) override;
    bool disable() override;
    bool set_frequency(uint32_t frequency_khz) override;
    bool jump(int direction, uint32_t *new_frequency) override;
    bool seek(int direction) override;
    bool set_power_mode(bool low_power) override;
    bool set_stereo(bool enabled) override;
    bool set_volume(int volume) override;
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
        return last_error_;
    }

    // Host test seam for feeding raw 3-byte S610 RDS blocks directly into the decoder.
    void feed_rds_block_for_test(uint16_t data, uint8_t blk_type, uint8_t errors) {
        process_rds_block(data, blk_type, errors);
    }

protected:
    const char *log_prefix() const override {
        return "exynos";
    }

    // Virtual file-descriptor seams for host unit tests.
    virtual int poll_rds_fd(int fd, int timeout_ms);
    virtual ssize_t read_rds_fd(int fd, void *buf, size_t count);
    virtual int get_fd_flags(int fd);
    virtual int set_fd_flags(int fd, int flags);

private:
    struct RdsDecoderState {
        uint16_t blocks[4] = {};
        bool have_block[4] = {};
        uint16_t pi = 0;
        uint16_t pi_candidate = 0;
        int pi_hits = 0;
        bool has_pi = false;
        int pty = -1;
        int pty_candidate = -1;
        int pty_hits = 0;
        unsigned char ps_raw[8] = {};
        uint8_t ps_mask = 0;
        char ps_candidate[64] = {};
        int ps_hits = 0;
        char ps_utf8[64] = {};
        unsigned char rt_raw[64] = {};
        unsigned char rt_committed_raw[64] = {};
        int rt_committed_raw_len = 0;
        uint16_t rt_mask = 0;
        int rt_end_seg = -1;
        int rt_ab_flag = -1;
        char rt_utf8[256] = {};
        int rtplus_group = -1;
        bool rtplus_group_is_b = false;
        bool rtplus_ert = false;
        RdsRtPlusTag rtplus_tags[2] = {};
        bool rtplus_tags_valid = false;
        RdsRtPlusSlices rtplus_slices = {};
        char country[4] = {};
        char country_candidate[4] = {};
        int country_hits = 0;
    };

    bool ensure_open();
    bool apply_region(StartupRegion region);
    bool apply_spacing(int spacing_khz);
    bool apply_mute(bool mute);
    bool apply_rds(bool enable);
    void drain_rds_fifo();
    bool tune_frequency_khz(uint32_t frequency_khz);
    bool read_frequency_khz(uint32_t *frequency_khz);
    bool read_stereo_state(bool *is_stereo);
    bool perform_hw_seek(bool upward, bool wrap_around, int *out_errno = nullptr);
    bool is_scan_or_seek_active() const {
        return search_active_.load() || seek_active_.load();
    }
    void finish_scan_or_seek_volume_sync(std::atomic<bool> *active_flag);
    void stop_search_thread();
    void seek_worker(int direction);
    void search_worker();
    void start_rds_thread();
    bool stop_rds_thread();
    void rds_worker();
    void reset_rds_state();
    void process_rds_block(uint16_t data, uint8_t blk_type, uint8_t errors);
    void flush_rds_group();
    void apply_rt_plus_slices(radio_state_patch_t *patch, bool *dirty);
    void clear_rt_plus_slices(radio_state_patch_t *patch, bool *dirty);
    void publish_state(int frequency_khz, int stereo, int rmssi, int sinr);

    std::mutex ioctl_mutex_;
    std::mutex rds_mutex_;
    std::mutex volume_mutex_;
    RdsDecoderState rds_;
    std::atomic<uint64_t> rds_generation_{0};

    std::thread rds_thread_;
    std::atomic<bool> rds_stop_{false};
    std::atomic<bool> rds_thread_running_{false};

    std::thread search_thread_;
    std::atomic<bool> search_active_{false};
    std::atomic<bool> search_cancel_{false};
    std::atomic<bool> seek_active_{false};

    std::atomic<bool> enabled_{false};
    StartupRegion region_ = StartupRegion::kEurope;
    int spacing_khz_ = 100;
    uint32_t current_frequency_khz_ = 87500;
    int desired_volume_ = kS610DefaultVolume;
    int applied_volume_ = -1;
    std::atomic<bool> rds_enabled_{false};
};

Backend *create_v4l2_exynos_backend();
