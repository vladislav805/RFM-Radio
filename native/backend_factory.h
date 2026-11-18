#pragma once

#include <optional>
#include <string_view>

#include "backend.h"

bool is_qualcomm_v4l2_driver_name(std::string_view name);
BackendKind classify_backend_kind(
        std::optional<std::string_view> v4l2_driver_name,
        bool has_dev_radio0,
        bool has_helium_lib
);
BackendKind detect_backend_kind();
const char *backend_kind_name(BackendKind kind);
Backend *create_backend(BackendKind kind);
Backend *create_detected_backend();
