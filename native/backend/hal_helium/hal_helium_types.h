#pragma once

#include <pthread.h>
#include <stddef.h>
#include <stdint.h>

#include "../../rds_parser.h"
#include "../../region_profile.h"
#include "../../scan_result.h"
#include "../../startup_config.h"
#include "../qcom_fm_controls.h"

constexpr int kV4l2CtrlIrisFreq        = 0x0098092f;
constexpr int kV4l2CtrlIrisSeek        = 0x00980930;
constexpr int kV4l2CtrlIrisUpperBand   = 0x00980931;
constexpr int kV4l2CtrlIrisLowerBand   = 0x00980932;
constexpr int kV4l2CtrlIrisAudioMode   = 0x00980933;
constexpr int kV4l2CtrlIrisRmssi       = 0x00980934;
constexpr int kV4l2CtrlEnableSlimbus   = 0x00980940;

typedef void (*enb_result_cb)(void);
typedef void (*tune_rsp_cb)(int freq);
typedef void (*seek_rsp_cb)(int freq);
typedef void (*scan_rsp_cb)(void);
typedef void (*srch_list_rsp_cb)(uint16_t *scan_tbl);
typedef void (*stereo_mode_cb)(bool status);
typedef void (*rds_avl_sts_cb)(bool status);
typedef void (*af_list_cb)(uint16_t *af_list);
typedef void (*rt_cb)(char *rt);
typedef void (*ps_cb)(char *ps);
typedef void (*oda_cb)(void);
typedef void (*rt_plus_cb)(char *rt_plus);
typedef void (*ert_cb)(char *ert);
typedef void (*disable_cb)(void);
typedef void (*callback_thread_event)(unsigned int evt);
typedef void (*rds_grp_cntrs_cb)(char *rds_params);
typedef void (*rds_grp_cntrs_ext_cb)(char *rds_params);
typedef void (*fm_peek_cb)(char *peek_rsp);
typedef void (*fm_ssbi_peek_cb)(char *ssbi_peek_rsp);
typedef void (*fm_agc_gain_cb)(char *agc_gain_rsp);
typedef void (*fm_ch_det_th_cb)(char *ch_det_rsp);
typedef void (*fm_ecc_evt_cb)(char *ecc_rsp);
typedef void (*fm_sig_thr_cb)(int val, int status);
typedef void (*fm_get_ch_det_thrs_cb)(int val, int status);
typedef void (*fm_def_data_rd_cb)(int val, int status);
typedef void (*fm_get_blnd_cb)(int val, int status);
typedef void (*fm_set_ch_det_thrs_cb)(int status);
typedef void (*fm_def_data_wrt_cb)(int status);
typedef void (*fm_set_blnd_cb)(int status);
typedef void (*fm_get_stn_prm_cb)(int val, int status);
typedef void (*fm_get_stn_dbg_prm_cb)(int val, int status);
typedef void (*fm_enable_slimbus_cb)(int status);
typedef void (*fm_enable_softmute_cb)(int status);

