#include "fm_wrap.h"
#include <fcntl.h>
#include <linux/videodev2.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <unistd.h>
#include <utils.h>
#include "../ctl_server.h"
#include "utils.h"
#include "fm_ctl.h"
#include "fmcommon.h"
#include "detector.h"

// In case, if not defined
#ifndef V4L2_CID_PRIVATE_BASE
#    define V4L2_CID_PRIVATE_BASE                  0x8000000
#endif

enum tavarua_evt_t {
    TAVARUA_EVT_RADIO_READY = 0,
    TAVARUA_EVT_TUNE_SUCC,
    TAVARUA_EVT_SEEK_COMPLETE,
    TAVARUA_EVT_SCAN_NEXT,
    TAVARUA_EVT_NEW_RAW_RDS,
    TAVARUA_EVT_NEW_RT_RDS,
    TAVARUA_EVT_NEW_PS_RDS,
    TAVARUA_EVT_ERROR,
    TAVARUA_EVT_BELOW_TH,
    TAVARUA_EVT_ABOVE_TH,
    TAVARUA_EVT_STEREO,
    TAVARUA_EVT_MONO,
    TAVARUA_EVT_RDS_AVAIL,
    TAVARUA_EVT_RDS_NOT_AVAIL,
    TAVARUA_EVT_NEW_SRCH_LIST,
    TAVARUA_EVT_NEW_AF_LIST,
    TAVARUA_EVT_RADIO_DISABLED = 18,
    TAVARUA_EVT_NEW_RT_PLUS = 20,
    TAVARUA_EVT_NEW_ERT,
};

static char *qsoc_poweron_path = NULL;

volatile bool is_power_on_completed = FALSE;

#define CHECK_EXEC_LAST_COMMAND(X,Y) if (ret == FALSE) {\
    legacy_log("cmd", "%.*s failed to set %s, ret=%d", 16, X, Y, ret);\
    return FM_CMD_FAILURE;\
}

// FM asynchronous thread to perform the long running ON
pthread_t fm_interrupt_thread;

/**
 * Current state
 */
fm_current_storage fm_storage = {
        .frequency = 0,
        .mute_mode = FM_RX_NO_MUTE,
        .stereo_type = FM_RX_STEREO,
        .space_type = FM_RX_SPACE_100KHZ,
        .band_type = FM_RX_US_EUROPE,
        .state = OFF,
        .rds = {
                .program_id = 0,
                .program_type = 0,
                .program_name = "",
                .radio_text = "",
        },
};


/**
 * Process the radio event read from the V4L2 and perform the action by Radio event
 * Updates the global state: frequency, station available, RDS, etc.
 */
