#include <stdlib.h>
#include <unistd.h>
#ifdef __ANDROID__
#include <dlfcn.h>
#endif

#include <cstdio>
#include <string>
#include <string_view>
#include <vector>

#include "command_router.h"
#include "backend.h"
#include "startup_config.h"

namespace {

constexpr const char *kResponseOk = "ok";
constexpr const char *kErrNoArg = "ERR_IVD_FRQ";
constexpr const char *kErrInvalidConfig = "ERR_IVD_CFG";
constexpr const char *kErrUnknown = "ERR_UNK_CMD";
constexpr const char *kErrUnsupported = "ERR_UNSUPPORTED";

struct CommandContext {
    std::vector<std::string> args;
    BackendProvider get_backend;
    void (*shutdown_backend)();
};

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
    // response is intentionally delayed to preserve timing expected by the Java/native
    // interaction and avoid races with async backend events
    usleep(kCommandResponseDelayUs);
    response_t res = {0, message};
    return res;
}

response_t make_backend_error(Backend *backend) {
    if (backend == nullptr) {
        return make_error(kErrUnsupported);
    }
    return make_error(backend->last_error()[0] ? backend->last_error() : kErrUnsupported);
}

response_t handle_init(const CommandContext &command) {
    Backend *backend = command.get_backend();
    if (backend == nullptr) {
        return make_error(kErrUnsupported);
    }
    if (!backend->init()) {
        return make_backend_error(backend);
    }
    return make_ok();
}

response_t handle_enable(const CommandContext &command) {
    StartupConfig config;
    std::string error;

    if (!parse_startup_config(command.args, &config, &error)) {
        fprintf(stderr, "srv/enable : invalid config: %s\n", error.c_str());
        return make_error(kErrInvalidConfig);
    }

    Backend *backend = command.get_backend();
    if (backend == nullptr || !backend->enable(config)) {
        return make_backend_error(backend);
    }
    return make_ok();
}

response_t handle_disable(const CommandContext &command) {
    Backend *backend = command.get_backend();
    if (backend == nullptr || !backend->disable()) {
        return make_backend_error(backend);
    }
    return make_ok();
}

response_t handle_shutdown(const CommandContext &command) {
    command.shutdown_backend();
    return make_ok();
}

response_t handle_setfreq(const CommandContext &command) {
    if (command.args.size() < 2) {
        return make_error(kErrNoArg);
    }
    Backend *backend = command.get_backend();
    if (backend == nullptr || !backend->set_frequency(parse_int_arg(command.args, 1))) {
        return make_backend_error(backend);
    }
    return make_ok();
}

response_t handle_jump(const CommandContext &command) {
    if (command.args.size() < 2) {
        return make_error(kErrNoArg);
    }

    static char response[16];
    uint32_t frequency = 0;
    Backend *backend = command.get_backend();
    if (backend == nullptr || !backend->jump(parse_int_arg(command.args, 1, 1), &frequency)) {
        return make_backend_error(backend);
    }
    snprintf(response, sizeof(response), "%u", frequency);
    return make_ok(response);
}

response_t handle_seek(const CommandContext &command) {
    if (command.args.size() < 2) {
        return make_error(kErrNoArg);
    }
    Backend *backend = command.get_backend();
    if (backend == nullptr || !backend->seek(parse_int_arg(command.args, 1, 1))) {
        return make_backend_error(backend);
    }
    return make_ok();
}

response_t handle_power_mode(const CommandContext &command) {
    if (command.args.size() < 2) {
        return make_error(kErrNoArg);
    }
    Backend *backend = command.get_backend();
    if (backend == nullptr || !backend->set_power_mode(command.args[1] == "low")) {
        return make_backend_error(backend);
    }
    return make_ok();
}

response_t handle_stereo(const CommandContext &command) {
    if (command.args.size() < 2) {
        return make_error(kErrNoArg);
    }
    Backend *backend = command.get_backend();
    if (backend == nullptr || !backend->set_stereo(parse_int_arg(command.args, 1) == 1)) {
        return make_backend_error(backend);
    }
    return make_ok();
}

response_t handle_antenna(const CommandContext &command) {
    if (command.args.size() < 2) {
        return make_error(kErrNoArg);
    }
    Backend *backend = command.get_backend();
    if (backend == nullptr || !backend->set_antenna(parse_int_arg(command.args, 1))) {
        return make_backend_error(backend);
    }
    return make_ok();
}

response_t handle_region(const CommandContext &command) {
    if (command.args.size() < 2) {
        return make_error(kErrNoArg);
    }

    StartupRegion region;
    if (!parse_region_name(command.args[1], &region)) {
        return make_error(kErrInvalidConfig);
    }

    Backend *backend = command.get_backend();
    if (backend == nullptr || !backend->set_region(region)) {
        return make_backend_error(backend);
    }
    return make_ok();
}

response_t handle_spacing(const CommandContext &command) {
    if (command.args.size() < 2) {
        return make_error(kErrNoArg);
    }

    int spacing_khz = 0;
    if (!parse_spacing_khz(command.args[1], &spacing_khz)) {
        return make_error(kErrInvalidConfig);
    }

    Backend *backend = command.get_backend();
    if (backend == nullptr || !backend->set_spacing(spacing_khz)) {
        return make_backend_error(backend);
    }
    return make_ok();
}

response_t handle_search(const CommandContext &command) {
    Backend *backend = command.get_backend();
    if (backend == nullptr || !backend->search()) {
        return make_backend_error(backend);
    }
    return make_ok();
}

