/**
 * FM Test Application
 * Test application for FM V4L2 APIs
 * Copyright (c) 2011 by Qualcomm Technologies, Inc.  All Rights Reserved.
 * Qualcomm Technologies Proprietary and Confidential.
 * @author rakeshk
 */

#define VERSION "1.1.8"

#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <stdint.h>
#include "fmhalapis.h"
#include "fmsrv.h"

srv_response api_fetch(char* request);

#define MK_ENABLE        "enable"
#define MK_DISABLE       "disable"
#define MK_SET_FREQUENCY "setfreq"
#define MK_GET_RSSI      "getrssi"
#define MK_SET_STEREO    "setstereo"
#define MK_HW_SEEK       "seekhw"
#define MK_SET_MUTE      "setmute"
#define MK_SEARCH        "search"
#define MK_JUMP          "jump"
#define MK_HW_SCAN       "scan"
#define MK_TEST          "test"

#define CD_OK 0
#define CD_ERR -1

#define RSP_OK "ok"
#define RSP_ERR_NO_ARG      "ERR_IVD_FRQ"
#define RSP_ERR_UNKNOWN     "ERR_UNK_CMD"
#define RSP_ERR_INVALID_ARG "ERR_INVLARG"

#define VAR_MUTE_NONE  '0'
#define VAR_MUTE_LEFT  '1'
#define VAR_MUTE_RIGHT '2'
#define VAR_MUTE_BOTH  '3'

#define str_equals(x, y) (strncmp(x, y, 5) == 0)
#define ARG_N 3

char** args_parse(char* cmd) {
	const int str_len = 15;
	const char* delimiter = " ";
	char* chr_ptr;
	char** token = malloc(ARG_N * sizeof(char*));

	int j = 0;
	for (j = 0; j < ARG_N; ++j) {
		token[j] = malloc(str_len * sizeof(char));
	}

	token[0] = strtok_r(cmd, delimiter, &chr_ptr);

	if (token[0] == NULL) {
		return token;
	}

	for (j = 1; j < ARG_N; ++j) {
		token[j] = strtok_r(NULL, delimiter, &chr_ptr);
		if (token[j] == NULL) {
			break;
		}
	}

	return token;
}

srv_response api_fetch(char* request) {
	char** ar = args_parse(request);
	char* cmd = ar[0];

	srv_response* res = malloc(sizeof(srv_response));

	res->code = CD_ERR;
	res->data = RSP_ERR_UNKNOWN;

	if (str_equals(cmd, MK_GET_RSSI)) {
		fm_station_params_available config;
		fm_cmd_status_type ret = GetStationParametersReceiver(&config);

		if (ret == FM_CMD_FAILURE) {
			res->code = CD_ERR;
			res->data = "0";
			goto __ret;
		}

		char response[6];

		sprintf(response, "%d", config.rssi);

		res->code = ret;
		res->data = response;
	} else if (str_equals(cmd, MK_ENABLE)) {
		//qcv_digital_input_on();

		fm_config_data cfg_data = {
			.band = FM_RX_US_EUROPE,
			.emphasis = FM_RX_EMP75,
			.spacing = FM_RX_SPACE_50KHZ,
			.rds_system = FM_RX_RDS_SYSTEM,
			.bandlimits = {
				.lower_limit = FREQ_LOWER,
				.upper_limit = FREQ_UPPER
			}
		};

		res->code = EnableReceiver(&cfg_data);
		res->data = RSP_OK;

		SetMuteModeReceiver(FM_RX_NO_MUTE);
	} else if (str_equals(cmd, MK_DISABLE)) {
		res->code = DisableReceiver();
		res->data = RSP_OK;
		//qcv_digital_input_off();
	} else if (str_equals(ar[0], MK_SET_FREQUENCY)) {
		if (ar[1] == NULL) {
			res->code = 1;
			res->data = RSP_ERR_NO_ARG;
			goto __ret;
		}
		uint32 freq = atoi(ar[1]); // NOLINT(cert-err34-c)
		fm_cmd_status_type ret = SetFrequencyReceiver(freq);
		res->code = ret;
		res->data = RSP_OK;
	} else if (str_equals(ar[0], MK_HW_SEEK)) {
		uint8 direction = str_equals(ar[1], "1") ? 1 : 0;

		fm_search_stations cfg_data = {
			.search_dir = direction,
			.search_mode = 0x00,
			.dwell_period = 0x07
		};

		res->code = SearchStationsReceiver(cfg_data);

		res->data = RSP_OK;
	} else if (str_equals(ar[0], MK_JUMP)) {
		uint32 direction = str_equals(ar[1], "1") ? 100 : -100;

		res->code = JumpToFrequencyReceiver(direction);
		res->data = RSP_OK;
	} else if (str_equals(cmd, MK_SET_STEREO)) {
		res->code = SetStereoModeReceiver(FM_RX_STEREO);

		fm_station_params_available config;
		fm_cmd_status_type ret = GetStationParametersReceiver(&config);

		if (ret == FM_CMD_FAILURE) {
			res->code = CD_ERR;
			res->data = "0";
			goto __ret;
		}

		char response[6];

		sprintf(response, "%d", config.audmode);

		res->data = response;
	} else if (str_equals(ar[0], MK_SET_MUTE)) {
		mute_type type = -1;
		switch (ar[1][0]) {
			case VAR_MUTE_NONE: type = FM_RX_NO_MUTE; break;
			case VAR_MUTE_LEFT: type = FM_RX_MUTE_LEFT; break;
			case VAR_MUTE_RIGHT: type = FM_RX_MUTE_RIGHT; break;
			case VAR_MUTE_BOTH: type = FM_RX_MUTE_BOTH; break;
		}

		if (type == -1) {
			res->code = CD_ERR;
			res->data = "0";
			goto __ret;
		}

		res->code = SetMuteModeReceiver(type);
		res->data = RSP_OK;
	} else if (str_equals(cmd, MK_SEARCH)) {
		fm_search_list_stations liststationparams;

		liststationparams.search_mode = 0x02;
		liststationparams.search_dir = 0x00;
		liststationparams.srch_list_max = 10;
		liststationparams.program_type = 0x00;
		fm_cmd_status_type ret = SearchStationListReceiver(liststationparams);

		res->code = CD_OK;
		res->data = ret ? RSP_OK : RSP_ERR_UNKNOWN;
	} else if (str_equals(cmd, MK_TEST)) {
		res->code = CD_OK;
		char str[20] = {0};
		sprintf(str, "Version %s", VERSION);
		res->data = str;
	}

__ret:
	return *res;
}

int main(int argc, char *argv[]) {
	printf("FM binary root v%s\nAuthor: vladislav805\n", VERSION);
	init_server(&api_fetch);
}
