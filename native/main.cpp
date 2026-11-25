#include <cstdio>
#include <unistd.h>

#include "backend_factory.h"
#include "command_router.h"

namespace {

Backend *g_backend = nullptr;
BackendKind g_backend_kind = BackendKind::kNone;
bool g_backend_shutdown = false;

Backend *ensure_backend() {
    if (g_backend != nullptr) {
        return g_backend;
    }

    // Backend selection is process-global and runs once at startup: the device cannot
    // switch between V4L2 and FM HAL while the bridge is running, so reuse g_backend_kind.
    g_backend = create_backend(g_backend_kind);
    return g_backend;
}

void shutdown_backend() {
    if (!g_backend_shutdown && g_backend != nullptr) {
        g_backend->shutdown();
        g_backend_shutdown = true;
    }
}

void poll_signal_metric() {
    if (g_backend != nullptr) {
        g_backend->request_signal_metric();
    }
}

response_t handle_request(const char *request) {
    return route_command(request, ensure_backend, shutdown_backend);
}

}  // namespace

int main() {
    setvbuf(stdout, nullptr, _IOLBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);

    g_backend_kind = detect_backend_kind();
    const int result = init_server(handle_request, poll_signal_metric, backend_kind_name(g_backend_kind));
    const bool needs_shutdown_settle = (
            g_backend != nullptr &&
            !g_backend_shutdown &&
            g_backend->kind() == BackendKind::kV4l2Qcom
    );

    shutdown_backend();

    if (needs_shutdown_settle) {
        usleep(kCommandResponseDelayUs);
    }

    const int exit_code = result == 0 ? 0 : 1;
    if (g_backend != nullptr && g_backend->kind() == BackendKind::kHelium) {
        // Some Qualcomm libfm-hci builds leave a joinable std::thread in their
        // global fm_hci_t even after FM OFF and the worker's exit. Normal exit
        // runs its destructor and aborts. Backend cleanup and protocol writes
        // are already complete; bypass vendor static destructors here.
        fflush(stdout);
        fflush(stderr);
        _exit(exit_code);
    }
    return exit_code;
}
