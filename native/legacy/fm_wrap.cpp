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
#include <string>
#include <utils.h>
#include "../ctl_server.h"
#include "../frequency_format.h"
#include "../rds_parser.h"
#include "utils.h"
#include "fm_ctl.h"
#include "fmcommon.h"
#include "detector.h"
#include "scan_state.h"

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
    TAVARUA_EVT_NEW_ODA,
    TAVARUA_EVT_NEW_RT_PLUS,
    TAVARUA_EVT_NEW_ERT,
};

static char *qsoc_poweron_path = NULL;
constexpr int kLegacyRdsMask = 23;
constexpr int kLegacyRds3aGroupMask = 0x40;

volatile bool is_power_on_completed = FALSE;

static LegacyScanState legacy_scan;
static pthread_mutex_t legacy_scan_lock = PTHREAD_MUTEX_INITIALIZER;

static void record_scan_tune(int frequency_khz) {
    pthread_mutex_lock(&legacy_scan_lock);
    legacy_scan.on_tune(frequency_khz);
    pthread_mutex_unlock(&legacy_scan_lock);
}

static int record_scan_next(int fallback_frequency_khz) {
    pthread_mutex_lock(&legacy_scan_lock);
    const int frequency_khz = legacy_scan.on_scan_next(fallback_frequency_khz);
    pthread_mutex_unlock(&legacy_scan_lock);
    return frequency_khz;
}

static LegacyScanTerminal finish_scan(ScanResult *result) {
    pthread_mutex_lock(&legacy_scan_lock);
    const LegacyScanTerminal terminal = legacy_scan.on_seek_complete(result);
    pthread_mutex_unlock(&legacy_scan_lock);
    return terminal;
}

static void reset_search() {
    pthread_mutex_lock(&legacy_scan_lock);
    legacy_scan.reset();
    pthread_mutex_unlock(&legacy_scan_lock);
}

bool fm_command_search_busy() {
    pthread_mutex_lock(&legacy_scan_lock);
    const bool busy = legacy_scan.busy();
    pthread_mutex_unlock(&legacy_scan_lock);
    return busy;
}

