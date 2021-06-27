#include <sys/types.h>
#include <zconf.h>

typedef unsigned int uint32;
typedef int int32;
typedef unsigned short uint16;
typedef short int16;
typedef unsigned char uint8;
typedef char int8;
typedef unsigned char boolean;

#define FALSE 0
#define TRUE 1

// Add implementations of system_property_set/get
#ifdef __ANDROID_API__
#    include <sys/system_properties.h>
#    include <sys/stat.h>
#else
#    define __system_property_get(x, y)
//#    define __system_property_get(x, y)
#    define asprintf(x, y, z)
#endif

// Add debug function macros
#ifdef DEBUG
#    define print(x) printf(x)
#    define print2(x,y) printf(x,y)
#    define print3(x,y,z) printf(x,y,z)
#else
#    define print(x)
#    define print2(x, y)
#    define print3(x, y, z)
#endif

/*
 * Multiplying factor to convert to Radio frequency
 * The frequency is set in units of 62.5 Hz when using V4L2_TUNER_CAP_LOW,
 * 62.5 kHz otherwise.
 * The tuner is able to have a channel spacing of 50, 100 or 200 kHz.
 * tuner->capability is therefore set to V4L2_TUNER_CAP_LOW
 * The TUNE_MULT is then: 1 MHz / 62.5 Hz = 16000
 */
#define TUNE_MULT 16000

#define FREQ_LOWER  87500
#define FREQ_UPPER 108000

#define TAVARUA_BUF_SRCH_LIST 0
#define TAVARUA_BUF_EVENTS    1
#define TAVARUA_BUF_RT_RDS    2
#define TAVARUA_BUF_PS_RDS    3
#define TAVARUA_BUF_RAW_RDS   4
#define TAVARUA_BUF_AF_LIST   5
#define TAVARUA_BUF_MAX       6

#ifndef __FMCOMMON_H
#define __FMCOMMON_H

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
} fm_cmd_status_t;

typedef enum {
	FM_RX_US_EUROPE = 0x1,
	FM_RX_JAPAN_STANDARD = 0x2,
	FM_RX_JAPAN_WIDE = 0x3,
	FM_RX_USER_DEFINED = 0x4
} radio_band_t;

typedef enum {
	FM_RX_EMP75 = 0x0,
	FM_RX_EMP50 = 0x1
} emphasis_t;

typedef enum {
	FM_RX_SPACE_200KHZ = 0x0,
	FM_RX_SPACE_100KHZ = 0x1,
	FM_RX_SPACE_50KHZ = 0x2
} channel_space_t;

typedef enum {
	FM_RX_RDBS_SYSTEM = 0x0,
	FM_RX_RDS_SYSTEM = 0x1,
	FM_RX_NO_RDS_SYSTEM = 0x2
} rds_system_t;

typedef struct {
	uint32 lower_limit;
	uint32 upper_limit;
} band_limit_freq;

typedef enum {
	FM_RDS_NOT_SYNCED = 0x0,
	FM_RDS_SYNCED = 0x1
} rds_sync_t;

typedef enum {
	FM_RX_MONO = 0x0,
	FM_RX_STEREO = 0x1
} stereo_t;

typedef enum {
    FM_RX_POWER_MODE_NORMAL = 0,
    FM_RX_POWER_MODE_LOW,
} power_mode_t;

typedef enum {
	FM_SERVICE_NOT_AVAILABLE = 0x0,
	FM_SERVICE_AVAILABLE = 0x1
} fm_available_t;

typedef enum {
	FM_RX_NO_MUTE = 0x00,
	FM_RX_MUTE_RIGHT = 0x01,
	FM_RX_MUTE_LEFT = 0x02,
	FM_RX_MUTE_BOTH = 0x03
} mute_t;

typedef enum {
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
	emphasis_t emphasis;
	channel_space_t spacing;
	boolean rds_enable;
	rds_system_t rds_system;
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
	uint32 rds_group_options;
} fm_cfg_request;
#endif
