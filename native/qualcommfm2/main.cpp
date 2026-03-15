#include <stdio.h>
#include <stdlib.h>
#include <signal.h>
#include <unistd.h>
#if defined(__GLIBC__)
#include <execinfo.h>
#endif

#include <string>
#include <vector>

#include "ctl_server.h"
#include "fm2_backend.h"
#include "fm2_vendor_iface.h"

namespace {

constexpr const char *kResponseOk = "ok";
constexpr const char *kErrNoArg = "ERR_IVD_FRQ";
constexpr const char *kErrUnknown = "ERR_UNK_CMD";
constexpr const char *kErrFailed = "ERR_FAILED";
constexpr useconds_t kResponseDelayUs = 300000;

std::vector<std::string> split_args(const char *request) {
    std::vector<std::string> parts;
    std::string current;
    for (const char *it = request; *it != '\0'; ++it) {
        if (*it == ' ') {
            if (!current.empty()) {
                parts.push_back(current);
                current.clear();
            }
            continue;
        }
        current.push_back(*it);
    }
    if (!current.empty()) {
        parts.push_back(current);
    }
    return parts;
}

int parse_int_arg(const std::vector<std::string> &args, size_t index, int fallback = 0) {
    if (index >= args.size()) {
        return fallback;
    }
    return atoi(args[index].c_str());
}

int parse_int_auto_arg(const std::vector<std::string> &args, size_t index, int fallback = 0) {
    if (index >= args.size()) {
        return fallback;
    }
    return static_cast<int>(strtol(args[index].c_str(), nullptr, 0));
}

response_t make_error(const char *message) {
    FM2_LOGE("returning error response `%s`", message);
    response_t res = {1, message};
    return res;
}

response_t make_ok(const char *message = kResponseOk) {
    usleep(kResponseDelayUs);
    response_t res = {0, message};
    return res;
}

response_t handle_init() {
    if (!fm2_backend_init()) {
        return make_error(fm2_backend_last_error()[0] ? fm2_backend_last_error() : kErrFailed);
    }
    return make_ok();
}

response_t handle_enable() {
    if (!fm2_backend_enable()) {
        return make_error(fm2_backend_last_error()[0] ? fm2_backend_last_error() : kErrFailed);
    }
    if (!fm2_backend_wait_enabled(2000)) {
        return make_error(fm2_backend_last_error()[0] ? fm2_backend_last_error() : kErrFailed);
    }
    return make_ok();
}

response_t handle_disable() {
    if (!fm2_backend_disable()) {
        return make_error(fm2_backend_last_error()[0] ? fm2_backend_last_error() : kErrFailed);
    }
    return make_ok();
}

response_t handle_setfreq(const std::vector<std::string> &args) {
    if (args.size() < 2) {
        return make_error(kErrNoArg);
    }
    if (!fm2_backend_set_frequency(parse_int_arg(args, 1))) {
        return make_error(fm2_backend_last_error()[0] ? fm2_backend_last_error() : kErrFailed);
    }
    return make_ok();
}

response_t handle_jump(const std::vector<std::string> &args) {
    if (args.size() < 2) {
        return make_error(kErrNoArg);
    }

    static char response[16];
    uint32_t frequency = 0;
    if (!fm2_backend_jump(parse_int_arg(args, 1, 1), &frequency)) {
        return make_error(fm2_backend_last_error()[0] ? fm2_backend_last_error() : kErrFailed);
    }
    snprintf(response, sizeof(response), "%u", frequency);
    return make_ok(response);
}

response_t handle_seek(const std::vector<std::string> &args) {
    if (args.size() < 2) {
        return make_error(kErrNoArg);
    }
    if (!fm2_backend_seek(parse_int_arg(args, 1, 1))) {
        return make_error(fm2_backend_last_error()[0] ? fm2_backend_last_error() : kErrFailed);
    }
    return make_ok();
}

response_t handle_power_mode(const std::vector<std::string> &args) {
    if (args.size() < 2) {
        return make_error(kErrNoArg);
    }
    if (!fm2_backend_set_power_mode(args[1] == "low")) {
        return make_error(fm2_backend_last_error()[0] ? fm2_backend_last_error() : kErrFailed);
    }
    return make_ok();
}

response_t handle_rds_toggle(const std::vector<std::string> &args) {
    if (args.size() < 2) {
        return make_error(kErrNoArg);
    }
    if (!fm2_backend_set_rds(parse_int_arg(args, 1) == 1)) {
        return make_error(fm2_backend_last_error()[0] ? fm2_backend_last_error() : kErrFailed);
    }
    return make_ok();
}

response_t handle_stereo(const std::vector<std::string> &args) {
    if (args.size() < 2) {
        return make_error(kErrNoArg);
    }
    if (!fm2_backend_set_stereo(parse_int_arg(args, 1) == 1)) {
        return make_error(fm2_backend_last_error()[0] ? fm2_backend_last_error() : kErrFailed);
    }
    return make_ok();
}

response_t handle_antenna(const std::vector<std::string> &args) {
    if (args.size() < 2) {
        return make_error(kErrNoArg);
    }
    if (!fm2_backend_set_antenna(parse_int_arg(args, 1))) {
        return make_error(fm2_backend_last_error()[0] ? fm2_backend_last_error() : kErrFailed);
    }
    return make_ok();
}

response_t handle_region(const std::vector<std::string> &args) {
    if (args.size() < 2) {
        return make_error(kErrNoArg);
    }
    // if (!fm2_backend_set_region_app_value(parse_int_arg(args, 1))) {
    //     return make_error(fm2_backend_last_error()[0] ? fm2_backend_last_error() : kErrFailed);
    // }
    return make_ok();
}

response_t handle_spacing(const std::vector<std::string> &args) {
    if (args.size() < 2) {
        return make_error(kErrNoArg);
    }
    // if (!fm2_backend_set_spacing_app_value(parse_int_arg(args, 1))) {
    //     return make_error(fm2_backend_last_error()[0] ? fm2_backend_last_error() : kErrFailed);
    // }
    return make_ok();
}

response_t handle_search() {
    if (!fm2_backend_search()) {
        return make_error(fm2_backend_last_error()[0] ? fm2_backend_last_error() : kErrFailed);
    }
    return make_ok();
}

response_t handle_search_cancel() {
    if (!fm2_backend_cancel_search()) {
        return make_error(fm2_backend_last_error()[0] ? fm2_backend_last_error() : kErrFailed);
    }
    return make_ok();
}

response_t handle_auto_af(const std::vector<std::string> &args) {
    if (args.size() < 2) {
        return make_error(kErrNoArg);
    }
    if (!fm2_backend_set_auto_af(parse_int_arg(args, 1) == 1)) {
        return make_error(fm2_backend_last_error()[0] ? fm2_backend_last_error() : kErrFailed);
    }
    return make_ok();
}

response_t handle_slimbus(const std::vector<std::string> &args) {
    if (args.size() < 2) {
        return make_error(kErrNoArg);
    }
    if (!fm2_backend_set_slimbus(parse_int_arg(args, 1) == 1)) {
        return make_error(fm2_backend_last_error()[0] ? fm2_backend_last_error() : kErrFailed);
    }
    return make_ok();
}

}  // namespace

