#include "fmcommon.h"

#ifndef CPP_FMSRV_H
#   define CPP_FMSRV_H

#   define CS_PORT 2112
#   define CS_PORT_SRV 2113
#   define CS_BUF 256

#define EVT_ENABLED 1
#define EVT_DISABLED 2
#define EVT_FREQUENCY_SET 4
#define EVT_UPDATE_RSSI 5
#define EVT_UPDATE_PS 6
#define EVT_UPDATE_RT 7
#define EVT_SEEK_COMPLETE 8
#define EVT_STEREO 9
#define EVT_SEARCH_DONE 10
#define EVT_UPDATE_PTY 11
#define EVT_UPDATE_PI 12
#define EVT_UPDATE_AF 14
#define EVT_INIT 999

typedef struct {
	int code;
	const char* data;
} response_t;

typedef response_t (*fm_srv_callback) (char*);

int init_server(fm_srv_callback request_callback);

boolean send_interruption_info(int evt, char* message);

#endif //CPP_FMSRV_H
