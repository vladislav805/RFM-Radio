#pragma once

#include "../../scan_result.h"

enum class V4l2ScanTerminal {
    kNone,
    kCompleted,
    kCancelled,
};

// Manages state and station collection for sequential V4L2 hardware scans and seeks.
class V4l2ScanState {
public:
    bool start_seek();
    void seek_failed();
    bool start();
    void start_failed();
    bool begin_cancel();
    void cancel_failed();
    bool on_tune(int frequency_khz);
    int on_scan_next(int fallback_frequency_khz);
    V4l2ScanTerminal on_seek_complete(ScanResult *result);
    bool busy() const;
    void reset();

private:
    enum class Status {
        kIdle,
        kSeeking,
        kScanning,
        kCancelling,
    } status_ = Status::kIdle;

    int pending_frequency_khz_ = 0;
    ScanResultCollector stations_;
};
