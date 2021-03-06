#include "fm_ctl.h"
#include "fmcommon.h"
#include "utils.h"
#include <linux/videodev2.h>
#include <string.h>
#include <malloc.h>
#include <fcntl.h>
#ifndef __ANDROID_API__
#    include <sys/ioctl.h>
#endif

#ifndef V4L2_CID_PRIVATE_BASE
#    define V4L2_CID_PRIVATE_BASE                  0x8000000
#endif

#define V4L2_CID_PRIVATE_TAVARUA_SRCHMODE      (V4L2_CID_PRIVATE_BASE + 1)  // 0x8000001
#define V4L2_CID_PRIVATE_TAVARUA_SCANDWELL     (V4L2_CID_PRIVATE_BASE + 2)  // 0x8000002
#define V4L2_CID_PRIVATE_TAVARUA_SRCHON        (V4L2_CID_PRIVATE_BASE + 3)  // 0x8000003
#define V4L2_CID_PRIVATE_TAVARUA_STATE         (V4L2_CID_PRIVATE_BASE + 4)  // 0x8000004
#define V4L2_CID_PRIVATE_TAVARUA_TRANSMIT_MODE (V4L2_CID_PRIVATE_BASE + 5)  // 0x8000005
#define V4L2_CID_PRIVATE_TAVARUA_RDSGROUP_MASK (V4L2_CID_PRIVATE_BASE + 6)  // 0x8000006
#define V4L2_CID_PRIVATE_TAVARUA_REGION        (V4L2_CID_PRIVATE_BASE + 7)  // 0x8000007
#define V4L2_CID_PRIVATE_TAVARUA_SIGNAL_TH     (V4L2_CID_PRIVATE_BASE + 8)  // 0x8000008
#define V4L2_CID_PRIVATE_TAVARUA_SRCH_PTY      (V4L2_CID_PRIVATE_BASE + 9)  // 0x8000009
#define V4L2_CID_PRIVATE_TAVARUA_SRCH_PI       (V4L2_CID_PRIVATE_BASE + 10) // 0x800000a
#define V4L2_CID_PRIVATE_TAVARUA_SRCH_CNT      (V4L2_CID_PRIVATE_BASE + 11) // 0x800000b
#define V4L2_CID_PRIVATE_TAVARUA_EMPHASIS      (V4L2_CID_PRIVATE_BASE + 12) // 0x800000c
#define V4L2_CID_PRIVATE_TAVARUA_RDS_STD       (V4L2_CID_PRIVATE_BASE + 13) // 0x800000d
#define V4L2_CID_PRIVATE_TAVARUA_SPACING       (V4L2_CID_PRIVATE_BASE + 14) // 0x800000e
#define V4L2_CID_PRIVATE_TAVARUA_RDSON         (V4L2_CID_PRIVATE_BASE + 15) // 0x800000f
#define V4L2_CID_PRIVATE_TAVARUA_RDSGROUP_PROC (V4L2_CID_PRIVATE_BASE + 16) // 0x8000010
#define V4L2_CID_PRIVATE_TAVARUA_LP_MODE       (V4L2_CID_PRIVATE_BASE + 17) // 0x8000011
#define V4L2_CID_PRIVATE_TAVARUA_RDSD_BUF      (V4L2_CID_PRIVATE_BASE + 19) // 0x8000013
#define V4L2_CID_PRIVATE_TAVARUA_AF_JUMP       (V4L2_CID_PRIVATE_BASE + 27)
#define V4L2_CID_PRIVATE_TAVARUA_SET_AUDIO_PATH (V4L2_CID_PRIVATE_BASE + 41)
#define V4L2_CID_PRIVATE_RSSI_TH               (V4L2_CID_PRIVATE_BASE + 62)

#define V4L2_CID_PRIVATE_TAVARUA_ANTENNA       (V4L2_CID_PRIVATE_BASE + 18)
#define V4L2_CID_PRIVATE_TAVARUA_PSALL         (V4L2_CID_PRIVATE_BASE + 20)


#define RDS_GROUP_RT  1 << 0
#define RDS_GROUP_PS  1 << 1
#define RDS_GROUP_AF  1 << 2
#define RDS_AF_AUTO   1 << 6
#define RDS_PS_ALL    1 << 4
#define RDS_AF_JUMP   1
#define MAX_TAG_CODES 64

