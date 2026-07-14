#include "fm_ctl.h"
#include "fmcommon.h"
#include "../rds_parser.h"
#include "../fm_v4l2_controls.h"
#include "utils.h"
#include <algorithm>
#include <fcntl.h>
#include <linux/videodev2.h>
#include <stdio.h>
#include <string.h>
#ifndef __ANDROID_API__
#    include <sys/ioctl.h>
#endif

constexpr int kRdsGroupRt = 1 << 0;
constexpr int kRdsGroupPs = 1 << 1;
constexpr int kRdsGroupAf = 1 << 2;
constexpr int kRdsAfAuto = 1 << 6;
constexpr int kRdsPsAll = 1 << 4;
constexpr int kRdsAfJump = 1;
constexpr int kMaxTagCodes = 64;

/**
 * V4L2 radio handle
 */
int fd_radio = -1;
extern fm_current_storage fm_storage;

/**
 * set_v4l2_ctrl
 * Sets the V4L2 control sent as argument with the requested value and returns the status
 * @return FALSE in failure, TRUE in success
 */
bool set_v4l2_ctrl(uint32 id, int32 value) {
    if (fd_radio < 0) {
        return FALSE;
    }

    struct v4l2_control control = {};

    control.value = value;
    control.id = id;

    int32 err = ioctl(fd_radio, VIDIOC_S_CTRL, &control);

    if (err < 0) {
        legacy_log("v4l2", "set control 0x%x=%d failed, err=%d", control.id, value, err);
        return FALSE;
    }

    return TRUE;
}

bool fm_receiver_open() {
    fd_radio = open("/dev/radio0", O_RDWR | O_NONBLOCK);

    legacy_log("v4l2", "opened /dev/radio0 fd=%d", fd_radio);

    if (fd_radio < 0) {
        legacy_log("v4l2", "open /dev/radio0 failed, fd=%d", fd_radio);
        return FALSE;
    }

    return TRUE;
}

bool fm_receiver_set_state(fm_tuner_state tuner_state) {
    return set_v4l2_ctrl(kV4l2CtrlState, tuner_state);
}

bool fm_receiver_set_emphasis(emphasis_t emp_type) {
    return set_v4l2_ctrl(kV4l2CtrlEmphasis, emp_type);
}

bool fm_receiver_set_spacing(channel_space_t spacing) {
    if (!set_v4l2_ctrl(kV4l2CtrlChannelSpacing, spacing)) {
        return FALSE;
    }

    fm_storage.space_type = spacing;
    return TRUE;
}

bool fm_receiver_set_rds_state(bool enable) {
    return set_v4l2_ctrl(kV4l2CtrlRdsOn, enable);
}

bool fm_receiver_set_band(radio_band_t band) {
    band_limit_freq limits = {};
    make_frequency_limit_by_band(band, &limits);

    int ret;

    struct v4l2_tuner tuner;
    tuner.index = 0;
    tuner.signal = 0;
    tuner.rangelow = khz_to_tunefreq(limits.lower_limit);
    tuner.rangehigh = khz_to_tunefreq(limits.upper_limit);
    ret = ioctl(fd_radio, VIDIOC_S_TUNER, &tuner);

    if (ret < 0) {
        return FALSE;
    }

    ret = set_v4l2_ctrl(kV4l2CtrlRegion, band);
    if (ret == TRUE) {
        fm_storage.band_type = band;
    }

    return ret == TRUE;
}

bool fm_receiver_set_rds_system(rds_system_t system) {
    return set_v4l2_ctrl(kV4l2CtrlRdsStandard, system);
}

uint8 fm_receiver_get_rds_group_options() {
    struct v4l2_control control;
    control.id = kV4l2CtrlRdsGroupProc;

    uint32 err = ioctl(fd_radio, VIDIOC_G_CTRL, &control);

    if (err < 0) {
        legacy_log("rds", "get group options failed, err=%d", err);
        return 0;
    }

    return (uint8) control.value;
}

/**
 * 0      - nothing
 * 1 << 0 - RT only
 * 1 << 1 - ???
 * 1 << 2 - ???
 * 1 << 3 - ?
 * 1 << 4 - PI, PTY, PS
 * 1 << 5 - PI, PTY, PS
 * 1 << 6 - RT only
 * 1 << 7 - PI, PTY, PS
 * 1 << 8 - ???
 */
bool fm_receiver_set_rds_group_options(uint32 mask) {
    return set_v4l2_ctrl(kV4l2CtrlRdsGroupProc, mask);
}

bool fm_receiver_set_ps_all(uint8 mode) {
    return set_v4l2_ctrl(kV4l2CtrlPsAll, mode);
}

bool fm_receiver_set_antenna(uint8 antenna) {
    return set_v4l2_ctrl(kV4l2CtrlAntenna, antenna);
}