bool process_radio_event(uint8 event_buf) {
    bool ret = TRUE;

    switch (event_buf) {
        case TAVARUA_EVT_RADIO_READY: {
            legacy_log("event", "FM enabled");
            fm_storage.state = RX;
            send_interruption_info(EVT_ENABLED, "enabled");
            break;
        }

        case TAVARUA_EVT_RADIO_DISABLED: {
            legacy_log("event", "FM disabled");
            fm_storage.state = OFF;
            fm_receiver_close();
            pthread_exit(NULL);
            ret = FALSE;
            break;
        }

        case TAVARUA_EVT_TUNE_SUCC: {
            // Update current frequency
            fm_storage.frequency = fm_receiver_get_tuned_frequency();

            // Remove last RDS data
            fm_storage.rds.program_name[0] = '\0';
            fm_storage.rds.radio_text[0] = '\0';

            legacy_log("event", "tuned frequency=%d", fm_storage.frequency);

            // Notify client about tune
            send_interruption_info(EVT_FREQUENCY_SET, int_to_string(fm_storage.frequency));
            send_interruption_info(EVT_UPDATE_PS, "");
            send_interruption_info(EVT_UPDATE_RT, "");
            send_interruption_info(EVT_UPDATE_PTY, "");
            send_interruption_info(EVT_UPDATE_PI, "");
            break;
        }

        case TAVARUA_EVT_SEEK_COMPLETE: {
            fm_storage.frequency = fm_receiver_get_tuned_frequency();

            legacy_log("event", "seek complete frequency=%d", fm_storage.frequency);
            send_interruption_info(EVT_SEEK_COMPLETE, int_to_string(fm_storage.frequency));
            break;
        }

        case TAVARUA_EVT_SCAN_NEXT: {
            legacy_log("event", "scan next frequency=%d", fm_receiver_get_tuned_frequency());
            break;
        }

        case TAVARUA_EVT_NEW_RAW_RDS:
            legacy_log("event", "raw RDS available");
            // extract_rds_af_list();
            break;

        case TAVARUA_EVT_NEW_RT_RDS: {
            ret = extract_radio_text(&fm_storage.rds);
            send_interruption_info(EVT_UPDATE_RT, fm_storage.rds.radio_text);
            break;
        }

        case TAVARUA_EVT_NEW_PS_RDS: {
            ret = extract_program_service(&fm_storage.rds);
            send_interruption_info(EVT_UPDATE_PS, fm_storage.rds.program_name);
            send_interruption_info(EVT_UPDATE_PI, int_to_hex_string(fm_storage.rds.program_id));
            send_interruption_info(EVT_UPDATE_PTY, int_to_string(fm_storage.rds.program_type));
            break;
        }

        case TAVARUA_EVT_ERROR: {
            legacy_log("event", "driver error");
            break;
        }

        case TAVARUA_EVT_BELOW_TH: {
            legacy_log("event", "signal below threshold");
            fm_storage.avail = FM_SERVICE_NOT_AVAILABLE;
            break;
        }

        case TAVARUA_EVT_ABOVE_TH: {
            legacy_log("event", "signal above threshold");
            fm_storage.avail = FM_SERVICE_AVAILABLE;
            break;
        }

        case TAVARUA_EVT_STEREO: {
            legacy_log("event", "stereo mode");
            fm_storage.stereo_type = FM_RX_STEREO;
            send_interruption_info(EVT_STEREO, "1");
            break;
        }

        case TAVARUA_EVT_MONO: {
            legacy_log("event", "mono mode");
            fm_storage.stereo_type = FM_RX_MONO;
            send_interruption_info(EVT_STEREO, "0");
            break;
        }

        case TAVARUA_EVT_RDS_AVAIL: {
            legacy_log("event", "RDS available");
            // fm_global_params.rds_sync_status = FM_RDS_SYNCED;
            break;
        }

        case TAVARUA_EVT_RDS_NOT_AVAIL: {
            legacy_log("event", "RDS not available");
            // fm_global_params.rds_sync_status = FM_RDS_NOT_SYNCED;
            break;
        }

        case TAVARUA_EVT_NEW_SRCH_LIST: {
            legacy_log("event", "search list available");
            uint32 list[25];
            uint8 stations = extract_search_station_list(list);

            char str[5 * 25 + 1];
            str[0] = '\0';
            char frequencies[7 * 25 + 1];
            frequencies[0] = '\0';

            for (int i = 0; i < stations; ++i) {
                const size_t offset = strlen(str);
                snprintf(str + offset, sizeof(str) - offset, "%04d", list[i] / 100);

                const size_t freq_offset = strlen(frequencies);
                snprintf(frequencies + freq_offset, sizeof(frequencies) - freq_offset, "%s%d", i == 0 ? "" : " ", list[i]);
            }

            legacy_log("search", "result count=%d frequencies=%s", stations, frequencies);

            send_interruption_info(EVT_SEARCH_DONE, str);
            break;
        }

        case TAVARUA_EVT_NEW_AF_LIST: {
            uint32 list[25];
            uint8 size = extract_rds_af_list(list);

            char str[5 * 25 + 1];
            str[0] = '\0';
            char frequencies[7 * 25 + 1];
            frequencies[0] = '\0';

            for (int i = 0; i < size; ++i) {
                const size_t offset = strlen(str);
                snprintf(str + offset, sizeof(str) - offset, "%04d", list[i] / 100);

                const size_t freq_offset = strlen(frequencies);
                snprintf(frequencies + freq_offset, sizeof(frequencies) - freq_offset, "%s%d", i == 0 ? "" : " ", list[i]);
            }

            legacy_log("af", "result count=%d frequencies=%s", size, frequencies);

            send_interruption_info(EVT_UPDATE_AF, str);

            break;
        }

        default: {
            legacy_log("event", "unknown event=%d", event_buf);
        }
    }
    /**
     * This logic is applied to ensure the exit of the Event read thread
     * before the FM Radio control is turned off. This is a temporary fix
     */
    return ret;
}

/**
 * Thread to perform a continuous read on the radio handle for events
 */
