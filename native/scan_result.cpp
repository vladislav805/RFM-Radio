#include "scan_result.h"

#include <algorithm>
#include <cstring>

ScanAddResult ScanResultCollector::add(int frequency_khz) {
    if (frequency_khz <= 0) {
        return ScanAddResult::kInvalid;
    }

    for (int i = 0; i < count_; ++i) {
        if (frequencies_khz_[i] == frequency_khz) {
            return ScanAddResult::kDuplicate;
        }
    }

    if (count_ >= kMaxScanStations) {
        return ScanAddResult::kFull;
    }

    frequencies_khz_[count_++] = frequency_khz;

    return ScanAddResult::kAdded;
}

void ScanResultCollector::copy_sorted(ScanResult *result) const {
    if (result == nullptr) {
        return;
    }

    result->count = count_;

    std::memcpy(
            result->frequencies_khz,
            frequencies_khz_,
            static_cast<size_t>(count_) * sizeof(frequencies_khz_[0])
    );
    std::sort(result->frequencies_khz, result->frequencies_khz + result->count);
}

void ScanResultCollector::reset() {
    count_ = 0;
}
