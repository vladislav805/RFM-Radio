#define VERSION "0.6.dev"

#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <stdint.h>
#include "fm_wrap.h"
#include "ctl_server.h"
#include "fm_ctl.h"

response_t api_handler(char* request);

#define MK_GET_RSSI      "getrssi"
#define MK_SET_STEREO    "setstereo"
#define MK_SET_MUTE      "setmute"
#define MK_SEARCH        "search"
#define MK_HW_SCAN       "scan"

#define CD_OK 0
#define CD_ERR (-1)

#define RSP_OK "ok"
#define RSP_ERR_NO_ARG          "ERR_IVD_FRQ"
#define RSP_ERR_UNKNOWN         "ERR_UNK_CMD"
#define RSP_ERR_INVALID_ANTENNA "ERR_UNV_ANT"
#define RSP_ERR_CANT_SET_REGION "ERR_CNS_REG"
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

typedef void (*handler_function) (response_t*, char**);

/**
 * Initialize FM device
 */
void handler_open(response_t* response, char** args) {
    response->code = fm_command_open();
    response->data = RSP_OK;
}

/**
 * Enable receiver
 */
void handler_enable(response_t* response, char** args) {
    // Config for enable
    fm_config_data cfg_data = {
            .band = FM_RX_US_EUROPE,
            .emphasis = FM_RX_EMP75,
            .spacing = FM_RX_SPACE_100KHZ,
            .rds_system = FM_RX_RDS_SYSTEM,
    };

    // Call to receiver enable with config
    response->code = fm_command_prepare(&cfg_data);
    response->data = RSP_OK;

    fm_command_set_mute_mode(FM_RX_NO_MUTE);
}

/**
 * Disable receiver
 */
void handler_disable(response_t* response, char** args) {
    response->code = fm_command_disable();
    response->data = RSP_OK;
}

/**
 * Set current frequency
 */
void handler_set_frequency(response_t* response, char** args) {
    if (args[1] == NULL) {
        response->code = 1;
        response->data = RSP_ERR_NO_ARG;
        return;
    }

    uint32 freq = atoi(args[1]); // NOLINT(cert-err34-c)
    fm_cmd_status_t ret = fm_command_tune_frequency(freq);
    response->code = ret;
    response->data = RSP_OK;
}

void handler_jump(response_t* response, char** args) {
    signed short direction = str_equals(args[1], "1") ? 1 : -1;

    response->code = fm_command_tune_frequency_by_delta(direction);

    char res[8];
    sprintf(res, "%d", fm_command_get_tuned_frequency());
    response->data = res;
}

void handler_seek(response_t* response, char** args) {
    uint8 direction = str_equals(args[1], "1") ? 1 : 0;

    response->code = fm_receiver_search_station_seek(SEEK, direction, 7);

    response->data = RSP_OK;
    response->code = FM_CMD_SUCCESS;
}

void handler_power_mode(response_t* response, char** args) {
    power_mode_t mode = str_equals(args[1], "low")
            ? FM_RX_POWER_MODE_LOW
            : FM_RX_POWER_MODE_NORMAL;

    fm_receiver_set_power_mode(mode);

    response->data = RSP_OK;
    response->code = FM_CMD_SUCCESS;
}

void handler_rds_toggle(response_t* response, char** args) {
    rds_system_t system = str_equals(args[1], "1")
                        ? FM_RX_RDS_SYSTEM
                        : FM_RX_NO_RDS_SYSTEM;

    fm_command_setup_rds(system);

    response->data = RSP_OK;
    response->code = FM_CMD_SUCCESS;
}

void handler_stereo(response_t* response, char** args) {
    stereo_t mode = str_equals(args[1], "1")
                        ? FM_RX_STEREO
                        : FM_RX_MONO;

    fm_command_set_stereo_mode(mode);

    response->data = RSP_OK;
    response->code = FM_CMD_SUCCESS;
}

void handler_set_antenna(response_t* response, char** args) {
    uint8 antenna = atoi(args[1]);

    response->code = fm_receiver_set_antenna(antenna);
    response->data = response->code == TRUE ? RSP_OK : RSP_ERR_INVALID_ANTENNA;
}


void handler_set_region(response_t* response, char** args) {
    radio_band_t region = (uint8) atoi(args[1]);

    response->code = fm_receiver_set_band(region);
    response->data = response->code == TRUE ? RSP_OK : RSP_ERR_CANT_SET_REGION;
}

void handler_hw_search(response_t* response, char** args) {
    fm_search_list_stations options = {
            .search_mode = SCAN_FOR_STRONG,
            .search_dir = 0,
            .program_type = 0,
            .srch_list_max = 20,
    };
    response->code = fm_receiver_search_station_list(options);
    response->data = response->code == TRUE ? RSP_OK : RSP_ERR_UNKNOWN;
}

