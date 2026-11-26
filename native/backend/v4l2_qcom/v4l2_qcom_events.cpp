#include "v4l2_qcom_backend.h"
#include "../qcom_fm_controls.h"
#include "../qcom_rds_log.h"
#include "../../ctl_server.h"
#include "../../frequency_format.h"
#include "../../hex_dump.h"
#include "../../rds_parser.h"

#include <csignal>
#include <cstring>
#include <errno.h>
#include <linux/videodev2.h>
#include <mutex>
#include <pthread.h>
#include <stdio.h>
#include <string>

namespace {

constexpr int kEventThreadStopMaxAttempts = 100;
constexpr unsigned int kEventThreadStopPollUs = 10000;

// Tavarua V4L2 event numbers emitted by the Qualcomm kernel driver.
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

void on_event_thread_interrupt_signal(int /*signo*/) {
}

void install_event_interrupt_signal_handler() {
    static std::once_flag once;
    std::call_once(once, []() {
        struct sigaction sa = {};
        sa.sa_handler = on_event_thread_interrupt_signal;
        sigemptyset(&sa.sa_mask);
        sa.sa_flags = 0;
        sigaction(SIGUSR1, &sa, nullptr);
    });
}

}  // namespace

// Reads a V4L2 private buffer index via VIDIOC_DQBUF.
int32_t V4l2QcomBackend::read_buffer(uint8_t *buf, uint32_t buffer_size, int index) {
    const int fd = fd_radio_.load();
    if (fd < 0 || buf == nullptr || buffer_size == 0) {
        return -1;
    }

    if (index == kTavaruaBufEvents && !thread_running_) {
        errno = EINTR;
        return -1;
    }

    struct v4l2_buffer v4l2_buf = {
            .index = static_cast<uint32_t>(index),
            .type = V4L2_BUF_TYPE_PRIVATE,
            .memory = V4L2_MEMORY_USERPTR,
            .m = { .userptr = reinterpret_cast<unsigned long>(buf) },
            .length = buffer_size,
    };

    if (v4l2_ioctl(fd, VIDIOC_DQBUF, &v4l2_buf) < 0) {
        const int saved_errno = errno;
        log("v4l2", "read buffer index=0x%x failed, errno=%d (%s)", index, saved_errno, strerror(saved_errno));
        return -1;
    }

    return static_cast<int32_t>(v4l2_buf.bytesused);
}

// Extracts Program Service payload from V4L2 buffer.
bool V4l2QcomBackend::extract_program_service(fm_rds_storage *storage) {
    uint8_t buf[64] = {0};
    int32_t bytes = read_buffer(buf, sizeof(buf), kTavaruaBufPsRds);

    if (bytes < kRdsTextHeaderSize) {
        return false;
    }

    RdsTextPayload parsed;
    if (!parse_rds_text_payload(
            buf,
            bytes,
            RdsTextPayloadKind::kProgramService,
            sizeof(storage->program_name) - 1,
            &parsed
    )) {
        return false;
    }

    storage->program_id = parsed.pi;
    storage->program_type = parsed.pty;
    snprintf(storage->program_name, sizeof(storage->program_name), "%s", parsed.text);

    log_qcom_rds_text(log, "ps", buf, parsed);
    return true;
}

// Extracts Radio Text payload from V4L2 buffer.
bool V4l2QcomBackend::extract_radio_text(fm_rds_storage *storage) {
    uint8_t buf[128] = {0};
    int32_t bytes = read_buffer(buf, sizeof(buf), kTavaruaBufRtRds);

    if (bytes < kRdsTextHeaderSize) {
        return false;
    }

    RdsTextPayload parsed;
    if (!parse_rds_text_payload(
            buf,
            bytes,
            RdsTextPayloadKind::kRadioText,
            sizeof(storage->radio_text) - 1,
            &parsed
    )) {
        return false;
    }

    snprintf(storage->radio_text, sizeof(storage->radio_text), "%s", parsed.text);
    rt_plus_.set_text(parsed);
    log_qcom_rds_text(log, "rt", buf, parsed);

    return true;
}

