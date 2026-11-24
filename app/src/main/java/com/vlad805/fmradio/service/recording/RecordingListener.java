package com.vlad805.fmradio.service.recording;

import androidx.annotation.NonNull;

/**
 * Typed lifecycle callbacks emitted by {@link RecordService}.
 * Callbacks may be invoked from background encoder threads; consumers must marshal to main thread if needed.
 */
public interface RecordingListener {
    void onRecordingStarted(int initialDurationSeconds, int sizeBytes, @NonNull String displayPath);

    void onRecordingProgress(int durationSeconds, int sizeBytes, @NonNull String displayPath);

    void onRecordingCompleted(@NonNull RecordingResult result);

    default void onRecordingInterrupted(@NonNull final RecordingResult partialResult, @NonNull final Throwable error) {
        onRecordingFailed(error);
    }

    void onRecordingFailed(@NonNull Throwable error);
}
