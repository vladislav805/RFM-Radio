#include "ctl_protocol.h"

#include <charconv>

bool parse_control_request_line(const std::string &line, ControlRequest *request) {
    if (request == nullptr) {
        return false;
    }

    const size_t separator = line.find('\t');
    if (separator == std::string::npos || separator == 0 || separator + 1 >= line.size()) {
        return false;
    }

    uint64_t id = 0;
    const char *begin = line.data();
    const char *end = begin + separator;
    const std::from_chars_result parsed = std::from_chars(begin, end, id);
    if (parsed.ec != std::errc() || parsed.ptr != end || id == 0) {
        return false;
    }

    const std::string command = line.substr(separator + 1);
    if (command.find('\0') != std::string::npos || command.find('\n') != std::string::npos || command.find('\r') != std::string::npos) {
        return false;
    }

    request->id = id;
    request->command = command;
    return true;
}
