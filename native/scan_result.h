#ifndef FMBIN_SCAN_RESULT_H
#define FMBIN_SCAN_RESULT_H

constexpr int kMaxScanStations = 64;

struct ScanResult {
    int frequencies_khz[kMaxScanStations] = {};
    int count = 0;
};

enum class ScanAddResult {
    kAdded,
    kDuplicate,
    kInvalid,
    kFull,
};

class ScanResultCollector {
public:
    ScanAddResult add(int frequency_khz);
    void copy_sorted(ScanResult *result) const;
    void reset();

private:
    int frequencies_khz_[kMaxScanStations] = {};
    int count_ = 0;
};

#endif // FMBIN_SCAN_RESULT_H