void fm_command_abandon_search() {
    reset_search();
}

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
            break;
        }

        case TAVARUA_EVT_RADIO_DISABLED: {
            legacy_log("event", "FM disabled");
            reset_search();
            fm_storage.state = OFF;
            fm_receiver_close();
            pthread_exit(NULL);
            ret = FALSE;
            break;
        }

        case TAVARUA_EVT_TUNE_SUCC: {
            // Update current frequency
            fm_storage.frequency = fm_receiver_get_tuned_frequency();
            record_scan_tune(static_cast<int>(fm_storage.frequency));

            // Remove last RDS data
            fm_storage.rds.program_name[0] = '\0';
            fm_storage.rds.radio_text[0] = '\0';
            clear_rt_plus_tags();

            legacy_log("event", "tune frequency_khz=%d", fm_storage.frequency);

            // Notify client about tune
            radio_state_patch_t patch = radio_state_patch_empty();
            patch.frequency_khz = fm_storage.frequency;
            patch.ps = patch.rt = patch.pi = patch.country = "";
            patch.af_count = patch.pty = 0;
            send_radio_state_patch(&patch);
            break;
        }

        case TAVARUA_EVT_SEEK_COMPLETE: {
            ScanResult result;
            const LegacyScanTerminal terminal = finish_scan(&result);
            if (terminal == LegacyScanTerminal::kCompleted) {
                const std::string frequencies = format_frequency_list_khz(result.frequencies_khz, result.count);
                legacy_log("search", "scan complete count=%d frequencies_khz=%s", result.count, frequencies.c_str());
                send_search_done(result.frequencies_khz, result.count);
                break;
            }
            if (terminal == LegacyScanTerminal::kCancelled) {
                legacy_log("search", "scan cancel complete");
                break;
            }

            // Some legacy drivers report search completion before switching
            // to the found station. TUNE_SUCC carries the final frequency.
            legacy_log("event", "seek operation complete, waiting for tune event");
            break;
        }

        case TAVARUA_EVT_SCAN_NEXT: {
            const int current_frequency = static_cast<int>(fm_receiver_get_tuned_frequency());
            const int collected_frequency = record_scan_next(current_frequency);
            legacy_log("search", "scan next frequency_khz=%d collected_frequency_khz=%d",
                    current_frequency, collected_frequency);
            break;
        }

        case TAVARUA_EVT_NEW_RAW_RDS:
            legacy_log("event", "raw_rds_available=1");
            extract_raw_rds();
            break;

        case TAVARUA_EVT_NEW_RT_RDS: {
            if (!extract_radio_text(&fm_storage.rds)) {
                legacy_log("rds", "failed to extract RT");
                break;
            }
            radio_state_patch_t patch = radio_state_patch_empty();
            patch.rt = fm_storage.rds.radio_text;
            send_radio_state_patch(&patch);
            break;
        }

        case TAVARUA_EVT_NEW_PS_RDS: {
            if (!extract_program_service(&fm_storage.rds)) {
                legacy_log("rds", "failed to extract PS");
                break;
            }
            radio_state_patch_t patch = radio_state_patch_empty();
            patch.ps = fm_storage.rds.program_name;
            patch.pi = int_to_hex_string(fm_storage.rds.program_id);
            patch.pty = fm_storage.rds.program_type;
            send_radio_state_patch(&patch);
            break;
        }

        case TAVARUA_EVT_ERROR: {
            legacy_log("event", "driver error");
            reset_search();
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
            legacy_log("event", "stereo=1");
            fm_storage.stereo_type = FM_RX_STEREO;
            radio_state_patch_t patch = radio_state_patch_empty();
            patch.stereo = 1;
            send_radio_state_patch(&patch);
            break;
        }

        case TAVARUA_EVT_MONO: {
            legacy_log("event", "stereo=0");
            fm_storage.stereo_type = FM_RX_MONO;
            radio_state_patch_t patch = radio_state_patch_empty();
            patch.stereo = 0;
            send_radio_state_patch(&patch);
            break;
        }

        case TAVARUA_EVT_RDS_AVAIL: {
            legacy_log("event", "rds_available=1");
            // fm_global_params.rds_sync_status = FM_RDS_SYNCED;
            break;
        }

        case TAVARUA_EVT_RDS_NOT_AVAIL: {
            legacy_log("event", "rds_available=0");
            // fm_global_params.rds_sync_status = FM_RDS_NOT_SYNCED;
            break;
        }

        case TAVARUA_EVT_NEW_SRCH_LIST: {
            legacy_log("event", "search_list_available=1");
            uint32 list[25];
            uint8 stations = extract_search_station_list(list);

            const std::string frequencies = format_frequency_list_khz(list, stations);

            legacy_log("search", "unexpected station-list result count=%d frequencies_khz=%s",
                    stations, frequencies.c_str());
            break;
        }

        case TAVARUA_EVT_NEW_AF_LIST: {
            uint32 list[kMaxRdsAfCount];
            uint8 size = extract_rds_af_list(list);

            const std::string frequencies = format_frequency_list_khz(list, size);

            legacy_log("af", "result count=%d frequencies_khz=%s", size, frequencies.c_str());

            int frequencies_khz[kMaxRdsAfCount];
            for (uint8 i = 0; i < size; ++i) {
                frequencies_khz[i] = static_cast<int>(list[i]);
            }
            radio_state_patch_t patch = radio_state_patch_empty();
            patch.af_khz = frequencies_khz;
            patch.af_count = size;
            send_radio_state_patch(&patch);

            break;
        }

        case TAVARUA_EVT_NEW_ODA: {
            legacy_log("event", "oda_available=1");
            fm_receiver_set_rds_group_mask(0);
            break;
        }

        case TAVARUA_EVT_NEW_RT_PLUS: {
            legacy_log("event", "rt_plus_available=1");
            extract_rt_plus();
            break;
        }

        case TAVARUA_EVT_NEW_ERT: {
            legacy_log("event", "ert_available=1");
            extract_ert();
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
        bytes = read_data_from_v4l2(buf, sizeof(buf), kTavaruaBufEvents);

        // If error occurred
        if (bytes < 0) {
            reset_search();
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

bool fm_command_start_scan() {
    pthread_mutex_lock(&legacy_scan_lock);
    const bool started = legacy_scan.start();
    pthread_mutex_unlock(&legacy_scan_lock);
    if (!started) {
        legacy_log("search", "scan rejected because another scan is active");
        return FALSE;
    }

    band_limit_freq limits = {};
    make_frequency_limit_by_band(fm_storage.band_type, &limits);
    legacy_log("search", "scan start frequency_khz=%d", limits.lower_limit);
    if (fm_command_tune_frequency(limits.lower_limit) != FM_CMD_SUCCESS) {
        pthread_mutex_lock(&legacy_scan_lock);
        legacy_scan.start_failed();
        pthread_mutex_unlock(&legacy_scan_lock);
        return FALSE;
    }

    legacy_log("search", "scan requested mode=%d direction=%d dwell=%d", SCAN, 1, 1);
    if (fm_receiver_search_station_seek(SCAN, 1, 1)) {
        return TRUE;
    }

    pthread_mutex_lock(&legacy_scan_lock);
    legacy_scan.start_failed();
    pthread_mutex_unlock(&legacy_scan_lock);
    return FALSE;
}

bool fm_command_start_seek(int8 direction, uint8 dwell_period) {
    pthread_mutex_lock(&legacy_scan_lock);
    const bool started = legacy_scan.start_seek();
    pthread_mutex_unlock(&legacy_scan_lock);
    if (!started) {
        legacy_log("search", "seek rejected because another search is active");
        return FALSE;
    }

    if (fm_receiver_search_station_seek(SEEK, direction, dwell_period)) {
        return TRUE;
    }

    pthread_mutex_lock(&legacy_scan_lock);
    legacy_scan.seek_failed();
    pthread_mutex_unlock(&legacy_scan_lock);
    return FALSE;
}

bool fm_command_cancel_scan() {
    pthread_mutex_lock(&legacy_scan_lock);
    const bool cancelling = legacy_scan.begin_cancel();
    pthread_mutex_unlock(&legacy_scan_lock);

    legacy_log("search", "cancel requested");
    if (fm_receiver_cancel_search()) {
        return TRUE;
    }

    if (cancelling) {
        pthread_mutex_lock(&legacy_scan_lock);
        legacy_scan.cancel_failed();
        pthread_mutex_unlock(&legacy_scan_lock);
    }
    return FALSE;
}


/**
 * Part 2. Check version of driver, set version in system_properties
 * Initiates a soc patch download.
 */
fm_cmd_status_t fm_command_prepare(fm_config_data *config_ptr) {
    uint32 ret;
    char version_str[40] = {'\0'};
    struct v4l2_capability cap = {};

    legacy_log("prep", "reading driver version");

    // Read the driver version
    ret = fm_receiver_query_capabilities(&cap);

    legacy_log("prep", "VIDIOC_QUERYCAP ret=%d version=0x%x", ret, cap.version);

    if (ret == TRUE) {
        legacy_log("prep", "driver version=0x%x", cap.version);

        // Convert the integer to string
        ret = snprintf(version_str, sizeof(version_str), "%d", cap.version);

        if (ret >= sizeof(version_str)) {
            legacy_log("prep", "version string overflow");
            fm_receiver_close();
            return FM_CMD_FAILURE;
        }

#ifdef __ANDROID_API__
        __system_property_set("hw.fm.version", version_str);
#endif

        legacy_log("prep", "hw.fm.version=%s", version_str);

        asprintf(&qsoc_poweron_path, "fm_qsoc_patches %d 0", cap.version);

        if (qsoc_poweron_path != NULL) {
            legacy_log("prep", "qsoc_onpath=%s", qsoc_poweron_path);
        }
    } else {
        legacy_log("prep", "query capabilities failed");
        return FM_CMD_FAILURE;
    }

    legacy_log("prep", "completed");
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
     */

    // Enable RX (Receiver)
    ret = fm_receiver_set_state(RX);

    // If cannot set state, finish
    if (ret == FALSE) {
        legacy_log("setup", "set radio state failed, ret=%d", ret);
        fm_receiver_close();
        return FM_CMD_FAILURE;
    }

    ret = fm_receiver_set_mute_mode(FM_RX_MUTE_BOTH);
    CHECK_EXEC_LAST_COMMAND(__FUNCTION__, "mute receiver during setup");

    // Power level
    // set_v4l2_ctrl(fd_radio, V4L2_CID_TUNE_POWER_LEVEL, 7);

    // FM de-emphasis (50/75 microseconds)
    legacy_log("setup", "emphasis=%d", cfg->emphasis);
    ret = fm_receiver_set_emphasis(cfg->emphasis);
    CHECK_EXEC_LAST_COMMAND(__FUNCTION__, "change emphasis");

    // Spacing (50/100/200kHz)
    legacy_log("setup", "spacing=%d", cfg->spacing);
    ret = fm_receiver_set_spacing(cfg->spacing);
    CHECK_EXEC_LAST_COMMAND(__FUNCTION__, "change channel spacing");

    // Set antenna
    ret = fm_receiver_set_antenna(cfg->antenna);
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
        // Qualcomm's legacy V4L2 path packs RT/PS/AF options into
        // RDSGROUP_PROC. RT+/ERT discovery starts from group 3A below; when
        // the driver discovers an ODA carrier it sends NEW_ODA and expects
        // userspace to set RDSGROUP_MASK again so it can OR in that carrier.
        uint8 rds_group_mask = fm_receiver_get_rds_group_options();
        rds_group_mask &= 0xC7;
        rds_group_mask |= (kLegacyRdsMask & 0x07) << 3;

        legacy_log("rds", "set group options=0x%x", rds_group_mask);
        ret = fm_receiver_set_rds_group_options(rds_group_mask);
        CHECK_EXEC_LAST_COMMAND(__FUNCTION__, "change RDS group options");
    }

    if (system != FM_RX_NO_RDS_SYSTEM) {
        legacy_log("rds", "set raw group mask=0x%x", kLegacyRds3aGroupMask);
        ret = fm_receiver_set_rds_group_mask(kLegacyRds3aGroupMask);
        CHECK_EXEC_LAST_COMMAND(__FUNCTION__, "change raw RDS group mask");

        legacy_log("rds", "set raw data buffer=1");
        ret = fm_receiver_set_rds_data_buffer(1);
        CHECK_EXEC_LAST_COMMAND(__FUNCTION__, "change raw RDS data buffer");

        const uint32 ps_all = (kLegacyRdsMask & (1 << 4)) >> 4;
        legacy_log("rds", "set ps all=0x%x", ps_all);
        ret = fm_receiver_set_ps_all(ps_all);
        CHECK_EXEC_LAST_COMMAND(__FUNCTION__, "change ps all");
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

    legacy_log("disable", "accepted");

    return FM_CMD_SUCCESS;
}

/**
 * Part 4.1. Set frequency (kHz).
 * Tune to specified frequency.
 */
fm_cmd_status_t fm_command_tune_frequency(uint32 frequency) {
    legacy_log("tune", "request frequency_khz=%d", frequency);

    bool ret = fm_receiver_set_tuned_frequency(frequency);

    if (ret == FALSE) {
        legacy_log("tune", "request frequency_khz=%d failed", frequency);
        return FM_CMD_FAILURE;
    }

    legacy_log("tune", "request frequency_khz=%d accepted", frequency);

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
        legacy_log("audio", "set mute=%d accepted", mode);
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