/**
 * V4L2 radio handle
 */
int fd_radio = -1;



/**
 * set_v4l2_ctrl
 * Sets the V4L2 control sent as argument with the requested value and returns the status
 * @return FALSE in failure, TRUE in success
 */
boolean set_v4l2_ctrl(uint32 id, int32 value) {
    if (fd_radio < 0) {
        return FALSE;
    }

    struct v4l2_control control = {};

    control.value = value;
    control.id = id;

    int32 err = ioctl(fd_radio, VIDIOC_S_CTRL, &control);

    if (err < 0) {
        printf("    v4l2_error @@@  failed for control = 0x%x, value = %d, err = %d\n", control.id, value, err);
        return FALSE;
    }

    return TRUE;
}

boolean fm_receiver_open() {
    fd_radio = open("/dev/radio0", O_RDWR | O_NONBLOCK);

    printf("fr_open         : fd_radio = %d\n", fd_radio);

    if (fd_radio < 0) {
        printf("fr_open         : failed to open fd_radio, exit code = %d\n", fd_radio);
        return FALSE;
    }

    return TRUE;
}

boolean fm_receiver_set_state(fm_tuner_state tuner_state) {
    return set_v4l2_ctrl(V4L2_CID_PRIVATE_TAVARUA_STATE, tuner_state);
}

boolean fm_receiver_set_emphasis(emphasis_t emp_type) {
    return set_v4l2_ctrl(V4L2_CID_PRIVATE_TAVARUA_EMPHASIS, emp_type);
}

boolean fm_receiver_set_spacing(channel_space_t spacing) {
    return set_v4l2_ctrl(V4L2_CID_PRIVATE_TAVARUA_SPACING, spacing);
}

boolean fm_receiver_set_rds_state(boolean enable) {
    return set_v4l2_ctrl(V4L2_CID_PRIVATE_TAVARUA_RDSON, enable);
}

boolean fm_receiver_set_band(radio_band_t band) {
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

    ret = set_v4l2_ctrl(V4L2_CID_PRIVATE_TAVARUA_REGION, band);

    return ret == 0;
}

boolean fm_receiver_set_rds_system(rds_system_t system) {
    return set_v4l2_ctrl(V4L2_CID_PRIVATE_TAVARUA_RDS_STD, system);
}

