#include "fm_wrap.h"
#include <linux/videodev2.h>
#include <fcntl.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <stdlib.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <utils.h>
#include "ctl_server.h"
#include "utils.h"
#include "fm_ctl.h"
#include "fmcommon.h"
#include "detector.h"

// In case, if not defined
#ifndef V4L2_CID_PRIVATE_BASE
#    define V4L2_CID_PRIVATE_BASE                  0x8000000
#endif

enum tavarua_evt_t {
    TAVARUA_EVT_RADIO_READY,
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
    TAVARUA_EVT_RADIO_DISABLED
};

static char *qsoc_poweron_path = NULL;

volatile boolean is_power_on_completed = FALSE;

#define CHECK_EXEC_LAST_COMMAND(X,Y) if (ret == FALSE) {\
    printf("%.*s: failed to set %s, exit code = %d\n", 16, X, Y, ret);\
    return FM_CMD_FAILURE;\
}

// FM asynchronous thread to perform the long running ON
pthread_t fm_interrupt_thread;
pthread_t fm_rssi_thread;

/**
 * Current state
 */
fm_current_storage fm_storage = {
        .frequency = 0,
        .band_type = FM_RX_US_EUROPE,
        .mute_mode = FM_RX_NO_MUTE,
        .space_type = FM_RX_SPACE_100KHZ,
        .stereo_type = FM_RX_STEREO,
        .state = OFF,
        .rssi = 0,
        .rds = {
                .radio_text = "",
                .program_name = "",
                .program_id = 0,
                .program_type = 0,
        },
};


/**
 * process_radio_event
 * Helper routine to process the radio event read from the V4L2 and performs
 * the corresponding action.
 * Depends on Radio event
 * Updates the Global data structures info entry like frequency, station
 * available, RDS sync status etc.
 * @return TRUE if success, else FALSE
 */
