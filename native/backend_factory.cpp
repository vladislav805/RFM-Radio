#include "backend_factory.h"

#include <atomic>
#include <cctype>
#include <cstdio>
#include <fstream>
#include <string>
#include <unistd.h>

Backend *create_v4l2_qcom_backend();
Backend *create_hal_helium_backend();

namespace {

constexpr const char *kSysfsRadio0NamePath = "/sys/class/video4linux/radio0/name";

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

std::string to_lower_ascii(std::string_view input) {
    std::string lowered(input);
    for (char &ch : lowered) {
        ch = static_cast<char>(std::tolower(static_cast<unsigned char>(ch)));
    }
    return lowered;
}

std::optional<std::string> read_sysfs_radio0_name() {
    std::ifstream file(kSysfsRadio0NamePath);
    if (!file.is_open()) {
        return std::nullopt;
    }

    std::string line;
    if (!std::getline(file, line)) {
        return std::string();
    }

    while (
            !line.empty() &&
            (line.back() == '\r' || line.back() == '\n' || line.back() == ' ' || line.back() == '\t')
    ) {
        line.pop_back();
    }

    return line;
}

}  // namespace

// Matches Qualcomm V4L2 driver families ("iris" / "tavarua") by allowlist.
bool is_qualcomm_v4l2_driver_name(std::string_view name) {
    if (name.empty()) {
        return false;
    }

    const std::string lowered = to_lower_ascii(name);
    return lowered.find("iris") != std::string::npos ||
            lowered.find("tavarua") != std::string::npos;
}

BackendKind classify_backend_kind(
        std::optional<std::string_view> v4l2_driver_name,
        bool has_dev_radio0,
        bool has_helium_lib
) {
    if (v4l2_driver_name.has_value()) {
        if (has_dev_radio0 && is_qualcomm_v4l2_driver_name(*v4l2_driver_name)) {
            return BackendKind::kV4l2Qcom;
        }
        return has_helium_lib ? BackendKind::kHelium : BackendKind::kNone;
    }

    // Legacy compatibility fallback when the sysfs name node is absent.
    if (has_dev_radio0) {
        return BackendKind::kV4l2Qcom;
    }

    return has_helium_lib ? BackendKind::kHelium : BackendKind::kNone;
}

bool has_helium() {
    for (const char *path : kFmHeliumPaths) {
        if (access(path, F_OK) == 0) {
            return true;
        }
    }

    return false;
}

const char *backend_kind_name(const BackendKind kind) {
    switch (kind) {
        case BackendKind::kV4l2Qcom:
            return "v4l2_qcom";
        case BackendKind::kHelium:
            return "hal_helium";
        case BackendKind::kNone:
        default:
            return "none";
    }
}

BackendKind detect_backend_kind() {
    static std::atomic<bool> logged{false};
    const std::optional<std::string> sysfs_name = read_sysfs_radio0_name();
    const bool has_radio0 = access("/dev/radio0", F_OK) == 0;
    const bool has_helium_lib = has_helium();
    const BackendKind kind = classify_backend_kind(
            sysfs_name ? std::optional<std::string_view>(*sysfs_name) : std::nullopt,
            has_radio0,
            has_helium_lib
    );

    if (!logged.exchange(true)) {
        std::fprintf(
                stderr,
                "drv/detect : sysfs=%d v4l2_name=\"%s\" radio0=%d helium=%d -> kind=%d\n",
                sysfs_name.has_value() ? 1 : 0,
                sysfs_name.has_value() ? sysfs_name->c_str() : "<absent>",
                has_radio0 ? 1 : 0,
                has_helium_lib ? 1 : 0,
                static_cast<int>(kind)
        );
    }

    return kind;
}

Backend *create_backend(const BackendKind kind) {
    switch (kind) {
        case BackendKind::kV4l2Qcom: {
            return create_v4l2_qcom_backend();
        }

        case BackendKind::kHelium: {
            return create_hal_helium_backend();
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
