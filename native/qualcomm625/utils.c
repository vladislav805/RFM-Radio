#include <unistd.h>
#include <malloc.h>
#include <sys/stat.h>
#include "utils.h"

/**
 * Delay
 * @param ms Time
 */
void wait(int ms) {
    usleep(ms * 1000);
}


char tmp[31];
/**
 * Int to string
 * @param val Number
 * @return Pointer to string with number value
 */
char* int_to_string(uint32 val) {
    snprintf(tmp, 31, "%d", val);
    return tmp;
}

char* int_to_hex_string(uint32 val) {
    snprintf(tmp, 31, "%x", val);
    return tmp;
}

/**
 * Multiplying factor to convert to Radio frequency
 * The frequency is set in units of 62.5 Hz when using V4L2_TUNER_CAP_LOW,
 * 62.5 kHz otherwise.
 * The tuner is able to have a channel spacing of 50, 100 or 200 kHz.
 * tuner->capability is therefore set to V4L2_TUNER_CAP_LOW
 * The FREQ_MUL is then: 1 MHz / 62.5 Hz = 16000
 */
uint32 tunefreq_to_khz(uint32 freq) {
    return (freq * 1000) / TUNE_MULT;
}

uint32 khz_to_tunefreq(uint32 freq) {
    return (freq * TUNE_MULT / 1000);
}

/**
 * Make information for limits of frequencies by band
 * @param band
 * @param limits
 */
void make_frequency_limit_by_band(radio_band_t band, band_limit_freq* limits) {
    switch (band) {
        case FM_RX_JAPAN_STANDARD: {
            limits->lower_limit = 76000;
            limits->upper_limit = 95000;
            break;
        }

        case FM_RX_JAPAN_WIDE: {
            limits->lower_limit = 76000;
            limits->upper_limit = 108000;
            break;
        }

        case FM_RX_US_EUROPE:
        default: {
            limits->lower_limit = 87500;
            limits->upper_limit = 108000;
            break;
        }
    }
}

/**
 * Return 1 if file, or directory, or device node etc. exists
 * @param file Path to file/directory
 * @return True if exists
 */
boolean file_exists(const char* file) {
    struct stat sb;
    return stat(file, &sb) == 0;
}
