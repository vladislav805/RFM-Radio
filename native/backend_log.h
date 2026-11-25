#pragma once

#include <cstdarg>
#include <cstdio>

class Logger {
public:
    // Each backend creates its logger with a string literal.
    explicit constexpr Logger(const char *prefix) : prefix_(prefix) {}

    void operator()(const char *scope, const char *fmt, ...) const __attribute__((format(printf, 3, 4))) {
        std::va_list args;
        va_start(args, fmt);
        std::fprintf(stderr, "%s/%-7s: ", prefix_, scope);
        std::vfprintf(stderr, fmt, args);
        std::fputc('\n', stderr);
        va_end(args);
    }

private:
    const char *prefix_;
};