// Reads raw RDS buffer.
bool V4l2QcomBackend::extract_raw_rds() {
    uint8_t buf[256] = {0};
    const int32_t bytes = read_buffer(buf, sizeof(buf), kTavaruaBufRawRds);

    if (bytes <= 0) {
        log("rds", "raw read failed bytes=%d", bytes);
        return false;
    }

    char dump[3 * 32 + 1];
    format_hex_dump(reinterpret_cast<const char *>(buf), bytes, dump, sizeof(dump));
    log("rds", "raw len=%d data=%s%s", bytes, dump, bytes > 32 ? " ..." : "");

    char country[3] = "";
    if (parse_v4l2_raw_rds_ecc(buf, bytes, country)) {
        log("rds", "ecc country=%s", country);
        radio_state_patch_t patch = radio_state_patch_empty();
        patch.country = country;
        send_radio_state_patch(&patch);
    }

    return true;
}

// Extracts RT+ payload from V4L2 buffer.
bool V4l2QcomBackend::extract_rt_plus() {
    uint8_t buf[32] = {0};
    const int32_t bytes = read_buffer(buf, sizeof(buf), kTavaruaBufRtPlus);

    if (bytes <= 1) {
        log("rds", "rt+ read failed bytes=%d", bytes);
        return false;
    }

    char dump[3 * 32 + 1];
    format_hex_dump(reinterpret_cast<const char *>(buf), bytes, dump, sizeof(dump));
    const int payload_len = buf[0] < bytes ? buf[0] : bytes;
    const int rt_ert_flag = buf[1];
    log("rds", "rt+ len=%d rt_ert=%d data=%s%s", payload_len, rt_ert_flag, dump, bytes > 32 ? " ..." : "");

    RdsRtPlusPayload parsed;

    if (parse_rds_rt_plus_payload(buf, payload_len, RdsRtPlusKind::kV4l2Qcom, &parsed)) {
        if (parsed.ert) {
            log("rds", "rt+ tags belong to eRT (unsupported), ignoring ordinary RT slicing");
            radio_state_patch_t patch = radio_state_patch_empty();
            rt_plus_.clear_tags();
            rt_plus_.fields().write_patch(patch);
            send_radio_state_patch(&patch);
            return true;
        }

        rt_plus_.set_tags(parsed);

        radio_state_patch_t patch = radio_state_patch_empty();
        rt_plus_.fields().write_patch(patch);
        send_radio_state_patch(&patch);
    }

    return true;
}

// Extracts eRT payload from V4L2 buffer.
bool V4l2QcomBackend::extract_ert() {
    uint8_t buf[256] = {0};
    const int32_t bytes = read_buffer(buf, sizeof(buf), kTavaruaBufErt);

    if (bytes <= 3) {
        log("rds", "ert read failed bytes=%d", bytes);
        return false;
    }

    char dump[3 * 32 + 1];
    format_hex_dump(reinterpret_cast<const char *>(buf), bytes, dump, sizeof(dump));
    const int text_len = buf[0] < bytes - 3 ? buf[0] : bytes - 3;
    char text[256];
    snprintf(text, sizeof(text), "%.*s", text_len, buf + 3);
    log("rds", "ert len=%d utf8=%d formatting=%d text=`%s` data=%s%s",
            text_len, buf[1], buf[2], text, dump, bytes > 32 ? " ..." : "");
    return true;
}

// Extracts Alternative Frequencies list from V4L2 buffer.
uint8_t V4l2QcomBackend::extract_rds_af_list(uint32_t *frequencies) {
    uint8_t buf[0xff];
    const int32_t bytes = read_buffer(buf, sizeof(buf), kTavaruaBufAfList);

    if (bytes < 7) {
        return 0;
    }

    RdsAfList af;
    if (!parse_rds_af_payload(buf, bytes, &af)) {
        log("af", "invalid list marker=%d count=%d", buf[4], buf[4] & kRdsByteMask);
        return 0;
    }

    for (int i = 0; i < af.count; ++i) {
        frequencies[i] = af.frequencies_khz[i];
    }

    log("af", "parsed %d frequencies", af.count);
    return static_cast<uint8_t>(af.count);
}