// Vendor callback table passed to fm_helium.so via hal_init().
// Field order and types must match the binary ABI of fm_helium.so exactly.
typedef struct {
    // Size of this struct in bytes.
    size_t size;

    // Receiver enabled (STATE=RX) completed.
    enb_result_cb enabled_cb;

    // Tune completed; carries tuned frequency in kHz.
    tune_rsp_cb tune_cb;

    // Seek completed (or terminal SEARCH_COMPLETE for scan); carries frequency in kHz.
    seek_rsp_cb seek_cmpl_cb;

    // Next station found during scan; caller reads current frequency via get_fm_ctrl.
    scan_rsp_cb scan_next_cb;

    // Station list search completed; carries relative 50 kHz offsets payload.
    srch_list_rsp_cb srch_list_cb;

    // Audio stereo/mono reception state changed.
    stereo_mode_cb stereo_status_cb;

    // RDS synchronization lock gained or lost.
    rds_avl_sts_cb rds_avail_status_cb;

    // Alternative Frequencies (AF) list updated; carries hci_ev_af_list payload.
    af_list_cb af_list_update_cb;

    // RadioText (RT) payload received.
    rt_cb rt_update_cb;

    // Program Service (PS), PI, and PTY payload received.
    ps_cb ps_update_cb;

    // Open Data Application (ODA) carrier detected (group 3A).
    oda_cb oda_update_cb;

    // RadioText+ (RT+) tag payload received.
    rt_plus_cb rt_plus_update_cb;

    // Enhanced RadioText (eRT, UTF-16) payload received.
    ert_cb ert_update_cb;

    // Receiver disabled (STATE=OFF) completed.
    disable_cb disabled_cb;

    // Standard RDS group error/block counters response.
    rds_grp_cntrs_cb rds_grp_cntrs_rsp_cb;

    // Extended RDS group counters response.
    rds_grp_cntrs_ext_cb rds_grp_cntrs_ext_rsp_cb;

    // RIVA register/memory peek response.
    fm_peek_cb fm_peek_rsp_cb;

    // SSBI register peek response.
    fm_ssbi_peek_cb fm_ssbi_peek_rsp_cb;

    // AGC gain state read response.
    fm_agc_gain_cb fm_agc_gain_rsp_cb;

    // Channel detection threshold read response.
    fm_ch_det_th_cb fm_ch_det_th_rsp_cb;

    // Extended Country Code (ECC, group 1A) payload received.
    fm_ecc_evt_cb ext_country_code_cb;

    // HAL worker thread lifecycle event (started/stopped).
    callback_thread_event thread_evt_cb;

    // Signal threshold getter response.
    fm_sig_thr_cb fm_get_sig_thres_cb;

    // Channel detection threshold getter response.
    fm_get_ch_det_thrs_cb fm_get_ch_det_thr_cb;

    // Default data read response.
    fm_def_data_rd_cb fm_def_data_read_cb;

    // Stereo blend table getter response.
    fm_get_blnd_cb fm_get_blend_cb;

    // Channel detection threshold setter status response.
    fm_set_ch_det_thrs_cb fm_set_ch_det_thr_cb;

    // Default data write status response.
    fm_def_data_wrt_cb fm_def_data_write_cb;

    // Stereo blend table setter status response.
    fm_set_blnd_cb fm_set_blend_cb;

    // Station parameter (RMSSI/SINR) read response.
    fm_get_stn_prm_cb fm_get_station_param_cb;

    // Station debug parameter read response.
    fm_get_stn_dbg_prm_cb fm_get_station_debug_param_cb;

    // Slimbus audio transport toggle status response.
    fm_enable_slimbus_cb enable_slimbus_cb;

    // Soft mute toggle status response.
    fm_enable_softmute_cb enable_softmute_cb;
} fm_hal_callbacks_t;

// Entrypoint interface exported as FM_HELIUM_LIB_INTERFACE by fm_helium.so.
struct fm_interface_t {
    // Registers the callback table and initializes the HAL library.
    int (*hal_init)(const fm_hal_callbacks_t *p_cb);

    // Sets a V4L2/vendor FM control by ID and value.
    int (*set_fm_ctrl)(int opcode, int val);

    // Reads a V4L2/vendor FM control (or triggers an async read) by ID.
    int (*get_fm_ctrl)(int opcode, int *val);
};

// Big-endian 16-bit relative frequency offset in 50 kHz units from band lower edge.
struct hci_ev_rel_freq {
    // High byte of the relative 50 kHz channel offset.
    char rel_freq_msb;

    // Low byte of the relative 50 kHz channel offset.
    char rel_freq_lsb;
};

// Vendor station-list search completion payload (capped at 20 stations).
struct hci_ev_srch_list_compl {
    // Number of valid entries in rel_freq.
    char num_stations_found;

    // Relative 50 kHz channel offsets from the lower band edge.
    struct hci_ev_rel_freq rel_freq[20];
};

// Packed vendor Alternative Frequencies (AF) list payload.
struct hci_ev_af_list {
    // Currently tuned frequency in kHz.
    int tune_freq;

    // Station Program Identification (PI) code.
    short pi_code;

    // Number of AF entries packed into af_list.
    uint8_t af_size;

    // Raw array of AF entries (32-bit kHz values).
    char af_list[200];
} __attribute__((packed));

// Cached audio and transport flags for the Helium receiver.
struct HeliumAudioState {
    // Latest stereo/mono status reported by stereo_status_cb.
    bool stereo = true;

    // Tracks whether mode_stereo has been sent to the HAL successfully.
    bool mode_valid = false;

    // Desired stereo mode (true = stereo, false = mono).
    bool mode_stereo = true;

    // Desired weak-signal soft-mute setting.
    bool soft_mute = true;

    // Latest Slimbus audio transport state.
    bool slimbus_enabled = false;
};

// Sequential full-band scan tracking state.
struct HeliumScanState {
    // True while a sequential scan is actively collecting stations.
    bool active = false;

    // True once scan_next_cb frequency wraps past the upper band edge.
    bool wrapped = false;

    // Lower band frequency where the current scan started, in kHz.
    int start_frequency_khz = 0;

    // Most recent frequency reported by scan_next_cb, in kHz.
    int last_frequency_khz = 0;

    // Bounded deduplicated collection of discovered station frequencies.
    ScanResultCollector stations;
};

// Active asynchronous signal-metric request type.
enum class HeliumMetricType {
    kNone,
    kSinr,
    kRmssi,
};

