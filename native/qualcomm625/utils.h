#include "fmcommon.h"

#ifndef FMBIN_UTILS_H
#define FMBIN_UTILS_H

void wait(int ms);

char* int_to_string(uint32 val);
char* int_to_hex_string(uint32 val);

uint32 tunefreq_to_khz(uint32 freq);
uint32 khz_to_tunefreq(uint32 freq);

void make_frequency_limit_by_band(radio_band_t band, band_limit_freq* limits);

boolean file_exists(const char* file);

#endif //FMBIN_UTILS_H
