package com.vlad805.fmradio.service.fm;

import androidx.annotation.NonNull;
import com.vlad805.fmradio.controller.TunerStatus;

/**
 * Typed event contract from native tuner layers to {@link com.vlad805.fmradio.service.FMService}.
 * Callbacks may arrive on background threads; consumers must marshal to the main looper.
 */
public interface FMEventCallback {
    void onStatusChanged(@NonNull TunerStatus status);

    void onFrequencyChanged(int frequencyKhz);

    void onStatePatch(@NonNull RadioStatePatch patch);

    void onSearchProgress(int count);

    void onSearchDone(@NonNull int[] stations);

    void onSearchFailed();

    void onInvalidAntennaError();

    void onError(@NonNull String message);

    void onUnexpectedExit(int exitCode);
}