uint8 fm_receiver_get_rds_group_options() {
    struct v4l2_control control;
    control.id = V4L2_CID_PRIVATE_TAVARUA_RDSGROUP_PROC;

    uint32 err = ioctl(fd_radio, VIDIOC_G_CTRL, &control);

    if (err < 0) {
        print2("fr_g_rds_grp_opt: failed to set RDS group err = %d\n", err);
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
boolean fm_receiver_set_rds_group_options(uint32 mask) {
    return set_v4l2_ctrl(V4L2_CID_PRIVATE_TAVARUA_RDSGROUP_PROC, mask);
}

/**
 *
 */
boolean fm_receiver_set_ps_all(uint8 mode) {
    return set_v4l2_ctrl(V4L2_CID_PRIVATE_TAVARUA_PSALL, mode);
}

boolean fm_receiver_set_antenna(uint8 antenna) {
    return set_v4l2_ctrl(V4L2_CID_PRIVATE_TAVARUA_ANTENNA, antenna);
}

boolean fm_receiver_query_capabilities(struct v4l2_capability* cap) {
    uint32 ret = ioctl(fd_radio, VIDIOC_QUERYCAP, cap);
    return ret >= 0; // Zero or positive - success
}

boolean fm_receiver_set_tuned_frequency(uint32 frequency_khz) {
    struct v4l2_frequency freq_struct;

    if (fd_radio < 0) {
        return FALSE;
    }

    freq_struct.type = V4L2_TUNER_RADIO;
    freq_struct.frequency = khz_to_tunefreq(frequency_khz);

    int32 err = ioctl(fd_radio, VIDIOC_S_FREQUENCY, &freq_struct);

    if (err < 0) {
        print2("fr_set_freq     : failed, exit code = %d\n", err);
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
        print2("fr_             : failed to set tuned frequency, err = %d\n", err);
        return 0;
    }
}

boolean fm_receiver_set_mute_mode(mute_t mode) {
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
boolean fm_receiver_toggle_af_jump(uint8 enable) {
    return set_v4l2_ctrl(V4L2_CID_PRIVATE_TAVARUA_AF_JUMP, enable);
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
boolean fm_receiver_set_power_mode(power_mode_t mode) {
    return set_v4l2_ctrl(V4L2_CID_PRIVATE_TAVARUA_LP_MODE, mode);
}

/**
 * Sets the mono/stereo mode of the FM device.
 *
 * This command allows the user to set the mono/stereo mode
 * of the FM device. Using this function, the user can allow
 * mono/stereo mixing or force the reception of mono audio only.
 */
boolean fm_receiver_set_stereo_mode(stereo_t mode) {
    if (fd_radio < 0) {
        return FALSE;
    }

    struct v4l2_tuner tuner;
    tuner.index = 0;
    tuner.type = V4L2_TUNER_RADIO;
    tuner.audmode = mode;

    int32 ret = ioctl(fd_radio, VIDIOC_S_TUNER, &tuner);

    if (ret < 0) {
        print2("fr_s_stereo_mode: failed, ret = %d\n", ret);
    }

    return ret == 0;
}

boolean fm_receiver_search_station_seek(search_t mode, int8 search_dir, uint8 dwell_period) {
    int err;

    boolean ret;

    print("fr_seek_search  : search\n");

    if (fd_radio < 0) {
        return FALSE;
    }

    ret = set_v4l2_ctrl(V4L2_CID_PRIVATE_TAVARUA_SRCHMODE, mode);
    if (ret == FALSE) {
        print("fr_seek_search  : set SRCHMODE failed\n");
        return FALSE;
    }

    ret = set_v4l2_ctrl(V4L2_CID_PRIVATE_TAVARUA_SCANDWELL, dwell_period);
    if (ret == FALSE) {
        print("fr_seek_search  : set SCANDWELL failed\n");
        return FALSE;
    }

    struct v4l2_hw_freq_seek hw_seek;
    hw_seek.type = V4L2_TUNER_RADIO;
    hw_seek.seek_upward = search_dir;
    err = ioctl(fd_radio, VIDIOC_S_HW_FREQ_SEEK, &hw_seek);

    if (err < 0) {
        print("fr_seek_search  : search failed\n");
        return FALSE;
    }

    print("fr_seek_search  : successfully\n");
    return TRUE;
}

/**
 * Launch search by stations
 */
boolean fm_receiver_search_station_list(fm_search_list_stations options) {
    int err;
    boolean ret;
    struct v4l2_hw_freq_seek hwseek;

    hwseek.type = V4L2_TUNER_RADIO;
    print("fm_receiver_search_station_list>\n");
    if (fd_radio < 0) {
        return FALSE;
    }

    ret = set_v4l2_ctrl(V4L2_CID_PRIVATE_TAVARUA_SRCHMODE, options.search_mode);
    if (ret == FALSE) {
        print("fm_receiver_search_station_list 1 failed\n");
        return FALSE;
    }

    ret = set_v4l2_ctrl( V4L2_CID_PRIVATE_TAVARUA_SRCH_CNT, options.srch_list_max);
    if (ret == FALSE) {
        print("fm_receiver_search_station_list 2 failed\n");
        return FALSE;
    }

    ret = set_v4l2_ctrl(V4L2_CID_PRIVATE_TAVARUA_SRCH_PTY, options.program_type);
    if (ret == FALSE) {
        print("fm_receiver_search_station_list 3 failed\n");
        return FALSE;
    }

    hwseek.seek_upward = options.search_dir;
    err = ioctl(fd_radio, VIDIOC_S_HW_FREQ_SEEK, &hwseek);

    if (err < 0) {
        print("fm_receiver_search_station_list 4 failed\n");
        return FALSE;
    }

    print("fm_receiver_search_station_list<\n");
    return TRUE;
}

/**
 * Cancel the ongoing search
 */
boolean fm_receiver_cancel_search() {
    return set_v4l2_ctrl(V4L2_CID_PRIVATE_TAVARUA_SRCHON, 0);
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
uint32 read_data_from_v4l2(const uint8 *buf, int index) {
    if (fd_radio < 0) {
        return 0xffffffff;
    }

    int err;

    struct v4l2_buffer v4l2_buf;
    memset(&v4l2_buf, 0, sizeof(v4l2_buf));

    v4l2_buf.index = index;
    v4l2_buf.type = V4L2_BUF_TYPE_PRIVATE;
    v4l2_buf.memory = V4L2_MEMORY_USERPTR;
    v4l2_buf.m.userptr = (unsigned long) buf;
    v4l2_buf.length = 128;

    err = ioctl(fd_radio, VIDIOC_DQBUF, &v4l2_buf);

    if (err < 0) {
        printf("fr_v4l2_read    : ioctl read 0x%x failed, err = %d\n", index, err);
        return -1;
    }

    return v4l2_buf.bytesused;
}

/**
 * Helper routine to read the Program Services data from the V4L2 buffer
 * following a PS event
 * Depends in PS event
 * Updates the Global data strutures PS info entry
 * @return TRUE if success, else FALSE
 */
boolean extract_program_service(fm_rds_storage* storage) {
    uint8 buf[64] = {0};

    uint32 bytes = read_data_from_v4l2(buf, TAVARUA_BUF_PS_RDS);

    if (bytes < 0) {
        return FALSE;
    }

    /*
     * Program Service
     * | buf[0]  | buf[1]  | buf[2]  | buf[3]  | buf[4]  | buf[5]  |
     * |.... ....|.... ....|.... ....|.... ....|.... ....|.... ....|
     * |0000 1111|         |         |         |         |         | num_of_ps (buf[0] & 0x0f)
     * |         |0001 1111|         |         |         |         | program_type (buf[1] & 0x1f)
     * |         |         |1111 1111|1111 1111|         |         | program_id (((buf[2] & 0xff) << 8) | (buf[3] & 0xff))
     * |         |         |         |         |         |1111 1111| program_name (buf[5] with length ps_services_len)
     */
    int num_of_ps = (int) (buf[0] & 0x0F); // 0-15
    int ps_services_len = num_of_ps * 8; // 0-120

    storage->program_id = ((buf[2] & 0xFF) << 8) | (buf[3] & 0xFF);
    storage->program_type = (int) (buf[1] & 0x1F);
    memset(storage->program_name, 0x0, 96);
    memcpy(storage->program_name, &buf[5], ps_services_len);
    storage->program_name[ps_services_len] = '\0';

    printf("fr_extr_ps      : prgid=%d, pty=%d, ps=`%s`\n", storage->program_id, storage->program_type, storage->program_name);

    return TRUE;
}

/**
 * extract_radio_text
 * Helper routine to read the Radio text data from the V4L2 buffer
 * following a RT event
 * @return TRUE if success,else FALSE
 */
boolean extract_radio_text(fm_rds_storage* storage) {
    uint8 buf[128];

    uint32 bytes = read_data_from_v4l2(buf, TAVARUA_BUF_RT_RDS);

    if (bytes < 0) {
        return FALSE;
    }

    /*
     * Radio text
     * | buf[0]  | buf[1]  | buf[2]  | buf[3]  | buf[4]  | buf[5]  |
     * |.... ....|.... ....|.... ....|.... ....|.... ....|.... ....|
     * |1111 1111|         |         |         |         |         | radio_text_length (buf[0] + 5)
     *?|         |0001 1111|         |         |         |         | program_type (buf[1] & 0x1f)
     *?|         |         |1111 1111|1111 1111|         |         | program_id (((buf[2] & 0xff) << 8) | (buf[3] & 0xff))
     * |         |         |         |         |         |1111 1111| radio_text (buf[5] with length buf[0] + 5)
     */
    uint8 radio_text_length = (int) (buf[0] + 5);

    memset(storage->radio_text, 0x0, 64);
    memcpy(storage->radio_text, &buf[5], radio_text_length);
    printf("fr_extr_rt      : rt=`%s`\n", storage->radio_text);

    return TRUE;
}

/**
 * Extract alternative frequencies list
 * @param frequencies Array of uint32, with size at least 25
 * @return Count of frequencies
 */
uint8 extract_rds_af_list(uint32* frequencies) {
    uint8 buf[0xff];

    const uint32 bytes = read_data_from_v4l2(buf, TAVARUA_BUF_AF_LIST);

    if (bytes < 0) {
        return FALSE;
    }

    // buf[4] | (buf[5] << 8)
    // buf[6] = count of frequencies
    // buf[($index * 4) + 6 + (1...4)] with shift = one frequency (uint32)
    const uint8 af_size = buf[6] & 0xff;

    if (af_size <= 0 || af_size > 25) {
        print3("fr_extr_af_list : AF invalid: %d , %d\n", buf[4], buf[4] & 0xff);
        return FALSE;
    }

    print("fr_extr_af_list : ");

    for (int i = 0; i < af_size; ++i) {
        uint8 shift = 6 + i * 4;

        uint32 freq =
                (buf[shift + 1] & 0xff) |
                ((buf[shift + 2] & 0xff) << 8) |
                ((buf[shift + 3] & 0xff) << 16) |
                ((buf[shift + 4] & 0xff) << 24);

        frequencies[i] = freq;

        print2("%d ", freq);
    }

    print("\n");

    return af_size;
}

/**
 * Extract the list of stations that woa found
 * @return Count of stations
 *
 * Эта хуйня перестала работать в самый последний момент просто ХУЙ ПОЙМИ почему.
 * Точнее, как сказать... Не хуй пойми почему, а потому что lower_limit с какого-то чёрта
 * возвращается нулём. Хотя, возвращаемые значения тоже не похожи на частоты, которые я видел в самом начале.
 * Периодически при установке band'а происходит ошибка. А если она происходит во время инициализации приложения
 * (сервера) то вообще всё вываливается в Segmentation Fault. Короче, ниебацца веселье.
 */
uint8 extract_search_station_list(uint32* list) {
    uint32 station_count;

    // Parts of frequency
    uint8 freq_upper;
    uint8 freq_lower;

    // Offset from lower limit frequency in 50 kHz
    uint32 freq;

    // Temporary buffer
    uint8 buf[100] = {0};

    // Lower limit for computation real frequency
    uint32 lower_limit;

    // Get lower limit from tuner
    struct v4l2_tuner tuner;
    tuner.index = 0;
    ioctl(fd_radio, VIDIOC_G_TUNER, &tuner);
    lower_limit = tunefreq_to_khz(tuner.rangelow);

    printf("fr_extr_srch_stl: lower_limit = %d\n", lower_limit);

    // Read buffer
    uint32 bytes = read_data_from_v4l2(buf, TAVARUA_BUF_SRCH_LIST);

    if (bytes < 0) {
        printf("fr_extr_srch_stl: read failed\n");
        return 0;
    }

    // First byte is count of found stations
    station_count = (int) buf[0];

    printf("fr_extr_srch_stl: found stations = %d\n", station_count);

    /**
     * | buf[1]  | buf[2]  |
     * |.... ....|.... ....|
     * |1111 1111|         | <- upper
     * |         |1111 1111| <- lower
     * |0000 0011|1111 1111| <- freq
     *
     * freq is offset from lower limit - "1" mean 50 kHz (0.05 MHz)
     * For example, if lower limit = 87500 (in kHz):
     * 0 means offset 0 kHz
     * 1 means offset 50 kHz
     * 10 means offset 500 kHz (0.5 MHz)
     */

    for (int i = 0; i < station_count; i++) {
        freq_upper = buf[i * 2 + 1] & 0xff; // upper part
        freq_lower = buf[i * 2 + 2] & 0xff; // lower part
        freq = ((freq_upper & 0x03) << 8) | freq_lower; // make offset from lower limit

        printf("fr_extr_srch_stl: station[%d] = %d\n", i, freq);

        // kHz = lower + (freq * 50), where: lower = lower_limit in kHz; freq = variable from code
        list[i] = lower_limit + (freq * 50);
    }

    return station_count;
}



/**
 * Returns the signal strength of the currently tuned station
 *
 * This method returns the signal strength of the currently
 * tuned station.
 *
 * @return RSSI of currently tuned station
 */
int32 fm_receiver_get_rssi() {
    struct v4l2_tuner tuner;
    tuner.index = 0;
    tuner.signal = 0;

    if (ioctl(fd_radio, VIDIOC_G_TUNER, &tuner) == 0) {
        return tuner.signal;
    } else {
        return -1;
    }
}