boolean process_radio_event(uint8 event_buf) {
    struct v4l2_frequency freq;
    boolean ret = TRUE;

    switch (event_buf) {
        case TAVARUA_EVT_RADIO_READY: {
            printf("fw_proc_event   : FM enabled\n");
            fm_storage.state = RX;
            send_interruption_info(EVT_ENABLED, "enabled");
            break;
        }

        case TAVARUA_EVT_RADIO_DISABLED: {
            printf("fw_proc_event   : FM disabled\n");
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
            strcpy(fm_storage.rds.program_name, "");
            strcpy(fm_storage.rds.radio_text, "");

            fm_storage.rssi = 0;

            printf("fw_proc_event   : tuned to frequency %d\n", fm_storage.frequency);

            // Notify client about tune
            send_interruption_info(EVT_FREQUENCY_SET, int_to_string(fm_storage.frequency));
            send_interruption_info(EVT_UPDATE_PS, "");
            send_interruption_info(EVT_UPDATE_RT, "");
            break;
        }

        case TAVARUA_EVT_SEEK_COMPLETE: {
            fm_storage.frequency = fm_receiver_get_tuned_frequency();

            printf("fw_proc_event   : seek complete to frequency %d\n", fm_storage.frequency);
            send_interruption_info(EVT_SEEK_COMPLETE, int_to_string(fm_storage.frequency));
            break;
        }

        case TAVARUA_EVT_SCAN_NEXT: {
            printf("fw_proc_event   : event scan next, frequency %d\n", fm_receiver_get_tuned_frequency());
            break;
        }

        case TAVARUA_EVT_NEW_RAW_RDS:
            printf("fw_proc_event   : new RAW RDS\n");
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
            send_interruption_info(EVT_UPDATE_PROGRAM_TYPE, int_to_string(fm_storage.rds.program_type));
            break;
        }

        case TAVARUA_EVT_ERROR: {
            print("fw_proc_event   : received error\n");
            break;
        }

        case TAVARUA_EVT_BELOW_TH: {
            print("fw_proc_event   : event below th\n");
            fm_storage.avail = FM_SERVICE_NOT_AVAILABLE;
            break;
        }

        case TAVARUA_EVT_ABOVE_TH: {
            print("fw_proc_event   : event above th\n");
            fm_storage.avail = FM_SERVICE_AVAILABLE;
            break;
        }

        case TAVARUA_EVT_STEREO: {
            print("fw_proc_event   : stereo mode\n");
            fm_storage.stereo_type = FM_RX_STEREO;
            send_interruption_info(EVT_STEREO, "1");
            break;
        }

        case TAVARUA_EVT_MONO: {
            print("fw_proc_event   : mono mode\n");
            fm_storage.stereo_type = FM_RX_MONO;
            send_interruption_info(EVT_STEREO, "0");
            break;
        }

        case TAVARUA_EVT_RDS_AVAIL:
            print("fw_proc_event   : RDS available\n");
            // fm_global_params.rds_sync_status = FM_RDS_SYNCED;
            break;

        case TAVARUA_EVT_RDS_NOT_AVAIL:
            print("fw_proc_event   : RDS not available\n");
            // fm_global_params.rds_sync_status = FM_RDS_NOT_SYNCED;
            break;

        case TAVARUA_EVT_NEW_SRCH_LIST:
            print("fw_proc_event   : received new search list\n");
            // make_search_station_list(fd_radio);
            break;

        case TAVARUA_EVT_NEW_AF_LIST:
            extract_rds_af_list();
            break;

        default: {
            printf("Unknown event RDS: %d\n", event_buf);
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
    print("fw_continue_th  : continue thread started\n");

    // Temporary buffer
    uint8 buf[128] = {0};

    // Status of process event
    boolean status;

    // Index for loop
    int i;

    // Count of bytes,
    uint32 bytes;

    while (1) {
        // Wait and read events
        bytes = read_data_from_v4l2(buf, TAVARUA_BUF_EVENTS);

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

    print("fw_continue_th  : continue thread exited\n");
    return NULL;
}

/**
 * Get signal strength
 * In frontend need compute:
 *   Weakest strength = 139 = -116dB
 *   Strongest strength = 220 = -35dB
 *   To get dB need: -255 + N = -X (dB)
 */
/**
 * Thread for polling signal strength
 */
void *fm_thread_rssi(__attribute__((unused)) void *ptr) {
    print("fw_rssi_th      : start RSSI thread\n");

    int errors = 0;

    while (errors < 100) {
        wait(1000);
        if (send_interruption_info(EVT_UPDATE_RSSI, int_to_string(fm_receiver_get_rssi())) != TRUE) {
            ++errors;
        }
    }

    print("fw_rssi_th      : RSSI thread exited\n");
    return NULL;
}

/**
 * Part 1. Open file descriptor of radio
 * Opens the handle to /dev/radio0 V4L2 device.
 * @return FM command status
 */
fm_cmd_status_t fm_command_open() {
    int exit_code = system("setprop hw.fm.mode normal >/dev/null 2>/dev/null; setprop hw.fm.version 0 >/dev/null 2>/dev/null; setprop ctl.start fm_dl >/dev/null 2>/dev/null");

    print2("fw_cmd_open     : setprop exit code %d\n", exit_code);

    if (file_exists("/system/lib/modules/radio-iris-transport.ko")) {
        print("fw_cmd_open     : found radio-iris-transport.ko, insmod it\n");
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
        print2("fw_cmd_open     : init success after %d attempts\n", attempt + 1);
    } else {
        print2("fw_cmd_open     : init failed after %d attempts, exiting...\n", attempt);
        return FM_CMD_FAILURE;
    }

    wait(500);

    print("fw_cmd_open     : open /dev/radio0...\n");

    boolean ret = fm_receiver_open();

    if (ret == FALSE) {
        print("fw_cmd_open    : failed to open fd_radio\n");
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

    print("fw_cmd_prepare  : read the driver versions...\n");

    // Read the driver version
    ret = fm_receiver_query_capabilities(&cap);

    print3("fw_cmd_prepare  : VIDIOC_QUERYCAP returns: ret=%d; version=0x%x\n", ret, cap.version);

    if (ret == TRUE) {
        print2("fw_cmd_prepare  : driver version (same as chip id): 0x%x\n", cap.version);

        // Convert the integer to string
        ret = snprintf(version_str, sizeof(version_str), "%d", cap.version);

        if (ret >= sizeof(version_str)) {
            print("fw_cmd_prepare  : version check failed\n");
            fm_receiver_close();
            return FM_CMD_FAILURE;
        }

#ifdef __ANDROID_API__
        __system_property_set("hw.fm.version", version_str);
#endif

        print2("fw_cmd_prepare  : hw.fm.version = %s\n", version_str);

        asprintf(&qsoc_poweron_path, "fm_qsoc_patches %d 0", cap.version);

        if (qsoc_poweron_path != NULL) {
            print2("fw_cmd_prepare  : qsoc_onpath = %s\n", qsoc_poweron_path);
        }
    } else {
        print("fw_cmd_prepare  : ioctl failed\n");
        return FM_CMD_FAILURE;
    }

    print("fw_cmd_prepare  : opened receiver successfully\n");
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
            print2("fw_setup_recei  : failed to download patches = %d\n", ret);
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
        print2("fw_setup_recei  : failed to set radio state = %d\n", ret);
        fm_receiver_close();
        return FM_CMD_FAILURE;
    }

    // Power level
    // set_v4l2_ctrl(fd_radio, V4L2_CID_TUNE_POWER_LEVEL, 7);

    // Emphasis (50/75 kHz)
    print2("fw_setup_recei  : emphasis = %d\n", cfg->emphasis);
    ret = fm_receiver_set_emphasis(cfg->emphasis);
    CHECK_EXEC_LAST_COMMAND(__FUNCTION__, "change emphasis");

    // Spacing (50/100/200kHz)
    print2("fw_setup_recei  : spacing = %d\n", cfg->spacing);
    ret = fm_receiver_set_spacing(cfg->spacing);
    CHECK_EXEC_LAST_COMMAND(__FUNCTION__, "change channel spacing");

    // Set band and range frequencies
    ret = fm_receiver_set_band(cfg->band);
    CHECK_EXEC_LAST_COMMAND(__FUNCTION__, "change band and limit frequencies");


    // Set antenna
    ret = fm_receiver_set_antenna(0);
    CHECK_EXEC_LAST_COMMAND(__FUNCTION__, "change antenna");

    // Create threads
    pthread_create(&fm_interrupt_thread, NULL, interrupt_thread, NULL);
    pthread_create(&fm_rssi_thread, NULL, fm_thread_rssi, NULL);

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
    print2("fw_cmd_set_rds  : RDS system = %d\n", system);

    // If RDS enabled
    if (system != FM_RX_NO_RDS_SYSTEM) {

        int32 rds_mask = fm_receiver_get_rds_group_options();

        // TODO ????
        uint8 rds_group_mask = ((rds_mask & 0xC7) & 0x07) << 3;
        // int psAllVal = ;

        print2("fw_cmd_set_rds  : rds_options: %x\n", rds_group_mask);

        ret = fm_receiver_set_rds_group_options(/*rds_group_mask*/ 0xff); // 255 OK
        CHECK_EXEC_LAST_COMMAND(__FUNCTION__, "change RDS group options");
    }

    if (is_rome_chip()) {
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
        ret = fm_receiver_set_ps_all(0x0f); // ???
        if (ret == FALSE) {
            print("fw_cmd_set_rds  : failed to set RDS ps all\n");
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
    print("fw_cmd_disable  : call\n");

    // Wait till the previous ON sequence has completed
    if (is_power_on_completed != TRUE) {
        print("fw_cmd_disable  : already disabled\n");
        return FM_CMD_FAILURE;
    }

    print("fw_cmd_disable  : set state = 0...\n");

    boolean ret = fm_receiver_set_state(OFF);

    if (ret == FALSE) {
        print("fw_cmd_disable  : failed to set fm off");
        return FM_CMD_FAILURE;
    }

#ifdef __ANDROID_API__
    __system_property_set("ctl.stop", "fm_dl");
#endif

    print("fw_cmd_disable  : successfully");

    return FM_CMD_SUCCESS;
}

/**
 * Part 4.1. Set frequency (kHz).
 * Tune to specified frequency.
 */
fm_cmd_status_t fm_command_tune_frequency(uint32 frequency) {
    print2("fw_cmd_set_freq : call with freq = %d\n", frequency);

    boolean ret = fm_receiver_set_tuned_frequency(frequency);

    if (ret == FALSE) {
        print("fw_cmd_set_freq : failed\n");
        return FM_CMD_FAILURE;
    }

    print("fw_cmd_set_freq : successfully\n");

    return FM_CMD_SUCCESS;
}

/**
 * Part 4.2. Set frequency by delta (current = 104000 kHz, delta = 100 kHz, expect = 104100 kHz).
 * @param direction Direction of delta
 */
fm_cmd_status_t fm_command_tune_frequency_by_delta(uint8 direction) {
    // Current
    uint32 current_frequency_khz = fm_receiver_get_tuned_frequency();

    int32 delta;

    switch (fm_storage.space_type) {
        case FM_RX_SPACE_200KHZ: delta = 200; break;
        case FM_RX_SPACE_100KHZ: delta = 100; break;
        case FM_RX_SPACE_50KHZ: delta = 50; break;
    }

    // Required
    uint32 need_frequency_khz = current_frequency_khz + (delta * direction);

    // Change
    return fm_receiver_set_tuned_frequency(need_frequency_khz);
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
        print("fw_cmd_set_mute : successfully\n");
        return FM_CMD_SUCCESS;
    }

    return FM_CMD_FAILURE;
}

fm_cmd_status_t fm_command_set_stereo_mode(stereo_t is_stereo) {
    return fm_receiver_set_stereo_mode(is_stereo);
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
    boolean ret;

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
    boolean ret;
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

/ **
 * Returns a frequency List of the searched stations.
 *
 * This method retrieves the results of the {@link
 * #searchStationList}. This method should be called when the
 * FmRxEvSearchListComplete is invoked.
 *
 * @return An array of integers that corresponds to the frequency of the searched Stations
 * /
fm_cmd_status_t fm_receiver_search_station_list(fm_search_list_stations options) {
    int i, err;
    boolean ret;
    struct v4l2_hw_freq_seek hwseek;

    hwseek.type = V4L2_TUNER_RADIO;
    print("fm_receiver_search_station_list>\n");
    if (fd_radio < 0) {
        return FM_CMD_NO_RESOURCES;
    }

    ret = set_v4l2_ctrl(fd_radio, V4L2_CID_PRIVATE_TAVARUA_SRCHMODE, options.search_mode);
    if (ret == FALSE) {
        print("fm_receiver_search_station_list 1 failed\n");
        return FM_CMD_FAILURE;
    }

    ret = set_v4l2_ctrl(fd_radio, V4L2_CID_PRIVATE_TAVARUA_SRCH_CNT, options.srch_list_max);
    if (ret == FALSE) {
        print("fm_receiver_search_station_list 2 failed\n");
        return FM_CMD_FAILURE;
    }

    ret = set_v4l2_ctrl(fd_radio, V4L2_CID_PRIVATE_TAVARUA_SRCH_PTY, options.program_type);
    if (ret == FALSE) {
        print("fm_receiver_search_station_list 3 failed\n");
        return FM_CMD_FAILURE;
    }

    hwseek.seek_upward = options.search_dir;
    err = ioctl(fd_radio, VIDIOC_S_HW_FREQ_SEEK, &hwseek);

    if (err < 0) {
        print("fm_receiver_search_station_list 4 failed\n");
        return FM_CMD_FAILURE;
    }

    print("fm_receiver_search_station_list<\n");
    return FM_CMD_SUCCESS;
}



/ **
 * PFAL specific routine to cancel the ongoing search operation
 * @return FM command status
 * /
fm_cmd_status_t fm_receiver_cancel_search() {
    struct v4l2_control control;
    boolean ret;

    if (fd_radio < 0) {
        return FM_CMD_NO_RESOURCES;
    }
    ret = set_v4l2_ctrl(fd_radio, V4L2_CID_PRIVATE_TAVARUA_SRCHON, 0);
    if (ret == FALSE) {
        return FM_CMD_FAILURE;
    }
    return FM_CMD_SUCCESS;
}

/ **
 * make_search_station_list
 * Helper routine to extract the list of good stations from the V4L2 buffer
 * @return NONE, If list is non empty then it prints the stations available
 * /
void make_search_station_list(int fd) {
    int freq = 0;
    int i = 0;
    int station_num;
    float real_freq = 0;
    int *stationList;
    uint8 sList[100] = {0};
    int tmpFreqByte1 = 0;
    int tmpFreqByte2 = 0;
    float lowBand;
    struct v4l2_tuner tuner;

    tuner.index = 0;
    ioctl(fd, VIDIOC_G_TUNER, &tuner);
    lowBand = (float) khz_to_tunefreq(tuner.rangelow);

    printf("lowBand %f\n", lowBand);

    if (read_data_from_v4l2(sList, TAVARUA_BUF_SRCH_LIST) < 0) {
        printf("Data read from v4l2 failed\n");
        return;
    }

    station_num = (int) sList[0];
    stationList = (int*) malloc(sizeof(int) * (station_num + 1));
    printf("station_num: %d\n", station_num);

    if (stationList == NULL) {
        printf("make_search_station_list: failed to allocate memory\n");
        return;
    }

    char *res = malloc(sizeof(char) * 4 * station_num);

    for (i = 0; i < station_num; i++) {
        tmpFreqByte1 = sList[i * 2 + 1] & 0xFF;
        tmpFreqByte2 = sList[i * 2 + 2] & 0xFF;
        freq = (tmpFreqByte1 & 0x03) << 8;
        freq |= tmpFreqByte2;
        //printf(" freq: %d\n", freq);
        real_freq = (float) (freq * 0.05) + lowBand;
        //printf(" real_freq: %f\n", real_freq);
        stationList[i] = (int) (real_freq * 1000);
        printf(" make_search_station_list: %d\n", stationList[i]);

        sprintf(res, "%s%04d", res, stationList[i] / 100);
    }

    send_interruption_info(EVT_SEARCH_DONE, res);

    free(stationList);
    free(res);
}*/
