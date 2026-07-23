#ifndef FMBIN_LEGACY_SCAN_STATE_H
#define FMBIN_LEGACY_SCAN_STATE_H

constexpr int kLegacyMaxScanStations = 64;

struct LegacyScanResult {
    int frequencies_khz[kLegacyMaxScanStations] = {};
    int count = 0;
};

enum class LegacyScanTerminal {
    kNone,
    kCompleted,
    kCancelled,
};

class LegacyScanState {
public:
    bool start_seek();
    void seek_failed();
    bool start();
    void start_failed();
    bool begin_cancel();
    void cancel_failed();
    void on_tune(int frequency_khz);
    int on_scan_next(int fallback_frequency_khz);
    LegacyScanTerminal on_seek_complete(LegacyScanResult *result);
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
    int frequencies_khz_[kLegacyMaxScanStations] = {};
    int count_ = 0;
};

#endif // FMBIN_LEGACY_SCAN_STATE_H