void handler_search_cancel(response_t* response, char** args) {
    response->code = fm_receiver_cancel_search();
    response->data = response->code == TRUE ? RSP_OK : RSP_ERR_UNKNOWN;
}

void handler_auto_af(response_t* response, char** args) {
    boolean enable = str_equals(args[1], "1");

    response->code = fm_receiver_toggle_af_jump(enable);
    response->data = response->code == TRUE ? RSP_OK : RSP_ERR_UNKNOWN;
}


/**
 * Hash for endpoint name
 * @param name Endpoint name
 * @return Hash
 */
uint32 hash_name_endpoint(char* name) {
    uint32 result = 0;
    uint8 length = strlen(name);

    for (uint8 i = 0; i < length; ++i) {
        result += (uint) name[i];
    }

    return result;
}

typedef struct {
    char* name;
    uint32 hash;
    void (*handler)(response_t*, char**);
} api_endpoint;

static api_endpoint endpoints[] = {
        {
                .name = "init",
                .handler = handler_open,
        },
        {
                .name = "enable",
                .handler = handler_enable,
        },
        {
                .name = "disable",
                .handler = handler_disable,
        },
        {
                .name = "setfreq",
                .handler = handler_set_frequency,
        },
        {
                .name = "jump",
                .handler = handler_jump,
        },
        {
                .name = "seekhw",
                .handler = handler_seek,
        },
        {
                .name = "power_mode",
                .handler = handler_power_mode,
        },
        {
                .name = "rds_toggle",
                .handler = handler_rds_toggle,
        },
        {
                .name = "set_stereo",
                .handler = handler_stereo,
        },
        {
                .name = "set_antenna",
                .handler = handler_set_antenna,
        },
        {
                .name = "set_region",
                .handler = handler_set_region,
        },
        {
                .name = "searchhw",
                .handler = handler_hw_search,
        },
        {
                .name = "search_cancel",
                .handler = handler_search_cancel,
        },
        {
                .name = "auto_af",
                .handler = handler_auto_af,
        },
};

static const uint8 endpoints_len = sizeof(endpoints) / sizeof(api_endpoint);

response_t api_handler(char* request) {
    // Parse request to string array
	char** ar = args_parse(request);

	// First element in array - name of command (always exists at least one element)
	char* command = ar[0];

	// Hash of command
	uint8 command_hash = hash_name_endpoint(command);

	// Allocate memory for response
	response_t* res = malloc(sizeof(response_t));

	res->code = CD_ERR;
	res->data = RSP_ERR_UNKNOWN;

	// Needle endpoint
	api_endpoint* found = NULL;

	// Find endpoint
	for (uint8 i = 0; i < endpoints_len; ++i) {
	    api_endpoint endpoint = endpoints[i];

	    if (str_equals(endpoint.name, command)) {
            found = &endpoint;
	        break;
	    }
	}

	// Command not found
	if (found != NULL) {
        found->handler(res, ar);
	} else {
	    printf("main_api_hand   : unknown endpoint '%s'\n", command);
	}

	return *res;



/*
	if (str_equals(ar[0], MK_HW_SEEK)) {

	} else if (str_equals(ar[0], MK_SET_MUTE)) {
		mute_t type = -1;
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

		res->code = fm_receiver_set_mute(type);
		res->data = RSP_OK;
	} else if (str_equals(command, MK_SEARCH)) {
		fm_search_list_stations liststationparams;

		liststationparams.search_mode = SCAN_FOR_STRONG;
		liststationparams.search_dir = 0x00;
		liststationparams.srch_list_max = 20;
		liststationparams.program_type = 0x00;
		fm_cmd_status_t ret = fm_receiver_search_station_list(liststationparams);

		res->code = CD_OK;
		res->data = ret ? RSP_OK : RSP_ERR_UNKNOWN;
	} else if (str_equals(command, MK_TEST)) {
		res->code = CD_OK;
		char str[20] = {0};
		sprintf(str, "Version %s", VERSION);
		res->data = str;
	} else if (str_equals(command, MK_EXIT)) {
		res->code = CD_OK;
		res->data = RSP_OK;
	}

__ret:*/

}

int main(int argc, char *argv[]) {
	printf("FM binary root v%s [%s %s]\nAuthor: vladislav805\n", VERSION, __DATE__, __TIME__);

	// Make hash codes for each endpoint
	for (uint8 i = 0; i < endpoints_len; ++i) {
	    api_endpoint* endpoint = &endpoints[i];
	    endpoint->hash = hash_name_endpoint(endpoint->name);
	}

	init_server(&api_handler);
}
