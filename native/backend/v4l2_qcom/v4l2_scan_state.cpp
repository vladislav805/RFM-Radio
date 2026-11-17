#include "v4l2_scan_state.h"

bool V4l2ScanState::start_seek() {
    if (status_ != Status::kIdle) {
        return false;
    }

    status_ = Status::kSeeking;
    return true;
}

void V4l2ScanState::seek_failed() {
    if (status_ == Status::kSeeking) {
        reset();
    }
}

bool V4l2ScanState::start() {
    if (status_ != Status::kIdle) {
        return false;
    }

    status_ = Status::kScanning;
    pending_frequency_khz_ = 0;
    stations_.reset();
    return true;
}

void V4l2ScanState::start_failed() {
    if (status_ == Status::kScanning) {
        reset();
    }
}

bool V4l2ScanState::begin_cancel() {
    if (status_ != Status::kScanning) {
        return false;
    }

    status_ = Status::kCancelling;
    pending_frequency_khz_ = 0;
    return true;
}

void V4l2ScanState::cancel_failed() {
    if (status_ == Status::kCancelling) {
        status_ = Status::kScanning;
    }
}

void V4l2ScanState::on_tune(int frequency_khz) {
    if (status_ == Status::kSeeking) {
        reset();
    } else if (status_ == Status::kScanning && frequency_khz > 0) {
        pending_frequency_khz_ = frequency_khz;
    }
}

int V4l2ScanState::on_scan_next(int fallback_frequency_khz) {
    if (status_ != Status::kScanning) {
        return 0;
    }

    const int frequency_khz = pending_frequency_khz_ > 0
            ? pending_frequency_khz_
            : fallback_frequency_khz;
    pending_frequency_khz_ = 0;
    if (frequency_khz <= 0) {
        return 0;
    }

    stations_.add(frequency_khz);
    return frequency_khz;
}

V4l2ScanTerminal V4l2ScanState::on_seek_complete(ScanResult *result) {
    if (status_ == Status::kCancelling) {
        reset();
        return V4l2ScanTerminal::kCancelled;
    }
    if (status_ != Status::kScanning || result == nullptr) {
        return V4l2ScanTerminal::kNone;
    }

    stations_.copy_sorted(result);
    reset();
    return V4l2ScanTerminal::kCompleted;
}

bool V4l2ScanState::busy() const {
    return status_ != Status::kIdle;
}

void V4l2ScanState::reset() {
    status_ = Status::kIdle;
    pending_frequency_khz_ = 0;
    stations_.reset();
}
