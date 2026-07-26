#pragma once

#ifdef __cplusplus
extern "C" {
#endif

#include <limits.h>

#include "types.h"

#define CS_PORT 2112
#define CS_PORT_SRV 2113
#define CS_BUF 512

#define RADIO_PATCH_ABSENT_INT -1
#define RADIO_PATCH_ABSENT_RMSSI INT_MIN

typedef struct {
    int frequency_khz;
    const char *ps;
    const char *rt;
    const char *pi;
    const char *country;
    int pty;
    const int *af_khz;
    int af_count;
    int stereo;
    int rmssi;
} radio_state_patch_t;

typedef struct {
    int code;
    const char *data;
} response_t;

typedef response_t (*fm_srv_callback)(char *);

void poll_rmssi(void);
int init_server(fm_srv_callback request_callback);
radio_state_patch_t radio_state_patch_empty(void);
bool send_radio_state_patch(const radio_state_patch_t *patch);
bool send_native_event(const char *type);
bool send_search_done(const int *frequencies_khz, int count);

#ifdef __cplusplus
}
#endif