void* interrupt_thread(__attribute__((unused)) void* ignore) {
    legacy_log("event", "interrupt thread started");

    // Temporary buffer
    uint8 buf[128] = {0};

    // Status of process event
    bool status;

    // Index for loop
    int i;

    // Count of bytes,
    int32 bytes;

    while (1) {
        // Wait and read events
        bytes = read_data_from_v4l2(buf, sizeof(buf), TAVARUA_BUF_EVENTS);

        // If error occurred
        if (bytes < 0) {
            break;
        }

        // Process events
        for (i = 0; i < bytes; i++) {
            status = process_radio_event(buf[i]);
            if (status != TRUE) {
                goto exit;
            }
        }
    }
exit:

    legacy_log("event", "interrupt thread exited");
    return NULL;
}

/**
 * Part 1. Open file descriptor of radio
 * Opens the handle to /dev/radio0 V4L2 device.
 * @return FM command status
 */
fm_cmd_status_t fm_command_open() {
    int exit_code = system("setprop hw.fm.mode normal >/dev/null 2>/dev/null; setprop hw.fm.version 0 >/dev/null 2>/dev/null; setprop ctl.start fm_dl >/dev/null 2>/dev/null");

    legacy_log("open", "setprop exit=%d", exit_code);

    if (file_exists("/system/lib/modules/radio-iris-transport.ko")) {
        legacy_log("open", "found radio-iris-transport.ko, loading");
        system("insmod /system/lib/modules/radio-iris-transport.ko >/dev/null 2>/dev/null");
    }

    char value[4] = {0x41, 0x42, 0x43, 0x44};

    uint16 attempt;
    int init_success = 0;


    for (attempt = 0; attempt < 600; ++attempt) {
        __system_property_get("hw.fm.init", value);
        if (value[0] == '1') {
            init_success = 1;
            break;
        } else {
            wait(10);
        }
    }

    if (init_success) {
        legacy_log("open", "init success after %d attempts", attempt + 1);
    } else {
        legacy_log("open", "init failed after %d attempts", attempt);
        return FM_CMD_FAILURE;
    }

    wait(500);

    legacy_log("open", "opening /dev/radio0");

    bool ret = fm_receiver_open();

    if (ret == FALSE) {
        legacy_log("open", "failed to open /dev/radio0");
        return FM_CMD_FAILURE;
    }

    wait(700);

    return FM_CMD_SUCCESS;
}


/**
 * Part 2. Check version of driver, set version in system_properties
 * Initiates a soc patch download.
 */
fm_cmd_status_t fm_command_prepare(fm_config_data *config_ptr) {
    uint32 ret;
    char version_str[40] = {'\0'};
    struct v4l2_capability cap = {};

    legacy_log("prepare", "reading driver version");

    // Read the driver version
    ret = fm_receiver_query_capabilities(&cap);

    legacy_log("prepare", "VIDIOC_QUERYCAP ret=%d version=0x%x", ret, cap.version);

    if (ret == TRUE) {
        legacy_log("prepare", "driver version=0x%x", cap.version);

        // Convert the integer to string
        ret = snprintf(version_str, sizeof(version_str), "%d", cap.version);

        if (ret >= sizeof(version_str)) {
            legacy_log("prepare", "version string overflow");
            fm_receiver_close();
            return FM_CMD_FAILURE;
        }

#ifdef __ANDROID_API__
        __system_property_set("hw.fm.version", version_str);
#endif

        legacy_log("prepare", "hw.fm.version=%s", version_str);

        asprintf(&qsoc_poweron_path, "fm_qsoc_patches %d 0", cap.version);

        if (qsoc_poweron_path != NULL) {
            legacy_log("prepare", "qsoc_onpath=%s", qsoc_poweron_path);
        }
    } else {
        legacy_log("prepare", "query capabilities failed");
        return FM_CMD_FAILURE;
    }

    legacy_log("prepare", "receiver opened");
    return fm_command_setup_receiver(config_ptr);
}

/**
 * Part 3. Setup receiver
 * Configure initial parameters like band limit, RDS system, frequency band and radio state
 */