response_t handle_search_cancel(const CommandContext &command) {
    Backend *backend = command.get_backend();
    if (backend == nullptr || !backend->cancel_search()) {
        return make_backend_error(backend);
    }
    return make_ok();
}

response_t handle_auto_af(const CommandContext &command) {
    if (command.args.size() < 2) {
        return make_error(kErrNoArg);
    }
    Backend *backend = command.get_backend();
    if (backend == nullptr || !backend->set_auto_af(parse_int_arg(command.args, 1) == 1)) {
        return make_backend_error(backend);
    }
    return make_ok();
}

response_t handle_slimbus(const CommandContext &command) {
    if (command.args.size() < 2) {
        return make_error(kErrNoArg);
    }
    Backend *backend = command.get_backend();
    if (backend == nullptr || !backend->set_slimbus(parse_int_arg(command.args, 1) == 1)) {
        return make_backend_error(backend);
    }
    return make_ok();
}

response_t handle_soft_mute(const CommandContext &command) {
    if (command.args.size() != 2 || (command.args[1] != "0" && command.args[1] != "1")) {
        return make_error(kErrInvalidConfig);
    }

    Backend *backend = command.get_backend();
    if (backend == nullptr || !backend->set_soft_mute(command.args[1] == "1")) {
        return make_backend_error(backend);
    }
    return make_ok();
}

response_t handle_notch_filter(const CommandContext &command) {
    if (command.args.size() != 2 || (command.args[1] != "0" && command.args[1] != "1" && command.args[1] != "2")) {
        return make_error(kErrInvalidConfig);
    }

    Backend *backend = command.get_backend();
    if (backend == nullptr || !backend->set_notch_filter(command.args[1][0] - '0')) {
        return make_backend_error(backend);
    }
    return make_ok();
}

response_t handle_volume(const CommandContext &command) {
    if (command.args.size() < 2) {
        return make_error(kErrNoArg);
    }

    Backend *backend = command.get_backend();
    if (backend == nullptr || !backend->set_volume(parse_int_arg(command.args, 1, 15))) {
        return make_backend_error(backend);
    }
    return {0, kResponseOk};
}

/*
 * Routes FM audio between headset and speaker via Android's AudioPolicyService.
 *
 * On Android 11+ (including Samsung OneUI), AudioSystem.setForceUse() from the
 * unprivileged app fails with -1 (MODIFY_AUDIO_ROUTING denied), and IAudioService$Stub
 * does not expose setForceUse because it talks directly to native media.audio_policy.
 * Running inside fmbin (uid 0) bypasses that permission check without spawning su/app_process.
 *
 * The mangled symbol is android::AudioSystem::setForceUse(audio_policy_force_use_t,
 * audio_policy_forced_cfg_t) in libaudioclient.so (Android 8+) or libmedia.so (older).
 */
response_t handle_force_use(const CommandContext &command) {
    if (command.args.size() < 3) {
        return make_error(kErrNoArg);
    }
#ifdef __ANDROID__
    const int usage = parse_int_arg(command.args, 1, 0);
    const int config = parse_int_arg(command.args, 2, 0);
    void *handle = dlopen("libaudioclient.so", RTLD_LAZY);
    if (handle == nullptr) {
        handle = dlopen("libmedia.so", RTLD_LAZY);
    }
    if (handle == nullptr) {
        std::fprintf(stderr, "audio/set_force_use: dlopen failed: %s\n", dlerror());
        return make_error("ERR_FAILED");
    }
    using SetForceUseFn = int (*)(int, int);
    auto *fn = reinterpret_cast<SetForceUseFn>(dlsym(
            handle,
            "_ZN7android11AudioSystem11setForceUseE24audio_policy_force_use_t25audio_policy_forced_cfg_t"
    ));
    if (fn == nullptr) {
        std::fprintf(stderr, "audio/set_force_use: dlsym failed: %s\n", dlerror());
        return make_error("ERR_FAILED");
    }
    const int ret = fn(usage, config);
    std::fprintf(stderr, "audio/set_force_use: usage=%d config=%d ret=%d\n", usage, config, ret);
    if (ret != 0) {
        return make_error("ERR_FAILED");
    }
    return {0, kResponseOk};
#else
    return {0, kResponseOk};
#endif
}

struct CommandEntry {
    std::string_view name;
    response_t (*handler)(const CommandContext &);
};

constexpr CommandEntry kCommands[] = {
        {"init", handle_init},
        {"enable", handle_enable},
        {"disable", handle_disable},
        {"shutdown", handle_shutdown},
        {"exit", handle_shutdown},
        {"setfreq", handle_setfreq},
        {"jump", handle_jump},
        {"seekhw", handle_seek},
        {"power_mode", handle_power_mode},
        {"set_stereo", handle_stereo},
        {"set_antenna", handle_antenna},
        {"set_region", handle_region},
        {"set_spacing", handle_spacing},
        {"searchhw", handle_search},
        {"search_cancel", handle_search_cancel},
        {"auto_af", handle_auto_af},
        {"slimbus", handle_slimbus},
        {"set_soft_mute", handle_soft_mute},
        {"set_notch_filter", handle_notch_filter},
        {"set_volume", handle_volume},
        {"set_force_use", handle_force_use},
};

}  // namespace

response_t route_command(const char *request, BackendProvider get_backend, void (*shutdown_backend)()) {
    const CommandContext command{split_args(request), get_backend, shutdown_backend};
    if (command.args.empty()) {
        return make_error(kErrUnknown);
    }

    for (const auto &entry : kCommands) {
        if (entry.name == command.args.front()) {
            return entry.handler(command);
        }
    }

    return make_error(kErrUnknown);
}