bool fm_receiver_query_capabilities(struct v4l2_capability* cap) {
    uint32 ret = ioctl(fd_radio, VIDIOC_QUERYCAP, cap);
    return ret >= 0; // Zero or positive - success
}

bool fm_receiver_set_tuned_frequency(uint32 frequency_khz) {
    struct v4l2_frequency freq_struct;

    if (fd_radio < 0) {
        return FALSE;
    }

    freq_struct.type = V4L2_TUNER_RADIO;
    freq_struct.frequency = khz_to_tunefreq(frequency_khz);

    int32 err = ioctl(fd_radio, VIDIOC_S_FREQUENCY, &freq_struct);

    if (err < 0) {
        legacy_log("tune", "set frequency %d kHz failed, err=%d", frequency_khz, err);
        return FALSE;
    }

    return TRUE;
}

uint32 fm_receiver_get_tuned_frequency() {
    struct v4l2_frequency freq_struct;

    if (fd_radio < 0) {
        return FALSE;
    }

    freq_struct.type = V4L2_TUNER_RADIO;

    int32 err = ioctl(fd_radio, VIDIOC_G_FREQUENCY, &freq_struct);

    if (err == 0) {
        return tunefreq_to_khz(freq_struct.frequency);
    } else {
        legacy_log("tune", "get frequency failed, err=%d", err);
        return 0;
    }
}

bool fm_receiver_set_mute_mode(mute_t mode) {
    return set_v4l2_ctrl(V4L2_CID_AUDIO_MUTE, mode);
}

/**
 * Enables automatic jump to alternative frequency
 *
 * This method enables automatic seeking to stations which are
 * known ahead of time to be Alternative Frequencies for the
 * currently tuned station. If no alternate frequencies are
 * known, or if the Alternative Frequencies have weaker signal
 * strength than the original frequency, the original frequency
 * will be re-tuned.
 *
 * @return true if successful false otherwise.
 */
bool fm_receiver_toggle_af_jump(uint8 enable) {
    return set_v4l2_ctrl(kV4l2CtrlAfJump, enable);
}

/**
 * Puts the driver into or out of low power mode.
 *
 * This is an synchronous command which can put the FM
 * device and driver into and out of low power mode. Low power mode
 * should be used when the receiver is tuned to a station and only
 * the FM audio is required. The typical scenario for low power mode
 * is when the FM application is no longer visible.
 *
 * While in low power mode, all normal FM and RDS indications from
 * the FM driver will be suppressed. By disabling these indications,
 * low power mode can result in fewer interruptions and this may lead
 * to a power savings.
 *
 * @param mode the new driver operating mode.
 */
bool fm_receiver_set_power_mode(power_mode_t mode) {
    return set_v4l2_ctrl(kV4l2CtrlLowPowerMode, mode);
}

/**
 * Sets the mono/stereo mode of the FM device.
 *
 * This command allows the user to set the mono/stereo mode
 * of the FM device. Using this function, the user can allow
 * mono/stereo mixing or force the reception of mono audio only.
 */
bool fm_receiver_set_stereo_mode(stereo_t mode) {
    if (fd_radio < 0) {
        return FALSE;
    }

    struct v4l2_tuner tuner;
    tuner.index = 0;
    tuner.type = V4L2_TUNER_RADIO;
    tuner.audmode = mode;

    int32 ret = ioctl(fd_radio, VIDIOC_S_TUNER, &tuner);

    if (ret < 0) {
        legacy_log("audio", "set stereo mode failed, ret=%d", ret);
    }

    return ret == 0;
}

bool fm_receiver_search_station_seek(search_t mode, int8 search_dir, uint8 dwell_period) {
    int err;

    bool ret;

    legacy_log("search", "start seek");

    if (fd_radio < 0) {
        return FALSE;
    }

    ret = set_v4l2_ctrl(kV4l2CtrlSearchMode, mode);
    if (ret == FALSE) {
        legacy_log("search", "set seek mode failed");
        return FALSE;
    }

    ret = set_v4l2_ctrl(kV4l2CtrlScanDwell, dwell_period);
    if (ret == FALSE) {
        legacy_log("search", "set scan dwell failed");
        return FALSE;
    }

    struct v4l2_hw_freq_seek hw_seek;
    hw_seek.type = V4L2_TUNER_RADIO;
    hw_seek.seek_upward = search_dir;
    err = ioctl(fd_radio, VIDIOC_S_HW_FREQ_SEEK, &hw_seek);

    if (err < 0) {
        legacy_log("search", "seek failed");
        return FALSE;
    }

    legacy_log("search", "seek started");
    return TRUE;
}

/**
 * Launch search by stations
 */