fm_cmd_status_t fm_command_setup_receiver(fm_config_data *ptr) {
    int ret;

    fm_config_data* cfg = (fm_config_data*) ptr;

#ifdef __ANDROID_API__
    if (is_smd_transport_layer()) {
        __system_property_set("ctl.start", "fm_dl");
        sleep(1);
    } else if (!is_rome_chip()) {
        ret = system(qsoc_poweron_path);
        if (ret != 0) {
            legacy_log("setup", "patch download failed, ret=%d", ret);
            return FM_CMD_FAILURE;
        }
    }
#endif

    /**
     * V4L2_CID_PRIVATE_TAVARUA_STATE
     * V4L2_CID_PRIVATE_TAVARUA_EMPHASIS
     * V4L2_CID_PRIVATE_TAVARUA_SPACING
     * V4L2_CID_PRIVATE_TAVARUA_RDS_STD
     * V4L2_CID_PRIVATE_TAVARUA_REGION
     */

    // Enable RX (Receiver)
    ret = fm_receiver_set_state(RX);

    // If cannot set state, finish
    if (ret == FALSE) {
        legacy_log("setup", "set radio state failed, ret=%d", ret);
        fm_receiver_close();
        return FM_CMD_FAILURE;
    }

    // Power level
    // set_v4l2_ctrl(fd_radio, V4L2_CID_TUNE_POWER_LEVEL, 7);

    // Emphasis (50/75 kHz)
    legacy_log("setup", "emphasis=%d", cfg->emphasis);
    ret = fm_receiver_set_emphasis(cfg->emphasis);
    CHECK_EXEC_LAST_COMMAND(__FUNCTION__, "change emphasis");

    // Spacing (50/100/200kHz)
    legacy_log("setup", "spacing=%d", cfg->spacing);
    ret = fm_receiver_set_spacing(cfg->spacing);
    CHECK_EXEC_LAST_COMMAND(__FUNCTION__, "change channel spacing");

    // Set band and range frequencies
    //ret = fm_receiver_set_band(cfg->band);
    //CHECK_EXEC_LAST_COMMAND(__FUNCTION__, "change band and limit frequencies");


    // Set antenna
    ret = fm_receiver_set_antenna(0);
    CHECK_EXEC_LAST_COMMAND(__FUNCTION__, "change antenna");

    // Create threads
    pthread_create(&fm_interrupt_thread, NULL, interrupt_thread, NULL);

    is_power_on_completed = TRUE;
    return FM_CMD_SUCCESS;
}

fm_cmd_status_t fm_command_setup_rds(rds_system_t system) {
    int ret;

    // RDS enable
    ret = fm_receiver_set_rds_state(system != FM_RX_NO_RDS_SYSTEM);
    CHECK_EXEC_LAST_COMMAND(__FUNCTION__, "enable RDS");

    //
    ret = fm_receiver_set_rds_system(system);
    CHECK_EXEC_LAST_COMMAND(__FUNCTION__, "change RDS system");

    // RDS system standard
    legacy_log("rds", "system=%d", system);

    // If RDS enabled
    if (system != FM_RX_NO_RDS_SYSTEM) {

        int32 rds_mask = fm_receiver_get_rds_group_options();

        // TODO ????
        //uint8 rds_group_mask = ((rds_mask & 0xC7) & 0x07) << 3;
        // int psAllVal = ;

        //print2("fw_cmd_set_rds  : rds_options: %x\n", rds_group_mask);

        uint32 mask = 0xffff;

        legacy_log("rds", "set group options=0x%x", mask);
        ret = fm_receiver_set_rds_group_options(/*rds_group_mask*/ mask); // 0xff OK
        CHECK_EXEC_LAST_COMMAND(__FUNCTION__, "change RDS group options");
    }

    if (is_rome_chip()) {
        legacy_log("rds", "using Rome RDS setup");
        /*ret = set_v4l2_ctrl(fd_radio, V4L2_CID_PRIVATE_TAVARUA_RDSGROUP_MASK, 1);
        if (ret == FALSE) {
            print("Failed to set RDS GRP MASK\n");
            return FM_CMD_FAILURE;
        }
        ret = set_v4l2_ctrl(fd_radio, V4L2_CID_PRIVATE_TAVARUA_RDSD_BUF, 1);
        if (ret == FALSE) {
            print("Failed to set RDS BUF\n");
            return FM_CMD_FAILURE;
        }*/
    } else {
        uint32 ps_all = 0xffff;
        legacy_log("rds", "set ps all=0x%x", ps_all);
        ret = fm_receiver_set_ps_all(ps_all); // ???
        if (ret == FALSE) {
            legacy_log("rds", "set ps all failed");
            return FM_CMD_FAILURE;
        }
    }

    return FM_CMD_SUCCESS;
}