// Extracts search station list from V4L2 buffer.
uint8_t V4l2QcomBackend::extract_search_station_list(uint32_t *list) {
    uint8_t buf[100] = {0};
    uint32_t lower_limit, upper_limit;

    struct v4l2_tuner tuner = {
            .index = 0,
    };

    if (v4l2_ioctl(fd_radio_, VIDIOC_G_TUNER, &tuner) == 0) {
        lower_limit = freq_to_khz(tuner.rangelow);
        upper_limit = freq_to_khz(tuner.rangehigh);
    } else {
        lower_limit = kDefaultLowerFrequencyKhz;
        upper_limit = kDefaultUpperFrequencyKhz;
    }

    if (lower_limit < 65000 || lower_limit > kDefaultUpperFrequencyKhz || upper_limit <= lower_limit || upper_limit > kDefaultUpperFrequencyKhz) {
        log("search", "invalid limits %d-%d, fallback to %d-%d", lower_limit, upper_limit, kDefaultLowerFrequencyKhz, kDefaultUpperFrequencyKhz);
        lower_limit = kDefaultLowerFrequencyKhz;
        upper_limit = kDefaultUpperFrequencyKhz;
    }

    int32_t bytes = read_buffer(buf, sizeof(buf), kTavaruaBufSearchList);

    if (bytes < 1) {
        log("search", "read station list failed");
        return 0;
    }

    RdsSearchList stations;
    if (!parse_rds_search_list_payload(
            buf,
            bytes,
            RdsSearchListKind::kAbsoluteChannels,
            0,
            &stations
    )) {
        log("search", "parse station list failed");
        return 0;
    }

    uint8_t valid_count = 0;

    for (int i = 0; i < stations.count; i++) {
        const uint32_t frequency = stations.frequencies_khz[i];

        if (frequency < lower_limit || frequency > upper_limit) {
            log("search", "skip station[%d]=%d out of range", i, frequency);
            continue;
        }

        list[valid_count] = frequency;
        valid_count++;
    }

    return valid_count;
}

// Stub function for launching event thread.
void *V4l2QcomBackend::event_thread_stub(void *arg) {
    auto *self = static_cast<V4l2QcomBackend *>(arg);
    self->event_thread();
    self->event_thread_running_ = false;
    return nullptr;
}