bool fm_receiver_search_station_list(fm_search_list_stations options) {
    int err;
    bool ret;
    struct v4l2_hw_freq_seek hwseek;

    hwseek.type = V4L2_TUNER_RADIO;
    legacy_log("search", "start station list scan");
    if (fd_radio < 0) {
        return FALSE;
    }

    ret = set_v4l2_ctrl(kV4l2CtrlSearchMode, options.search_mode);
    if (ret == FALSE) {
        legacy_log("search", "set list scan mode failed");
        return FALSE;
    }

    ret = set_v4l2_ctrl(kV4l2CtrlSearchCount, options.srch_list_max);
    if (ret == FALSE) {
        legacy_log("search", "set list scan count failed");
        return FALSE;
    }

    ret = set_v4l2_ctrl(kV4l2CtrlSearchPty, options.program_type);
    if (ret == FALSE) {
        legacy_log("search", "set list scan PTY failed");
        return FALSE;
    }

    hwseek.seek_upward = options.search_dir;
    err = ioctl(fd_radio, VIDIOC_S_HW_FREQ_SEEK, &hwseek);

    if (err < 0) {
        legacy_log("search", "list scan failed");
        return FALSE;
    }

    legacy_log("search", "station list scan started");
    return TRUE;
}

/**
 * Cancel the ongoing search
 */
bool fm_receiver_cancel_search() {
    return set_v4l2_ctrl(kV4l2CtrlSearchOn, 0);
}

/**
 * Close file descriptor
 */
void fm_receiver_close() {
    close(fd_radio);
    fd_radio = -1;
}

/**
 * Reads the fm_radio handle and updates the FM global configuration based on
 * the interrupt data received
 * @return FALSE in failure, TRUE in success
 */
int32 read_data_from_v4l2(uint8 *buf, uint32 buffer_size, int index) {
    if (fd_radio < 0 || buf == NULL || buffer_size == 0) {
        return -1;
    }

    int err;

    struct v4l2_buffer v4l2_buf;
    memset(&v4l2_buf, 0, sizeof(v4l2_buf));

    v4l2_buf.index = index;
    v4l2_buf.type = V4L2_BUF_TYPE_PRIVATE;
    v4l2_buf.memory = V4L2_MEMORY_USERPTR;
    v4l2_buf.m.userptr = (unsigned long) buf;
    v4l2_buf.length = buffer_size;

    err = ioctl(fd_radio, VIDIOC_DQBUF, &v4l2_buf);

    if (err < 0) {
        legacy_log("v4l2", "read buffer 0x%x failed, err=%d", index, err);
        return -1;
    }

    return (int32) v4l2_buf.bytesused;
}

/**
 * Helper routine to read the Program Services data from the V4L2 buffer
 * following a PS event
 * Depends in PS event
 * Updates the Global data strutures PS info entry
 * @return TRUE if success, else FALSE
 */
bool extract_program_service(fm_rds_storage* storage) {
    /*
     * Program Service
     * | buf[0]  | buf[1]  | buf[2]  | buf[3]  | buf[4]  | buf[5]  |
     * |.... ....|.... ....|.... ....|.... ....|.... ....|.... ....|
     * |0000 1111|         |         |         |         |         | num_of_ps (buf[0] & 0x0f)
     * |         |0001 1111|         |         |         |         | program_type (buf[1] & 0x1f)
     * |         |         |1111 1111|1111 1111|         |         | program_id (((buf[2] & 0xff) << 8) | (buf[3] & 0xff))
     * |         |         |         |         |         |1111 1111| program_name (buf[5] with length ps_services_len)
     */
    uint8 buf[64] = {0};

    int32 bytes = read_data_from_v4l2(buf, sizeof(buf), kTavaruaBufPsRds);

    if (bytes < 5) {
        return FALSE;
    }

    RdsTextPayload parsed;
    if (!parse_rds_text_payload(
            buf,
            bytes,
            RdsTextPayloadKind::kProgramService,
            sizeof(storage->program_name) - 1,
            &parsed
    )) {
        return FALSE;
    }

    storage->program_id = parsed.pi;
    storage->program_type = parsed.pty;
    snprintf(storage->program_name, sizeof(storage->program_name), "%s", parsed.text);

    legacy_log("rds", "ps pi=%04x pty=0x%04x name=`%s` (len=%d)", storage->program_id, storage->program_type, storage->program_name, parsed.text_len);

    return TRUE;
}

/**
 * extract_radio_text
 * Helper routine to read the Radio text data from the V4L2 buffer
 * following a RT event
 * @return TRUE if success,else FALSE
 */
