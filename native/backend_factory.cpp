#include "backend_factory.h"

#include <unistd.h>

Backend *create_legacy_backend();
Backend *create_hal_backend();

namespace {

constexpr const char *kFmHeliumPaths[] = {
        "/vendor/lib64/fm_helium.so",
        "/vendor/lib/fm_helium.so",
        "/system/vendor/lib64/fm_helium.so",
        "/system/vendor/lib/fm_helium.so",
        "/system_ext/lib64/fm_helium.so",
        "/system_ext/lib/fm_helium.so",
        "/odm/lib64/fm_helium.so",
        "/odm/lib/fm_helium.so",
};

}  // namespace

bool has_dev_radio0() {
    return access("/dev/radio0", F_OK) == 0;
}

bool has_helium() {
    for (const char *path : kFmHeliumPaths) {
        if (access(path, F_OK) == 0) {
            return true;
        }
    }

    return false;
}

BackendKind detect_backend_kind() {
    if (has_dev_radio0()) {
        return BackendKind::kLegacy;
    }

    if (has_helium()) {
        return BackendKind::kHal;
    }

    return BackendKind::kNone;
}

Backend *create_backend(const BackendKind kind) {
    switch (kind) {
        case BackendKind::kLegacy: {
            return create_legacy_backend();
        }

        case BackendKind::kHal: {
            return create_hal_backend();
        }

        case BackendKind::kNone:
        default: {
            return nullptr;
        }
    }
}

Backend *create_detected_backend() {
    return create_backend(detect_backend_kind());
}
