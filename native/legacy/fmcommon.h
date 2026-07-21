#pragma once

#include <sys/types.h>
#include <zconf.h>
#include <stdio.h>
#include <stdarg.h>

#include "../types.h"

// Add implementations of system_property_set/get
#ifdef __ANDROID_API__
#    include <sys/system_properties.h>
#    include <sys/stat.h>
#else
#    define __system_property_get(x, y)
//#    define __system_property_get(x, y)
#    define asprintf(x, y, z)
#endif

#ifndef RFM_LEGACY_LOG_HELPERS
#define RFM_LEGACY_LOG_HELPERS

static inline void legacy_log(const char* scope, const char* fmt, ...) {
    printf("leg/%-7s: ", scope);

    va_list args;
    va_start(args, fmt);
    vprintf(fmt, args);
    va_end(args);

    printf("\n");
}

#endif

/*
 * Multiplying factor to convert to Radio frequency
 * The frequency is set in units of 62.5 Hz when using V4L2_TUNER_CAP_LOW,
 * 62.5 kHz otherwise.
 * The tuner is able to have a channel spacing of 50, 100 or 200 kHz.
 * tuner->capability is therefore set to V4L2_TUNER_CAP_LOW
 * The kTuneMultiplier is then: 1 MHz / 62.5 Hz = 16000
 */
constexpr int kTuneMultiplier = 16000;

constexpr int kDefaultLowerFrequencyKhz = 87500;
constexpr int kDefaultUpperFrequencyKhz = 108000;

constexpr int kTavaruaBufSearchList = 0;
constexpr int kTavaruaBufEvents = 1;
constexpr int kTavaruaBufRtRds = 2;
constexpr int kTavaruaBufPsRds = 3;
constexpr int kTavaruaBufRawRds = 4;
constexpr int kTavaruaBufAfList = 5;
constexpr int kTavaruaBufPeek = 6;
constexpr int kTavaruaBufSsbiPeek = 7;
constexpr int kTavaruaBufRdsCounters = 8;
constexpr int kTavaruaBufReadDefault = 9;
constexpr int kTavaruaBufCalibrationData = 10;
constexpr int kTavaruaBufRtPlus = 11;
constexpr int kTavaruaBufErt = 12;
constexpr int kTavaruaBufEventsCci = 13;
constexpr int kTavaruaBufMax = 14;

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
 * FM Config Request structure.
 */
typedef struct fm_config_data {
	emphasis_t emphasis;
	channel_space_t spacing;
	uint8 antenna;
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
	uint8 srch_list_max;
	uint8 program_type;
} fm_search_list_stations;


#endif