bool extract_radio_text(fm_rds_storage* storage) {
    /*
     * Radio text
     * | buf[0]  | buf[1]  | buf[2]  | buf[3]  | buf[4]  | buf[5]  |
     * |.... ....|.... ....|.... ....|.... ....|.... ....|.... ....|
     * |0111 1111|         |         |         |         |         | radio_text_length (buf[0] & 0x7f)
     *?|         |0001 1111|         |         |         |         | program_type (buf[1] & 0x1f)
     *?|         |         |1111 1111|1111 1111|         |         | program_id (((buf[2] & 0xff) << 8) | (buf[3] & 0xff))
     * |         |         |         |         |         |1111 1111| radio_text (buf[5] with length radio_text_length)
     */
    uint8 buf[128] = {0};

    int32 bytes = read_data_from_v4l2(buf, sizeof(buf), kTavaruaBufRtRds);

    if (bytes < 5) {
        return FALSE;
    }

    RdsTextPayload parsed;
    if (!parse_rds_text_payload(
            buf,
            bytes,
            RdsTextPayloadKind::kRadioText,
            sizeof(storage->radio_text) - 1,
            &parsed
    )) {
        return FALSE;
    }

    snprintf(storage->radio_text, sizeof(storage->radio_text), "%s", parsed.text);
    legacy_log("rds", "rt=`%s` (len=%d)", storage->radio_text, parsed.text_len);

    return TRUE;
}

/**
 * Extract alternative frequencies list
 * @param frequencies Array of uint32, with size at least 25
 * @return Count of frequencies
 */
uint8 extract_rds_af_list(uint32* frequencies) {
    uint8 buf[0xff];

    const int32 bytes = read_data_from_v4l2(buf, sizeof(buf), kTavaruaBufAfList);

    if (bytes < 7) {
        return FALSE;
    }

    RdsAfList af;
    if (!parse_rds_af_payload(buf, bytes, &af)) {
        legacy_log("af", "invalid list marker=%d count=%d", buf[4], buf[4] & 0xff);
        return FALSE;
    }

    for (int i = 0; i < af.count; ++i) {
        frequencies[i] = af.frequencies_khz[i];
    }

    legacy_log("af", "parsed %d frequencies", af.count);

    return af.count;
}

/**
 * Extract the list of stations found by hardware search.
 * Search-list entries are absolute 50 kHz channel numbers encoded as
 * big-endian 16-bit values. Example: 0x06df * 50 kHz = 87950 kHz.
 * @return Count of stations
 */
uint8 extract_search_station_list(uint32* list) {
    uint32 station_count;

    // Parts of frequency
    uint8 freq_upper;
    uint8 freq_lower;

    // Frequency in kHz, before rounding to the app's compact 100 kHz format.
    uint32 freq;

    // Temporary buffer
    uint8 buf[100] = {0};

    // Lower/upper limits for computation real frequency
    uint32 lower_limit;
    uint32 upper_limit;

    // Get lower limit from tuner
    struct v4l2_tuner tuner = {};
    tuner.index = 0;
    if (ioctl(fd_radio, VIDIOC_G_TUNER, &tuner) == 0) {
        lower_limit = tunefreq_to_khz(tuner.rangelow);
        upper_limit = tunefreq_to_khz(tuner.rangehigh);
    } else {
        lower_limit = kDefaultLowerFrequencyKhz;
        upper_limit = kDefaultUpperFrequencyKhz;
    }

    if (lower_limit < 65000 || lower_limit > kDefaultUpperFrequencyKhz || upper_limit <= lower_limit || upper_limit > kDefaultUpperFrequencyKhz) {
        legacy_log("search", "invalid limits %d-%d, fallback to %d-%d", lower_limit, upper_limit, kDefaultLowerFrequencyKhz, kDefaultUpperFrequencyKhz);
        lower_limit = kDefaultLowerFrequencyKhz;
        upper_limit = kDefaultUpperFrequencyKhz;
    }

    // Read buffer
    int32 bytes = read_data_from_v4l2(buf, sizeof(buf), kTavaruaBufSearchList);

    if (bytes < 1) {
        legacy_log("search", "read station list failed");
        return 0;
    }

    // First byte is count of found stations
    station_count = (int) buf[0];
    if (station_count > 25) {
        station_count = 25;
    }
    if (station_count > (uint32) ((bytes - 1) / 2)) {
        station_count = (uint32) ((bytes - 1) / 2);
    }

    uint8 valid_count = 0;

    for (int i = 0; i < station_count; i++) {
        freq_upper = buf[i * 2 + 1] & 0xff; // upper part
        freq_lower = buf[i * 2 + 2] & 0xff; // lower part
        const uint16 raw = ((uint16) freq_upper << 8) | freq_lower;
        freq = raw * 50;

        // Search-list entries are absolute 50 kHz channel numbers.
        const uint32 frequency = ((freq + 50) / 100) * 100;
        if (frequency < lower_limit || frequency > upper_limit) {
            legacy_log("search", "skip station[%d]=%d out of range", i, frequency);
            continue;
        }

        list[valid_count] = frequency;
        valid_count++;
    }

    return valid_count;
}