/**
 * Part N. Disable radio
 * Close the handle to /dev/radio0 V4L2 device.
 */
fm_cmd_status_t fm_command_disable() {
    legacy_log("disable", "requested");

    // Wait till the previous ON sequence has completed
    if (is_power_on_completed != TRUE) {
        legacy_log("disable", "already disabled");
        return FM_CMD_FAILURE;
    }

    legacy_log("disable", "setting radio state off");

    bool ret = fm_receiver_set_state(OFF);

    if (ret == FALSE) {
        legacy_log("disable", "set radio state off failed");
        return FM_CMD_FAILURE;
    }

#ifdef __ANDROID_API__
    __system_property_set("ctl.stop", "fm_dl");
#endif

    legacy_log("disable", "success");

    return FM_CMD_SUCCESS;
}

/**
 * Part 4.1. Set frequency (kHz).
 * Tune to specified frequency.
 */
fm_cmd_status_t fm_command_tune_frequency(uint32 frequency) {
    legacy_log("tune", "request frequency=%d", frequency);

    bool ret = fm_receiver_set_tuned_frequency(frequency);

    if (ret == FALSE) {
        legacy_log("tune", "request failed");
        return FM_CMD_FAILURE;
    }

    legacy_log("tune", "request sent");

    return FM_CMD_SUCCESS;
}

/**
 * Part 4.2. Set frequency by delta (current = 104000 kHz, delta = 100 kHz, expect = 104100 kHz).
 * @param direction Direction of delta
 */
fm_cmd_status_t fm_command_tune_frequency_by_delta(signed short direction) {
    // Current
    uint32 current_frequency_khz = fm_receiver_get_tuned_frequency();

    int32 delta = 100;

    switch (fm_storage.space_type) {
        case FM_RX_SPACE_200KHZ: delta = 200; break;
        case FM_RX_SPACE_100KHZ: delta = 100; break;
        case FM_RX_SPACE_50KHZ: delta = 50; break;
    }

    // Required
    uint32 need_frequency_khz = current_frequency_khz + (delta * direction);

    // Change
    return fm_receiver_set_tuned_frequency(need_frequency_khz) ? FM_CMD_SUCCESS : FM_CMD_FAILURE;
}

/**
 * Part 4.3. Returns frequency in kHz.
 */
uint32 fm_command_get_tuned_frequency() {
    return fm_receiver_get_tuned_frequency();
}


/**
 * Part 5. Configure mute mode
 * @return FM command status
 */
fm_cmd_status_t fm_command_set_mute_mode(mute_t mode) {
    int ret = fm_receiver_set_mute_mode(mode);

    if (ret == TRUE) {
        legacy_log("audio", "mute mode set");
        return FM_CMD_SUCCESS;
    }

    return FM_CMD_FAILURE;
}

fm_cmd_status_t fm_command_set_stereo_mode(stereo_t is_stereo) {
    return fm_receiver_set_stereo_mode(is_stereo) ? FM_CMD_SUCCESS : FM_CMD_FAILURE;
}


/**
 * fm_receiver_set_rds_options
 * PFAL specific routine to configure the FM receiver's RDS options
 * @return FM command status
 */