response_t api_handler(char *request) {
    const std::vector<std::string> args = split_args(request);
    if (args.empty()) {
        return make_error(kErrUnknown);
    }

    const std::string &command = args[0];
    if (command == "init") return handle_init();
    if (command == "enable") return handle_enable();
    if (command == "disable") return handle_disable();
    if (command == "setfreq") return handle_setfreq(args);
    if (command == "jump") return handle_jump(args);
    if (command == "seekhw") return handle_seek(args);
    if (command == "power_mode") return handle_power_mode(args);
    if (command == "rds_toggle") return handle_rds_toggle(args);
    if (command == "set_stereo") return handle_stereo(args);
    if (command == "set_antenna") return handle_antenna(args);
    if (command == "set_region") return handle_region(args);
    if (command == "set_spacing") return handle_spacing(args);
    if (command == "searchhw") return handle_search();
    if (command == "search_cancel") return handle_search_cancel();
    if (command == "auto_af") return handle_auto_af(args);
    if (command == "slimbus") return handle_slimbus(args);
    return make_error(kErrUnknown);
}

void crash_signal_handler(int sig) {
    FM2_LOGE("fatal signal received: %d", sig);
#if defined(__GLIBC__)
    void *frames[32];
    int count = backtrace(frames, 32);
    backtrace_symbols_fd(frames, count, fileno(stderr));
    fflush(stderr);
#endif
    _Exit(128 + sig);
}

void install_signal_handlers() {
    signal(SIGSEGV, crash_signal_handler);
    signal(SIGABRT, crash_signal_handler);
    signal(SIGBUS, crash_signal_handler);
    signal(SIGILL, crash_signal_handler);
    signal(SIGFPE, crash_signal_handler);
}

int main() {
    install_signal_handlers();
    FM2_LOGI("FM HAL binary bridge starting");
    init_server(&api_handler);
    FM2_LOGI("FM HAL binary bridge exiting");
    return 0;
}
