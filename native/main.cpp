#include <stdlib.h>
#include <unistd.h>

#include <cstdio>
#include <string>
#include <vector>

#include "ctl_server.h"
#include "backend.h"
#include "backend_factory.h"

namespace {

constexpr const char *kResponseOk = "ok";
constexpr const char *kErrNoArg = "ERR_IVD_FRQ";
constexpr const char *kErrUnknown = "ERR_UNK_CMD";
constexpr const char *kErrUnsupported = "ERR_UNSUPPORTED";
constexpr useconds_t kResponseDelayUs = 300000;

Backend *g_backend = nullptr;

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

response_t make_error(const char *message) {
    response_t res = {1, message};
    return res;
}

response_t make_ok(const char *message = kResponseOk) {
    usleep(kResponseDelayUs);
    response_t res = {0, message};
    return res;
}

Backend *ensure_backend() {
    if (g_backend != nullptr) {
        return g_backend;
    }

    g_backend = create_detected_backend();
    return g_backend;
}

response_t make_backend_error(Backend *backend) {
    if (backend == nullptr) {
        return make_error(kErrUnsupported);
    }
    return make_error(backend->last_error()[0] ? backend->last_error() : kErrUnsupported);
}

response_t handle_init() {
    Backend *backend = ensure_backend();
    if (backend == nullptr) {
        return make_error(kErrUnsupported);
    }
    if (!backend->init()) {
        return make_backend_error(backend);
    }
    return make_ok();
}

response_t handle_enable() {
    Backend *backend = ensure_backend();
    if (backend == nullptr || !backend->enable()) {
        return make_backend_error(backend);
    }
    return make_ok();
}

response_t handle_disable() {
    Backend *backend = ensure_backend();
    if (backend == nullptr || !backend->disable()) {
        return make_backend_error(backend);
    }
    return make_ok();
}

response_t handle_setfreq(const std::vector<std::string> &args) {
    if (args.size() < 2) {
        return make_error(kErrNoArg);
    }
    Backend *backend = ensure_backend();
    if (backend == nullptr || !backend->set_frequency(parse_int_arg(args, 1))) {
        return make_backend_error(backend);
    }
    return make_ok();
}

response_t handle_jump(const std::vector<std::string> &args) {
    if (args.size() < 2) {
        return make_error(kErrNoArg);
    }

    static char response[16];
    uint32_t frequency = 0;
    Backend *backend = ensure_backend();
    if (backend == nullptr || !backend->jump(parse_int_arg(args, 1, 1), &frequency)) {
        return make_backend_error(backend);
    }
    snprintf(response, sizeof(response), "%u", frequency);
    return make_ok(response);
}

response_t handle_seek(const std::vector<std::string> &args) {
    if (args.size() < 2) {
        return make_error(kErrNoArg);
    }
    Backend *backend = ensure_backend();
    if (backend == nullptr || !backend->seek(parse_int_arg(args, 1, 1))) {
        return make_backend_error(backend);
    }
    return make_ok();
}

response_t handle_power_mode(const std::vector<std::string> &args) {
    if (args.size() < 2) {
        return make_error(kErrNoArg);
    }
    Backend *backend = ensure_backend();
    if (backend == nullptr || !backend->set_power_mode(args[1] == "low")) {
        return make_backend_error(backend);
    }
    return make_ok();
}

response_t handle_rds_toggle(const std::vector<std::string> &args) {
    if (args.size() < 2) {
        return make_error(kErrNoArg);
    }
    Backend *backend = ensure_backend();
    if (backend == nullptr || !backend->set_rds(parse_int_arg(args, 1) == 1)) {
        return make_backend_error(backend);
    }
    return make_ok();
}

response_t handle_stereo(const std::vector<std::string> &args) {
    if (args.size() < 2) {
        return make_error(kErrNoArg);
    }
    Backend *backend = ensure_backend();
    if (backend == nullptr || !backend->set_stereo(parse_int_arg(args, 1) == 1)) {
        return make_backend_error(backend);
    }
    return make_ok();
}

response_t handle_antenna(const std::vector<std::string> &args) {
    if (args.size() < 2) {
        return make_error(kErrNoArg);
    }
    Backend *backend = ensure_backend();
    if (backend == nullptr || !backend->set_antenna(parse_int_arg(args, 1))) {
        return make_backend_error(backend);
    }
    return make_ok();
}

response_t handle_region(const std::vector<std::string> &args) {
    if (args.size() < 2) {
        return make_error(kErrNoArg);
    }
    Backend *backend = ensure_backend();
    if (backend == nullptr || !backend->set_region(parse_int_arg(args, 1))) {
        return make_backend_error(backend);
    }
    return make_ok();
}

response_t handle_spacing(const std::vector<std::string> &args) {
    if (args.size() < 2) {
        return make_error(kErrNoArg);
    }
    Backend *backend = ensure_backend();
    if (backend == nullptr || !backend->set_spacing(parse_int_arg(args, 1))) {
        return make_backend_error(backend);
    }
    return make_ok();
}

response_t handle_search() {
    Backend *backend = ensure_backend();
    if (backend == nullptr || !backend->search()) {
        return make_backend_error(backend);
    }
    return make_ok();
}

response_t handle_search_cancel() {
    Backend *backend = ensure_backend();
    if (backend == nullptr || !backend->cancel_search()) {
        return make_backend_error(backend);
    }
    return make_ok();
}

response_t handle_auto_af(const std::vector<std::string> &args) {
    if (args.size() < 2) {
        return make_error(kErrNoArg);
    }
    Backend *backend = ensure_backend();
    if (backend == nullptr || !backend->set_auto_af(parse_int_arg(args, 1) == 1)) {
        return make_backend_error(backend);
    }
    return make_ok();
}

response_t handle_slimbus(const std::vector<std::string> &args) {
    if (args.size() < 2) {
        return make_error(kErrNoArg);
    }
    Backend *backend = ensure_backend();
    if (backend == nullptr || !backend->set_slimbus(parse_int_arg(args, 1) == 1)) {
        return make_backend_error(backend);
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

int main() {
    init_server(&api_handler);
    return 0;
}
