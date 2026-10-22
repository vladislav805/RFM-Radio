#include <errno.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <string>

#include "ctl_json.h"
#include "ctl_protocol.h"
#include "ctl_server.h"
#include "monotonic_clock.h"
#include "periodic_deadline.h"

namespace {

pthread_mutex_t g_state_lock = PTHREAD_MUTEX_INITIALIZER;
pthread_mutex_t g_output_lock = PTHREAD_MUTEX_INITIALIZER;
RadioStateJsonCache g_last_state;
volatile sig_atomic_t g_stop_requested = 0;

constexpr int kServerLogIn = 0;
constexpr int kServerLogOut = 1;

constexpr uint64_t kRmssiPollIntervalMs = 2000;
constexpr size_t kMaxPendingInputBytes = 4096;

void server_log(const char *scope, const int type, const char *fmt, ...) {
    fprintf(stderr, "srv/%-7s%s ", scope, type == kServerLogIn ? ">" : "<");

    va_list args;
    va_start(args, fmt);
    vfprintf(stderr, fmt, args);
    va_end(args);

    fprintf(stderr, "\n");
}

void stop_signal_handler(int /*signal*/) {
    g_stop_requested = 1;
}

void configure_process_lifecycle() {
    struct sigaction stop_action = {};
    stop_action.sa_handler = stop_signal_handler;
    sigemptyset(&stop_action.sa_mask);
    sigaction(SIGTERM, &stop_action, nullptr);
    sigaction(SIGHUP, &stop_action, nullptr);

    struct sigaction pipe_action = {};
    pipe_action.sa_handler = SIG_IGN;
    sigemptyset(&pipe_action.sa_mask);
    sigaction(SIGPIPE, &pipe_action, nullptr);

}

bool write_protocol_message(const std::string &json) {
    if (json.empty()) {
        return false;
    }

    const std::string framed = json + '\n';
    pthread_mutex_lock(&g_output_lock);

    size_t written = 0;
    while (written < framed.size()) {
        const ssize_t result = write(STDOUT_FILENO, framed.data() + written, framed.size() - written);
        if (result > 0) {
            written += static_cast<size_t>(result);
            continue;
        }
        if (result < 0 && errno == EINTR) {
            continue;
        }

        pthread_mutex_unlock(&g_output_lock);
        return false;
    }

    pthread_mutex_unlock(&g_output_lock);
    return true;
}

bool send_json_message(const std::string &json) {
    server_log("send", kServerLogOut, "%s", json.c_str());
    return write_protocol_message(json);
}

} // namespace

int init_server(fm_srv_callback request_callback) {
    if (request_callback == nullptr) {
        return -1;
    }

    configure_process_lifecycle();
    if (g_stop_requested || !write_protocol_message(build_ready_json(static_cast<int>(getpid())))) {
        return -2;
    }

    PeriodicDeadline rmssi_deadline(kRmssiPollIntervalMs, monotonic_ms);
    struct pollfd descriptor = {STDIN_FILENO, POLLIN, 0};
    std::string pending_input;

    while (!g_stop_requested) {
        descriptor.revents = 0;

        const int ready = poll(&descriptor, 1, rmssi_deadline.remaining_ms());

        if (ready < 0) {
            if (errno != EINTR) {
                server_log("error", kServerLogIn, "stdin poll failed: %s", strerror(errno));
                break;
            }
            continue;
        }

        if (ready == 0) {
            // A zero result is a timeout, not a socket error.
            poll_rmssi();
            rmssi_deadline.restart();
            continue;
        }

        if ((descriptor.revents & (POLLERR | POLLNVAL)) != 0) {
            server_log("owner", kServerLogIn, "stdin unavailable revents=0x%x", descriptor.revents);
            break;
        }

        if ((descriptor.revents & (POLLIN | POLLHUP)) == 0) {
            continue;
        }

        char input[512];
        const ssize_t count = read(STDIN_FILENO, input, sizeof(input));
        if (count == 0) {
            server_log("owner", kServerLogIn, "stdin closed");
            break;
        }
        if (count < 0) {
            if (errno == EINTR) {
                continue;
            }
            server_log("error", kServerLogIn, "stdin read failed: %s", strerror(errno));
            break;
        }

        pending_input.append(input, static_cast<size_t>(count));
        if (pending_input.size() > kMaxPendingInputBytes) {
            server_log("error", kServerLogIn, "unterminated input exceeds %zu bytes", kMaxPendingInputBytes);
            break;
        }

        size_t newline = std::string::npos;
        while ((newline = pending_input.find('\n')) != std::string::npos) {
            std::string line = pending_input.substr(0, newline);
            pending_input.erase(0, newline + 1);
            if (!line.empty() && line.back() == '\r') {
                line.pop_back();
            }

            ControlRequest request;
            if (!parse_control_request_line(line, &request)) {
                server_log("error", kServerLogIn, "invalid control frame");
                continue;
            }

            server_log("recv", kServerLogIn, "id=%llu command=%s",
                    static_cast<unsigned long long>(request.id), request.command.c_str());
            const bool should_stop = request.command == "shutdown" || request.command == "exit";
            response_t response = request_callback(request.command.data());
            if (!write_protocol_message(build_response_json(request.id, response))) {
                g_stop_requested = 1;
                break;
            }

            if (should_stop) {
                g_stop_requested = 1;
                break;
            }

            if (rmssi_deadline.is_due()) {
                poll_rmssi();
                rmssi_deadline.restart();
            }
        }
    }

    return 0;
}

radio_state_patch_t radio_state_patch_empty(void) {
    radio_state_patch_t patch;

    patch.frequency_khz = RADIO_PATCH_ABSENT_INT;
    patch.ps = patch.rt = patch.pi = patch.country = nullptr;
    patch.pty = RADIO_PATCH_ABSENT_INT;
    patch.af_khz = nullptr;
    patch.af_count = RADIO_PATCH_ABSENT_INT;
    patch.stereo = RADIO_PATCH_ABSENT_INT;
    patch.rmssi = RADIO_PATCH_ABSENT_RMSSI;

    return patch;
}

bool send_radio_state_patch(const radio_state_patch_t *patch) {
    if (patch == nullptr) {
        return false;
    }

    pthread_mutex_lock(&g_state_lock);
    std::string json;
    if (!build_radio_state_patch_json(g_last_state, patch, &json)) {
        pthread_mutex_unlock(&g_state_lock);
        return false;
    }

    const bool ok = send_json_message(json);
    if (ok) {
        apply_radio_state_patch(&g_last_state, patch);
    }

    pthread_mutex_unlock(&g_state_lock);
    return ok;
}

bool send_native_event(const char *type) {
    const std::string json = build_native_event_json(type);
    if (json.empty()) {
        return false;
    }
    return send_json_message(json);
}

bool send_search_done(const int *frequencies_khz, int count) {
    std::string json;
    if (!build_search_done_json(frequencies_khz, count, &json)) {
        return false;
    }
    return send_json_message(json);
}