// Outcome of attempting to start a periodic SINR/RMSSI request.
enum class HeliumMetricRequestResult {
    kOk,
    kSkipped,
    kUnsupported,
};

// Tracks an in-flight asynchronous SINR or RMSSI request.
struct HeliumRmssiState {
    // Currently pending metric type, or kNone when idle.
    HeliumMetricType type = HeliumMetricType::kNone;

    // Monotonic timestamp when the active request was issued.
    uint64_t requested_at_ms = 0;

    // Tuned frequency in kHz when the request was issued.
    int request_frequency_khz = 0;

    // Prevents repeating the pending-request warning log.
    bool timeout_logged = false;

    // Suppresses metric requests while tuning, seeking, or disabled.
    bool paused = false;

    // Resets the active request fields to idle.
    void clear_request() {
        type = HeliumMetricType::kNone;
        requested_at_ms = 0;
        request_frequency_khz = 0;
        timeout_logged = false;
    }
};

// Complete runtime state of HalHeliumBackend, guarded by lock.
struct HeliumRuntimeState {
    // dlopen() handle for fm_helium.so.
    void *lib_handle = nullptr;

    // Resolved FM_HELIUM_LIB_INTERFACE pointer table.
    fm_interface_t *vendor = nullptr;

    // True after hal_init() succeeds.
    bool initialized = false;

    // True after enabled_cb arrives and before disabled_cb clears it.
    bool enabled = false;

    // enabled alone cannot describe an enable that timed out or an asynchronous
    // disable still in progress. Only disabled_cb confirms receiver cleanup.
    bool receiver_cleanup_needed = false;

    // True while STATE=OFF has been issued and disabled_cb is still pending.
    bool disable_pending = false;

    // Cached stereo, soft-mute, and Slimbus flags.
    HeliumAudioState audio;

    // Desired low-power mode flag.
    bool low_power = false;

    // Desired automatic AF jump flag.
    bool auto_af = false;

    // Selected antenna index.
    int antenna = 0;

    // Active startup region profile.
    StartupRegion region = StartupRegion::kEurope;

    // Vendor region enum mapped from StartupRegion.
    int vendor_region = 0;

    // Lower band boundary in kHz.
    int lower_band_khz = 87500;

    // Upper band boundary in kHz.
    int upper_band_khz = 108000;

    // Channel spacing step in kHz.
    int spacing_khz = 100;

    // Vendor channel spacing enum mapped from spacing_khz.
    int vendor_spacing = 1;

    // De-emphasis constant for the active region.
    int emphasis = kFmEmphasis50Microseconds;

    // RDS/RBDS standard selector for the active region.
    int rds_standard = kFmRdsStandardRds;

    // Last requested or callback-reported frequency in kHz.
    int current_frequency_khz = 87500;

    // Monotonic timestamp of the last enabled_cb.
    uint64_t last_enabled_cb_ms = 0;

    // Monotonic timestamp of the last tune_cb.
    uint64_t last_tune_cb_ms = 0;

    // Monotonic timestamp of the last seek_cmpl_cb.
    uint64_t last_seek_cb_ms = 0;

    // Asynchronous SINR/RMSSI request state.
    HeliumRmssiState rmssi;

    // True when last_search_payload holds a previous station-list summary.
    bool last_search_payload_valid = false;

    // Formatted string of the previous station list for replay deduplication.
    char last_search_payload[5 * 20 + 1] = "";

    // Sequential scan collection state.
    HeliumScanState scan;

    // True when last_rt_plus holds a valid RT+ payload for deduplication.
    bool last_rt_plus_valid = false;

    // Previous raw RT+ payload bytes for deduplication.
    unsigned char last_rt_plus[15] = {};

    // True when last_ecc holds a valid ECC payload for deduplication.
    bool last_ecc_valid = false;

    // Previous raw ECC payload bytes for deduplication.
    unsigned char last_ecc[12] = {};

    // RAW (pre-UTF-8-conversion) EBU Latin RT bytes. RT+ tag start/length
    // indices refer to these raw characters, not to the UTF-8 text sent to
    // the app. Load-bearing for RT+ slicing (issue #103).
    char current_rt[65] = "";

    // Length of valid raw EBU Latin bytes in current_rt.
    int current_rt_len = 0;

    // True when rt_plus_tags holds active tag definitions for the current RT.
    bool rt_plus_tags_valid = false;

    // Up to two active RT+ tags parsed from rt_plus_update_cb.
    RdsRtPlusTag rt_plus_tags[2];

    // Most recent backend error message for last_error().
    char last_error[128] = "";

    // Mutex protecting runtime state fields across command and callback threads.
    pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;

    // Condition variable signaled by enabled_cb and disabled_cb.
    pthread_cond_t cond = PTHREAD_COND_INITIALIZER;
};
