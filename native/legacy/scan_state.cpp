#include "scan_state.h"

bool LegacyScanState::start_seek() {
    if (status_ != Status::kIdle) {
        return false;
    }

    status_ = Status::kSeeking;
    return true;
}

void LegacyScanState::seek_failed() {
    if (status_ == Status::kSeeking) {
        reset();
    }
}

bool LegacyScanState::start() {
    if (status_ != Status::kIdle) {
        return false;
    }

    status_ = Status::kScanning;
    pending_frequency_khz_ = 0;
    stations_.reset();
    return true;
}

void LegacyScanState::start_failed() {
    if (status_ == Status::kScanning) {
        reset();
    }
}

bool LegacyScanState::begin_cancel() {
    if (status_ != Status::kScanning) {
        return false;
    }

    status_ = Status::kCancelling;
    pending_frequency_khz_ = 0;
    return true;
}

void LegacyScanState::cancel_failed() {
    if (status_ == Status::kCancelling) {
        status_ = Status::kScanning;
    }
}

void LegacyScanState::on_tune(int frequency_khz) {
    if (status_ == Status::kSeeking) {
        reset();
    } else if (status_ == Status::kScanning && frequency_khz > 0) {
        pending_frequency_khz_ = frequency_khz;
    }
}

int LegacyScanState::on_scan_next(int fallback_frequency_khz) {
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

LegacyScanTerminal LegacyScanState::on_seek_complete(ScanResult *result) {
    if (status_ == Status::kCancelling) {
        reset();
        return LegacyScanTerminal::kCancelled;
    }
    if (status_ != Status::kScanning || result == nullptr) {
        return LegacyScanTerminal::kNone;
    }

    stations_.copy_sorted(result);
    reset();
    return LegacyScanTerminal::kCompleted;
}

bool LegacyScanState::busy() const {
    return status_ != Status::kIdle;
}

void LegacyScanState::reset() {
    status_ = Status::kIdle;
    pending_frequency_khz_ = 0;
    stations_.reset();
}
