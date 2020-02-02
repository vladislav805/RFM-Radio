/**
 * FM Test App Common Header File
 * Global Data declarations of the fm test common component.
 * Copyright (c) 2011 by Qualcomm Technologies, Inc.  All Rights Reserved.
 * Qualcomm Technologies Proprietary and Confidential.
 */

/**
 * https://github.com/carvsdriver/msm8660-common_marla/blob/master/drivers/media/radio/radio-tavarua.c
 * https://github.com/carvsdriver/msm8660-common_marla/blob/master/include/media/tavarua.h
 *
 *
 * https://android.googlesource.com/kernel/msm/+/android-7.1.0_r0.2/drivers/media/radio/radio-iris.c
 *
 * https://source.codeaurora.org/external/gigabyte/qrd-gb-dsds-7225/plain/kernel/drivers/media/radio/radio-tavarua.c
 * https://source.codeaurora.org/external/gigabyte/qrd-gb-dsds-7225/plain/kernel/include/media/tavarua.h
 *
 * https://github.com/SivanLiu/VivoFramework/blob/master/Vivo_y93/src/main/java/qcom/fmradio/FmReceiver.java
 */
#include <sys/types.h>
#include <zconf.h>

typedef unsigned int uint32;
typedef int int32;
typedef unsigned short uint16;
typedef unsigned char uint8;
typedef unsigned char boolean;

#define FALSE 0
#define TRUE 1

#define FREQ_LOWER  87500
#define FREQ_UPPER 108000

/* FM power state enum */
typedef enum {
	FM_POWER_OFF,
	FM_POWER_TRANSITION,
	FM_POWER_ON
} fm_power_state;

/* FM command status enum */
typedef enum {
	FM_CMD_SUCCESS,
	FM_CMD_PENDING,
	FM_CMD_NO_RESOURCES,
	FM_CMD_INVALID_PARAM,
	FM_CMD_DISALLOWED,
	FM_CMD_UNRECOGNIZED_CMD,
	FM_CMD_FAILURE
} fm_cmd_status_type;

typedef enum radio_band_type {
	FM_RX_US_EUROPE = 0x1,
	FM_RX_JAPAN_STANDARD = 0x2,
	FM_RX_JAPAN_WIDE = 0x3,
	FM_RX_USER_DEFINED = 0x4
} radio_band_type;

typedef enum emphasis_type {
	FM_RX_EMP75 = 0x0,
	FM_RX_EMP50 = 0x1
} emphasis_type;

typedef enum channel_space_type {
	FM_RX_SPACE_200KHZ = 0x0,
	FM_RX_SPACE_100KHZ = 0x1,
	FM_RX_SPACE_50KHZ = 0x2
} channel_space_type;

typedef enum rds_system_type {
	FM_RX_RDBS_SYSTEM = 0x0,
	FM_RX_RDS_SYSTEM = 0x1,
	FM_RX_NO_RDS_SYSTEM = 0x2
} rds_sytem_type;

typedef struct band_limit_freq {
	uint32 lower_limit;
	uint32 upper_limit;
} band_limit_freq;

typedef enum rds_sync_type {
	FM_RDS_NOT_SYNCED = 0x0,
	FM_RDS_SYNCED = 0x1
} rds_sync_type;

typedef enum stereo_type {
	FM_RX_MONO = 0x0,
	FM_RX_STEREO = 0x1
} stereo_type;

typedef enum fm_service_available {
	FM_SERVICE_NOT_AVAILABLE = 0x0,
	FM_SERVICE_AVAILABLE = 0x1
} fm_service_available;

typedef enum mute_type {
	FM_RX_NO_MUTE = 0x00,
	FM_RX_MUTE_RIGHT = 0x01,
	FM_RX_MUTE_LEFT = 0x02,
	FM_RX_MUTE_BOTH = 0x03
} mute_type;

typedef enum search_t {
	SEEK,
	SCAN,
	SCAN_FOR_STRONG,
	SCAN_FOR_WEAK,
	RDS_SEEK_PTY,
	RDS_SCAN_PTY,
	RDS_SEEK_PI,
	RDS_AF_JUMP,
} search_t;

/**
 * RDS/RBDS Program Type type.
 */
typedef uint8 fm_prgm_type;

/**
 * RDS/RBDS Program Identification type.
 */
typedef uint16 fm_prgmid_type;

/**
 * RDS/RBDS Program Services type.
 */
typedef char fm_prm_services;

/**
 * RDS/RBDS Radio Text type.
 */
typedef char fm_radiotext_info;

/**
 * FM Global Paramaters struct.
 */
typedef struct {
	/**
	 * a frequency in kHz the band range
	 */
	uint32 current_station_freq;

	uint8 service_available;

	/**
	 * rssi range from 0-100
	 */
	uint8 rssi;

	uint8 stype;

	uint8 rds_sync_status;

	uint8 mute_status;

	uint32 audmode;

	/**
	 * Program Id
	 */
	fm_prgmid_type pgm_id;

	/**
	 * Program type
	 */
	fm_prgm_type pgm_type;

	/**
	 * Program services Can maximum hold 96
	 */
	fm_prm_services pgm_services[96];

	/**
	 * RT maximum is 64 bytes
	 */
	fm_radiotext_info radio_text[64];
} fm_station_params_available;

/**
 * FM Config Request structure.
 */
typedef struct fm_config_data {
	uint8 band;
	uint8 emphasis;
	uint8 spacing;
	uint8 rds_system;
	band_limit_freq bandlimits;
} fm_config_data;

/**
 * FM RDS Options Config Request
 */
typedef struct fm_rds_options {
	uint32 rds_group_mask;
	uint32 rds_group_buffer_size;
	uint8 rds_change_filter;
} fm_rds_options;

/**
 * FM RX Search stations request
 */
typedef struct fm_search_stations {
	search_t search_mode;
	uint8 dwell_period;
	uint8 search_dir;
} fm_search_stations;

/**
 * FM RX Search DDS stations request
 */
typedef struct fm_search_rds_stations {
	search_t search_mode;
	uint8 dwell_period;
	uint8 search_dir;
	uint8 program_type;
	uint16 program_id;
} fm_search_rds_stations;

/**
 * FM RX Search station lists request
 */
typedef struct fm_search_list_stations {
	search_t search_mode;
	uint8 search_dir;
	uint32 srch_list_max;
	/**< Maximum number of stations that can be returned from a search. */
	uint8 program_type;
} fm_search_list_stations;

/**
 * FM RX I2C request
 */
typedef struct fm_i2c_params {
	uint8 slaveaddress;
	uint8 offset;
	uint8 payload_length;
	uint8 data[64];
} fm_i2c_params;

/**
 * FM All Request Union type.
 */
typedef union fm_cfg_request {
	fm_config_data cfg_param;
	uint8 mute_param;
	uint8 stereo_param;
	uint32 freq;
	fm_rds_options rds_options;
	uint8  power_mode;
	uint8  signal_threshold;
	fm_search_stations search_stations_options;
	fm_search_rds_stations search_rds_stations_options;
	fm_search_list_stations search_list_stations_options;
	fm_i2c_params i2c_params;
	uint32 rds_group_options;
} fm_cfg_request;
