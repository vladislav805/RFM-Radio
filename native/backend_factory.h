#pragma once

#include "backend.h"

BackendKind detect_backend_kind();
Backend *create_backend(BackendKind kind);
Backend *create_detected_backend();
