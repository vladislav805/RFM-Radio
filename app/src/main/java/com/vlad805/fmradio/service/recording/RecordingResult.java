package com.vlad805.fmradio.service.recording;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Immutable summary of a completed FM recording.
 */
public final class RecordingResult {
    private final int mSizeBytes;
    private final int mDurationSeconds;
    @NonNull
    private final String mDisplayPath;
    @NonNull
    private final String mDisplayName;
    @Nullable
    private final String mRecordingUri;
    @Nullable
    private final String mRecordingFilePath;

    public RecordingResult(
            final int sizeBytes,
            final int durationSeconds,
            @NonNull final String displayPath,
            @NonNull final String displayName,
            @Nullable final String recordingUri,
            @Nullable final String recordingFilePath
    ) {
        mSizeBytes = sizeBytes;
        mDurationSeconds = durationSeconds;
        mDisplayPath = displayPath;
        mDisplayName = displayName;
        mRecordingUri = recordingUri;
        mRecordingFilePath = recordingFilePath;
    }

    public int getSizeBytes() {
        return mSizeBytes;
    }

    public int getDurationSeconds() {
        return mDurationSeconds;
    }

    @NonNull
    public String getDisplayPath() {
        return mDisplayPath;
    }

    @NonNull
    public String getDisplayName() {
        return mDisplayName;
    }

    @Nullable
    public String getRecordingUri() {
        return mRecordingUri;
    }

    @Nullable
    public String getRecordingFilePath() {
        return mRecordingFilePath;
    }

    @Nullable
    public String getLocation() {
        return mRecordingUri != null ? mRecordingUri : mRecordingFilePath;
    }
}
