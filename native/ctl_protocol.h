#pragma once

#include <stdint.h>

#include <string>

struct ControlRequest {
    uint64_t id = 0;
    std::string command;
};

bool parse_control_request_line(const std::string &line, ControlRequest *request);
