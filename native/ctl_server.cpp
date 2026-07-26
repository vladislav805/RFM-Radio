#include <arpa/inet.h>
#include <netinet/in.h>
#include <poll.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#include <string>

#include "ctl_json.h"
#include "ctl_server.h"
#include "monotonic_clock.h"
#include "periodic_deadline.h"

namespace {

pthread_mutex_t g_state_lock = PTHREAD_MUTEX_INITIALIZER;
RadioStateJsonCache g_last_state;

constexpr int kServerLogIn = 0;
constexpr int kServerLogOut = 1;

constexpr uint64_t kRmssiPollIntervalMs = 2000;

void server_log(const char *scope, const int type, const char *fmt, ...) {
    printf("srv/%-7s%s ", scope, type == kServerLogIn ? ">" : "<");

    va_list args;
    va_start(args, fmt);
    vprintf(fmt, args);
    va_end(args);

    printf("\n");
}

bool send_json_message(const std::string &json) {
    server_log("send", kServerLogOut, "%s", json.c_str());

    if (json.size() >= CS_BUF) {
        server_log("error", kServerLogOut, "JSON too large: %zu bytes", json.size());
        return false;
    }

    int sock = socket(AF_INET, SOCK_DGRAM, 0);
    if (sock < 0) {
        return false;
    }

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons(CS_PORT_SRV);
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);

    const int ok = sendto(sock, json.c_str(), json.size(), MSG_CONFIRM, reinterpret_cast<struct sockaddr *>(&addr), sizeof(addr)) >= 0;
    close(sock);
    return ok;
}

} // namespace

int init_server(fm_srv_callback request_callback) {
    int sockfd = socket(AF_INET, SOCK_DGRAM, 0);
    if (sockfd < 0) {
        return -1;
    }

    struct sockaddr_in srv_addr;
    struct sockaddr_in cli_addr;
    memset(&srv_addr, 0, sizeof(srv_addr));
    memset(&cli_addr, 0, sizeof(cli_addr));

    srv_addr.sin_family = AF_INET;
    srv_addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    srv_addr.sin_port = htons(CS_PORT);

    if (bind(sockfd, reinterpret_cast<struct sockaddr *>(&srv_addr), sizeof(srv_addr)) < 0) {
        close(sockfd);
        return -2;
    }

    PeriodicDeadline rmssi_deadline(kRmssiPollIntervalMs, monotonic_ms);

    // pollfd only describes what to wait for; poll() fills revents on each call.
    struct pollfd descriptor = {sockfd, POLLIN, 0};

    while (1) {
        descriptor.revents = 0;

        // Wait until either a UDP command arrives or the next RMSSI sample is due.
        const int ready = poll(&descriptor, 1, rmssi_deadline.remaining_ms());

        if (ready < 0) {
            continue;
        }

        if (ready == 0) {
            // A zero result is a timeout, not a socket error.
            poll_rmssi();
            rmssi_deadline.restart();
            continue;
        }

        // poll() may report socket errors without readable command data.
        if ((descriptor.revents & POLLIN) == 0) {
            continue;
        }

        char buf[CS_BUF];
        memset(buf, 0, sizeof(buf));

        socklen_t cli_len = sizeof(cli_addr);
        ssize_t cmd_len = recvfrom(sockfd, buf, sizeof(buf) - 1, 0, reinterpret_cast<struct sockaddr *>(&cli_addr), &cli_len);
        if (cmd_len < 0) {
            continue;
        }

        buf[cmd_len] = '\0';
        server_log("recv", kServerLogIn, "%s", buf);

        if (strcmp(buf, "exit") == 0) {
            break;
        }

        response_t res = request_callback(buf);
        sendto(sockfd, res.data, strlen(res.data) + 1, 0, reinterpret_cast<struct sockaddr *>(&cli_addr), cli_len);

        // Command handling can cross the deadline because successful responses
        // intentionally wait 300 ms. Sample immediately instead of another 2 s later.
        if (rmssi_deadline.is_due()) {
            poll_rmssi();
            rmssi_deadline.restart();
        }
    }

    close(sockfd);
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
