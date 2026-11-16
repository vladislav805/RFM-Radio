#include "backend_factory.h"

#include <unistd.h>
#include <stdio.h>
#include <string.h>

Backend *create_legacy_backend();
Backend *create_helium_backend();

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

bool has_v4l2_qcom() {
    FILE *f = fopen("/sys/class/video4linux/radio0/name", "r");
    if (!f) {
        // Fallback for older devices that might not have this sysfs node
        // but still have /dev/radio0
        return access("/dev/radio0", F_OK) == 0;
    }
    char name[64] = {};
    const bool ok = fgets(name, sizeof(name), f) != nullptr;
    fclose(f);

    if (!ok) {
        return access("/dev/radio0", F_OK) == 0;
    }

    // Samsung Exynos radio uses s610. If it's not s610, fallback to Qcom
    // (tavarua/iris) so we don't break existing devices.
    return strstr(name, "s610") == nullptr;
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
    if (has_v4l2_qcom()) {
        return BackendKind::kV4l2Qcom;
    }

    if (has_helium()) {
        return BackendKind::kHelium;
    }

    return BackendKind::kNone;
}

Backend *create_backend(const BackendKind kind) {
    switch (kind) {
        case BackendKind::kV4l2Qcom: {
            return create_legacy_backend();
        }

        case BackendKind::kHelium: {
            return create_helium_backend();
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
