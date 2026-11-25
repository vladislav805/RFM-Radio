#pragma once

#include "ctl_server.h"

class Backend;
using BackendProvider = Backend *(*)();

inline constexpr unsigned int kCommandResponseDelayUs = 300000;

response_t route_command(const char *request, BackendProvider get_backend, void (*shutdown_backend)());
