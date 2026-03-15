#pragma once

#include <stddef.h>
#include <stdint.h>

#define V4L2_CID_PRV_BASE 0x8000000
#define V4L2_CID_PRV_SRCHMODE (V4L2_CID_PRV_BASE + 1)
#define V4L2_CID_PRV_SCANDWELL (V4L2_CID_PRV_BASE + 2)
#define V4L2_CID_PRV_SRCHON (V4L2_CID_PRV_BASE + 3)
#define V4L2_CID_PRV_STATE (V4L2_CID_PRV_BASE + 4)
#define V4L2_CID_PRV_RDSGROUP_MASK (V4L2_CID_PRV_BASE + 6)
#define V4L2_CID_PRV_REGION (V4L2_CID_PRV_BASE + 7)
#define V4L2_CID_PRV_SIGNAL_TH (V4L2_CID_PRV_BASE + 8)
#define V4L2_CID_PRV_SRCH_PTY (V4L2_CID_PRV_BASE + 9)
#define V4L2_CID_PRV_SRCH_PI (V4L2_CID_PRV_BASE + 10)
#define V4L2_CID_PRV_SRCH_CNT (V4L2_CID_PRV_BASE + 11)
#define V4L2_CID_PRV_EMPHASIS (V4L2_CID_PRV_BASE + 12)
#define V4L2_CID_PRV_RDS_STD (V4L2_CID_PRV_BASE + 13)
#define V4L2_CID_PRV_CHAN_SPACING (V4L2_CID_PRV_BASE + 14)
#define V4L2_CID_PRV_RDSON (V4L2_CID_PRV_BASE + 15)
#define V4L2_CID_PRV_RDSGROUP_PROC (V4L2_CID_PRV_BASE + 16)
#define V4L2_CID_PRV_LP_MODE (V4L2_CID_PRV_BASE + 17)
#define V4L2_CID_PRV_ANTENNA (V4L2_CID_PRV_BASE + 18)
#define V4L2_CID_PRV_RDSD_BUF (V4L2_CID_PRV_BASE + 19)
#define V4L2_CID_PRV_PSALL (V4L2_CID_PRV_BASE + 20)
#define V4L2_CID_PRV_AF_JUMP (V4L2_CID_PRV_BASE + 27)
#define V4L2_CID_PRV_SOFT_MUTE (V4L2_CID_PRV_BASE + 30)
#define V4L2_CID_PRV_RDS_GRP_COUNTERS (V4L2_CID_PRV_BASE + 39)
#define V4L2_CID_PRV_RDS_GRP_COUNTERS_EXT (V4L2_CID_PRV_BASE + 66)
#define V4L2_CID_PRV_AUDIO_PATH (V4L2_CID_PRV_BASE + 41)
#define V4L2_CID_PRV_IRIS_FREQ 0x0098092F
#define V4L2_CID_PRV_IRIS_SEEK 0x00980930
#define V4L2_CID_PRV_IRIS_UPPER_BAND 0x00980931
#define V4L2_CID_PRV_IRIS_LOWER_BAND 0x00980932
#define V4L2_CID_PRV_IRIS_AUDIO_MODE 0x00980933
#define V4L2_CID_PRV_IRIS_RMSSI 0x00980943
#define V4L2_CID_PRV_ENABLE_SLIMBUS 0x00980940

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

typedef struct {
    size_t size;
    enb_result_cb enabled_cb;
    tune_rsp_cb tune_cb;
    seek_rsp_cb seek_cmpl_cb;
    scan_rsp_cb scan_next_cb;
    srch_list_rsp_cb srch_list_cb;
    stereo_mode_cb stereo_status_cb;
    rds_avl_sts_cb rds_avail_status_cb;
    af_list_cb af_list_update_cb;
    rt_cb rt_update_cb;
    ps_cb ps_update_cb;
    oda_cb oda_update_cb;
    rt_plus_cb rt_plus_update_cb;
    ert_cb ert_update_cb;
    disable_cb disabled_cb;
    rds_grp_cntrs_cb rds_grp_cntrs_rsp_cb;
    rds_grp_cntrs_ext_cb rds_grp_cntrs_ext_rsp_cb;
    fm_peek_cb fm_peek_rsp_cb;
    fm_ssbi_peek_cb fm_ssbi_peek_rsp_cb;
    fm_agc_gain_cb fm_agc_gain_rsp_cb;
    fm_ch_det_th_cb fm_ch_det_th_rsp_cb;
    fm_ecc_evt_cb ext_country_code_cb;
    callback_thread_event thread_evt_cb;
    fm_sig_thr_cb fm_get_sig_thres_cb;
    fm_get_ch_det_thrs_cb fm_get_ch_det_thr_cb;
    fm_def_data_rd_cb fm_def_data_read_cb;
    fm_get_blnd_cb fm_get_blend_cb;
    fm_set_ch_det_thrs_cb fm_set_ch_det_thr_cb;
    fm_def_data_wrt_cb fm_def_data_write_cb;
    fm_set_blnd_cb fm_set_blend_cb;
    fm_get_stn_prm_cb fm_get_station_param_cb;
    fm_get_stn_dbg_prm_cb fm_get_station_debug_param_cb;
    fm_enable_slimbus_cb enable_slimbus_cb;
    fm_enable_softmute_cb enable_softmute_cb;
} fm_hal_callbacks_t;

struct fm_interface_t {
    int (*hal_init)(const fm_hal_callbacks_t *p_cb);
    int (*set_fm_ctrl)(int opcode, int val);
    int (*get_fm_ctrl)(int opcode, int *val);
};

struct hci_ev_rel_freq {
    char rel_freq_msb;
    char rel_freq_lsb;
};

struct hci_ev_srch_list_compl {
    char num_stations_found;
    struct hci_ev_rel_freq rel_freq[20];
};

struct hci_ev_af_list {
    int tune_freq;
    short pi_code;
    uint8_t af_size;
    char af_list[200];
} __attribute__((packed));
