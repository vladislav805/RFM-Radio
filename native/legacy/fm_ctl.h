#include "fmcommon.h"
#include <linux/videodev2.h>

#ifndef FMBIN_FM_CTL_H
#define FMBIN_FM_CTL_H

typedef struct {
    uint16 program_id;
    uint8 program_type;
    char program_name[96];
    char radio_text[64];
} fm_rds_storage;

typedef enum {
    OFF = 0,
    RX = 1,
    TX = 2,
} fm_tuner_state;

typedef struct {
    uint32 frequency;
    mute_t mute_mode;
    stereo_t stereo_type;
    channel_space_t space_type;
    radio_band_t band_type;
    fm_tuner_state state;
    fm_rds_storage rds;
    fm_available_t avail;
} fm_current_storage;

bool set_v4l2_ctrl(uint32 id, int32 value);

bool fm_receiver_open();
bool fm_receiver_set_state(fm_tuner_state tuner_state);
bool fm_receiver_set_emphasis(emphasis_t emp_type);
bool fm_receiver_set_spacing(channel_space_t spacing);
bool fm_receiver_set_rds_state(bool enable);
bool fm_receiver_set_band(radio_band_t band);
bool fm_receiver_set_rds_system(rds_system_t system);
uint8 fm_receiver_get_rds_group_options();
bool fm_receiver_set_rds_group_options(uint32 options);
bool fm_receiver_set_ps_all(uint8 mode);
bool fm_receiver_set_antenna(uint8 antenna);
bool fm_receiver_query_capabilities(struct v4l2_capability* cap);
bool fm_receiver_set_tuned_frequency(uint32 frequency_khz);
uint32 fm_receiver_get_tuned_frequency();
bool fm_receiver_set_mute_mode(mute_t mode);
bool fm_receiver_toggle_af_jump(uint8 enable);
bool fm_receiver_set_power_mode(power_mode_t mode);
bool fm_receiver_set_stereo_mode(stereo_t mode);
bool fm_receiver_search_station_seek(search_t mode, int8 search_dir, uint8 dwell_period);
bool fm_receiver_search_station_list(fm_search_list_stations options);
bool fm_receiver_cancel_search();


void fm_receiver_close();

uint32 read_data_from_v4l2(const uint8 *buf, int index);

bool extract_program_service(fm_rds_storage* storage);
bool extract_radio_text(fm_rds_storage* storage);
uint8 extract_rds_af_list(uint32* list);
uint8 extract_search_station_list(uint32* list);

#endif //FMBIN_FM_CTL_H