// Background thread that continuously reads V4L2 events.
void V4l2QcomBackend::event_thread() {
    install_event_interrupt_signal_handler();
    sigset_t unblock_set;
    sigemptyset(&unblock_set);
    sigaddset(&unblock_set, SIGUSR1);
    pthread_sigmask(SIG_UNBLOCK, &unblock_set, nullptr);

    log("event", "interrupt thread started");
    uint8_t buf[128] = {0};

    while (thread_running_) {
        int32_t bytes = read_buffer(buf, sizeof(buf), kTavaruaBufEvents);

        if (bytes < 0) {
            std::lock_guard<std::mutex> lock(state_lock_);
            legacy_scan_.reset();
            break;
        }

        for (int i = 0; i < bytes; i++) {
            const uint8_t event_buf = buf[i];
            bool continue_loop = true;

            switch (event_buf) {
                case TAVARUA_EVT_RADIO_READY: {
                    log("event", "FM enabled");
                    fm_storage_.state = RX;
                    power_on_completed_ = true;
                    break;
                }

                case TAVARUA_EVT_RADIO_DISABLED: {
                    log("event", "FM disabled");
                    {
                        std::lock_guard<std::mutex> lock(state_lock_);
                        legacy_scan_.reset();
                    }
                    enabled_ = false;
                    fm_storage_.state = OFF;
                    power_on_completed_ = false;
                    clear_direct_init_marker();
                    receiver_close();
                    send_native_event("disabled");
                    continue_loop = false;
                    break;
                }

                case TAVARUA_EVT_TUNE_SUCC: {
                    fm_storage_.frequency = receiver_get_tuned_frequency();
                    bool seek_completed = false;
                    {
                        std::lock_guard<std::mutex> lock(state_lock_);
                        seek_completed = legacy_scan_.on_tune(static_cast<int>(fm_storage_.frequency));
                    }

                    fm_storage_.rds.program_name[0] = '\0';
                    fm_storage_.rds.radio_text[0] = '\0';
                    rt_plus_.reset();

                    log("event", "tune frequency_khz=%d", fm_storage_.frequency);

                    radio_state_patch_t patch = make_station_metadata_reset_patch();
                    patch.frequency_khz = fm_storage_.frequency;
                    send_radio_state_patch(&patch);

                    if (seek_completed) {
                        send_native_event("seek_done");
                    }

                    // The tune event is authoritative for the new station. Full scans
                    // remain busy here and use the periodic refresh after completion.
                    if (!command_search_busy()) {
                        command_publish_sinr() || command_publish_rmssi();
                    }
                    break;
                }

                case TAVARUA_EVT_SEEK_COMPLETE: {
                    ScanResult result;
                    V4l2ScanTerminal terminal = V4l2ScanTerminal::kNone;
                    {
                        std::lock_guard<std::mutex> lock(state_lock_);
                        terminal = legacy_scan_.on_seek_complete(&result);
                    }

                    if (terminal == V4l2ScanTerminal::kCompleted) {
                        const std::string frequencies = format_frequency_list_khz(result.frequencies_khz, result.count);
                        log("search", "scan complete count=%d frequencies_khz=%s", result.count, frequencies.c_str());
                        send_search_done(result.frequencies_khz, result.count);
                    } else if (terminal == V4l2ScanTerminal::kCancelled) {
                        log("search", "scan cancel complete");
                    } else {
                        // Some legacy drivers report search completion before switching
                        // to the found station. TUNE_SUCC carries the final frequency.
                        log("event", "seek operation complete, waiting for tune event");
                    }
                    break;
                }

                case TAVARUA_EVT_SCAN_NEXT: {
                    const int current_frequency = static_cast<int>(receiver_get_tuned_frequency());
                    int collected_frequency = 0;
                    int progress_count = 0;
                    {
                        std::lock_guard<std::mutex> lock(state_lock_);
                        const int before_count = legacy_scan_.station_count();
                        collected_frequency = legacy_scan_.on_scan_next(current_frequency);
                        const int after_count = legacy_scan_.station_count();
                        if (after_count > before_count) {
                            progress_count = after_count;
                        }
                    }

                    log("search", "scan next frequency_khz=%d collected_frequency_khz=%d",
                            current_frequency, collected_frequency);
                    if (progress_count > 0) {
                        send_search_progress(progress_count);
                    }
                    break;
                }

                case TAVARUA_EVT_NEW_RAW_RDS: {
                    log("event", "raw_rds_available=1");
                    extract_raw_rds();
                    break;
                }

                case TAVARUA_EVT_NEW_RT_RDS: {
                    if (extract_radio_text(&fm_storage_.rds)) {
                        radio_state_patch_t patch = radio_state_patch_empty();
                        patch.rt = fm_storage_.rds.radio_text;
                        rt_plus_.fields().write_patch(patch);
                        send_radio_state_patch(&patch);
                    } else {
                        log("rds", "failed to extract RT");
                    }
                    break;
                }

                case TAVARUA_EVT_NEW_PS_RDS: {
                    if (extract_program_service(&fm_storage_.rds)) {
                        char pi_str[16];
                        snprintf(pi_str, sizeof(pi_str), "%x", fm_storage_.rds.program_id);

                        radio_state_patch_t patch = radio_state_patch_empty();
                        patch.ps = fm_storage_.rds.program_name;
                        patch.pi = pi_str;
                        patch.pty = fm_storage_.rds.program_type;
                        send_radio_state_patch(&patch);
                    } else {
                        log("rds", "failed to extract PS");
                    }
                    break;
                }

                case TAVARUA_EVT_ERROR: {
                    log("event", "driver error");
                    std::lock_guard<std::mutex> lock(state_lock_);
                    legacy_scan_.reset();
                    break;
                }

                case TAVARUA_EVT_BELOW_TH: {
                    log("event", "signal below threshold");
                    fm_storage_.avail = FM_SERVICE_NOT_AVAILABLE;
                    break;
                }

                case TAVARUA_EVT_ABOVE_TH: {
                    log("event", "signal above threshold");
                    fm_storage_.avail = FM_SERVICE_AVAILABLE;
                    break;
                }

                case TAVARUA_EVT_STEREO: {
                    log("event", "stereo=1");
                    fm_storage_.stereo_type = FM_RX_STEREO;
                    radio_state_patch_t patch = radio_state_patch_empty();
                    patch.stereo = 1;
                    send_radio_state_patch(&patch);
                    break;
                }

                case TAVARUA_EVT_MONO: {
                    log("event", "stereo=0");
                    fm_storage_.stereo_type = FM_RX_MONO;
                    radio_state_patch_t patch = radio_state_patch_empty();
                    patch.stereo = 0;
                    send_radio_state_patch(&patch);
                    break;
                }

                case TAVARUA_EVT_NEW_SRCH_LIST: {
                    log("event", "search_list_available=1");
                    uint32_t list[25];
                    uint8_t stations = extract_search_station_list(list);
                    const std::string frequencies = format_frequency_list_khz(list, stations);

                    log("search", "unexpected station-list result count=%d frequencies_khz=%s",
                            stations, frequencies.c_str());
                    break;
                }

                case TAVARUA_EVT_NEW_AF_LIST: {
                    uint32_t list[kMaxRdsAfCount];
                    uint8_t size = extract_rds_af_list(list);
                    const std::string frequencies = format_frequency_list_khz(list, size);

                    log("af", "result count=%d frequencies_khz=%s", size, frequencies.c_str());

                    int frequencies_khz[kMaxRdsAfCount];
                    for (uint8_t j = 0; j < size; ++j) {
                        frequencies_khz[j] = static_cast<int>(list[j]);
                    }

                    radio_state_patch_t patch = radio_state_patch_empty();
                    patch.af_khz = frequencies_khz;
                    patch.af_count = size;
                    send_radio_state_patch(&patch);
                    break;
                }

                case TAVARUA_EVT_RDS_AVAIL: {
                    log("event", "rds_sync=1");
                    break;
                }

                case TAVARUA_EVT_RDS_NOT_AVAIL: {
                    log("event", "rds_sync=0");
                    break;
                }

                case TAVARUA_EVT_NEW_ODA: {
                    log("event", "oda_available=1");
                    set_v4l2_ctrl(kV4l2CtrlRdsGroupMask, kV4l2QcomRds1aGroupMask);
                    break;
                }

                case TAVARUA_EVT_NEW_RT_PLUS: {
                    log("event", "rt_plus_available=1");
                    extract_rt_plus();
                    break;
                }

                case TAVARUA_EVT_NEW_ERT: {
                    log("event", "ert_available=1");
                    extract_ert();
                    break;
                }

                default: {
                    log("event", "unknown event=%d", event_buf);
                    break;
                }
            }

            if (!continue_loop) {
                thread_running_ = false;
                break;
            }
        }
    }

    receiver_close();
    log("event", "interrupt thread exited");
}

// Interrupts an in-flight event read on the event thread.
void V4l2QcomBackend::interrupt_event_read() {
    if (event_thread_started_ && !pthread_equal(pthread_self(), q_event_thread_)) {
        install_event_interrupt_signal_handler();
        pthread_kill(q_event_thread_, SIGUSR1);
    }
}

// Signals the event thread to exit, waits within a bound, and joins it.
bool V4l2QcomBackend::stop_event_thread() {
    power_on_completed_ = false;
    thread_running_ = false;

    if (!event_thread_started_) {
        receiver_close();
        return true;
    }

    if (pthread_equal(pthread_self(), q_event_thread_)) {
        return false;
    }

    for (int attempt = 0; attempt < kEventThreadStopMaxAttempts && event_thread_running_; ++attempt) {
        interrupt_event_read();
        sleep_us(kEventThreadStopPollUs);
    }

    if (event_thread_running_) {
        log("event", "timed out waiting for interrupt thread to exit");
        return false;
    }

    pthread_join(q_event_thread_, nullptr);
    event_thread_started_ = false;
    receiver_close();
    return true;
}
