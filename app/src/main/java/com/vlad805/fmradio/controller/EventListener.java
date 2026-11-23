package com.vlad805.fmradio.controller;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.vlad805.fmradio.service.recording.RecordingResult;

/**
 * Listener for one-shot transient events that are not part of persistent {@link RadioState}.
 * All callbacks are delivered on the main thread.
 */
public interface EventListener {
    default void onSearchProgress(int count) {}

    default void onSearchDone(@NonNull int[] frequenciesKhz) {}

    default void onSearchFailed() {}

    default void onInvalidAntennaError() {}

    default void onRecordingFinished(@NonNull RecordingResult result) {}

    default void onRecordingFailed(@Nullable String message) {}

    default void onTunerError(@NonNull String message) {}
}