/*fm_cmd_status_t fm_receiver_set_rds_options(fm_rds_options options) {
    int ret;
    print("fm_receiver_set_rds_options\n");

    ret = set_v4l2_ctrl(fd_radio, V4L2_CID_PRIVATE_TAVARUA_RDSGROUP_MASK, options.rds_group_mask);

    if (ret == FALSE) {
        print2("fm_receiver_set_rds_options Failed to set RDS group options = %d\n", ret);
        return FM_CMD_FAILURE;
    }

    ret = set_v4l2_ctrl(fd_radio, V4L2_CID_PRIVATE_TAVARUA_RDSD_BUF, options.rds_group_buffer_size);

    if (ret == FALSE) {
        print2("fm_receiver_set_rds_options Failed to set RDS group options = %d\n", ret);
        return FM_CMD_FAILURE;
    }

    // Change Filter not supported
    print("fm_receiver_set_rds_options<\n");

    return FM_CMD_SUCCESS;
}


/ **
 * fm_receiver_set_signal_threshold
 * PFAL specific routine to configure the signal threshold of FM receiver
 * @return FM command status
 * /
fm_cmd_status_t fm_receiver_set_signal_threshold(uint8 threshold) {
    struct v4l2_control control;
    int i, err;
    print2("fm_receiver_set_signal_threshold threshold = %d\n", threshold);
    if (fd_radio < 0) {
        return FM_CMD_NO_RESOURCES;
    }

    control.value = threshold;
    control.id = V4L2_CID_PRIVATE_TAVARUA_SIGNAL_TH;

    for (i = 0; i < 3; i++) {
        err = ioctl(fd_radio, VIDIOC_S_CTRL, &control);
        if (err >= 0) {
            print("fm_receiver_set_signal_threshold Success\n");
            return FM_CMD_SUCCESS;
        }
    }
    return FM_CMD_SUCCESS;
}

/ **
 * fm_receiver_search_stations
 * PFAL specific routine to search for stations from the current frequency of
 * FM receiver and print the information on diag
 * @return FM command status
 * /
fm_cmd_status_t fm_receiver_search_stations(fm_search_stations options) {
    int err, i;
    struct v4l2_control control;
    struct v4l2_hw_freq_seek hwseek;
    bool ret;

    hwseek.type = V4L2_TUNER_RADIO;
    print("fm_receiver_search_stations\n");

    if (fd_radio < 0) {
        return FM_CMD_NO_RESOURCES;
    }

    ret = set_v4l2_ctrl(fd_radio, V4L2_CID_PRIVATE_TAVARUA_SRCHMODE, options.search_mode);
    if (ret == FALSE) {
        print("fm_receiver_search_stations failed \n");
        return FM_CMD_FAILURE;
    }

    ret = set_v4l2_ctrl(fd_radio, V4L2_CID_PRIVATE_TAVARUA_SCANDWELL, options.dwell_period);
    if (ret == FALSE) {
        print("fm_receiver_search_stations failed \n");
        return FM_CMD_FAILURE;
    }

    hwseek.seek_upward = options.search_dir;
    err = ioctl(fd_radio, VIDIOC_S_HW_FREQ_SEEK, &hwseek);

    if (err < 0) {
        print("fm_receiver_search_stations failed \n");
        return FM_CMD_FAILURE;
    }
    print("fm_receiver_search_stations<\n");
    return FM_CMD_SUCCESS;
}


/ **
 * PFAL specific routine to search for stations from the current frequency of
 * FM receiver with a specific program type and print the information on diag
 * @return FM command status
 * /
fm_cmd_status_t fm_receiver_search_rds_stations(fm_search_rds_stations options) {
    int i, err;
    bool ret;
    struct v4l2_control control;
    struct v4l2_hw_freq_seek hwseek;

    hwseek.type = V4L2_TUNER_RADIO;
    print("fm_receiver_search_rds_stations>\n");

    if (fd_radio < 0) {
        return FM_CMD_NO_RESOURCES;
    }

    ret = set_v4l2_ctrl(fd_radio, V4L2_CID_PRIVATE_TAVARUA_SRCHMODE, options.search_mode);
    if (ret == FALSE) {
        print("fm_receiver_search_rds_stations failed\n");
        return FM_CMD_FAILURE;
    }

    ret = set_v4l2_ctrl(fd_radio, V4L2_CID_PRIVATE_TAVARUA_SCANDWELL, options.dwell_period);
    if (ret == FALSE) {
        print("fm_receiver_search_rds_stations failed\n");
        return FM_CMD_FAILURE;
    }

    ret = set_v4l2_ctrl(fd_radio, V4L2_CID_PRIVATE_TAVARUA_SRCH_PTY, options.program_type);
    if (ret == FALSE) {
        print("fm_receiver_search_rds_stations failed\n");
        return FM_CMD_FAILURE;
    }

    ret = set_v4l2_ctrl(fd_radio, V4L2_CID_PRIVATE_TAVARUA_SRCH_PI, options.program_id);
    if (ret == FALSE) {
        print("fm_receiver_search_rds_stations failed\n");
        return FM_CMD_FAILURE;
    }

    hwseek.seek_upward = options.search_dir;
    err = ioctl(fd_radio, VIDIOC_S_HW_FREQ_SEEK, &hwseek);

    if (err < 0) {
        print("fm_receiver_search_rds_stations failed\n");
        return FM_CMD_FAILURE;
    }

    print("fm_receiver_search_rds_stations<\n");
    return FM_CMD_SUCCESS;
}
*/
