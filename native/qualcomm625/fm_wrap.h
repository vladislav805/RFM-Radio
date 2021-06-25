#include "fmcommon.h"

fm_cmd_status_t fm_command_open();
fm_cmd_status_t fm_command_prepare(fm_config_data* config_ptr);
fm_cmd_status_t fm_command_setup_receiver(fm_config_data* ptr);
fm_cmd_status_t fm_command_setup_rds(rds_system_t system);
fm_cmd_status_t fm_command_disable();

fm_cmd_status_t fm_command_tune_frequency(uint32 frequency);
fm_cmd_status_t fm_command_tune_frequency_by_delta(uint32 delta_khz);
uint32 fm_command_get_tuned_frequency();

fm_cmd_status_t fm_command_set_mute_mode(mute_t mode);
fm_cmd_status_t fm_command_set_stereo_mode(stereo_t is_stereo);

/*
fm_cmd_status_t fm_receiver_set_rds_options(fm_rds_options options);
fm_cmd_status_t fm_command_set_rds_group_proc(uint32 rdsgroup);
fm_cmd_status_t fm_command_set_power_mode(power_mode_t mode);
fm_cmd_status_t fm_command_set_signal_threshold(uint8 threshold);
fm_cmd_status_t fm_receiver_search_stations(fm_search_stations options);
fm_cmd_status_t fm_receiver_search_rds_stations(fm_search_rds_stations options);
fm_cmd_status_t fm_receiver_search_station_list(fm_search_list_stations options);
fm_cmd_status_t fm_receiver_cancel_search();
 */
