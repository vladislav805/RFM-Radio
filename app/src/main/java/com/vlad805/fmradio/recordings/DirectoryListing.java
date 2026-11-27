package com.vlad805.fmradio.recordings;

import androidx.annotation.NonNull;

import java.util.Collections;
import java.util.List;

/**
 * Snapshot of one directory level inside {@code Music/RFM-Recordings}.
 */
public final class DirectoryListing {
    @NonNull
    private final String mCurrentRelativeDir;
    @NonNull
    private final List<String> mSubdirectories;
    @NonNull
    private final List<RecordingItem> mRecordings;

    public DirectoryListing(
            @NonNull final String currentRelativeDir,
            @NonNull final List<String> subdirectories,
            @NonNull final List<RecordingItem> recordings
    ) {
        mCurrentRelativeDir = currentRelativeDir;
        mSubdirectories = Collections.unmodifiableList(subdirectories);
        mRecordings = Collections.unmodifiableList(recordings);
    }

    @NonNull
    public String getCurrentRelativeDir() {
        return mCurrentRelativeDir;
    }

    @NonNull
    public List<String> getSubdirectories() {
        return mSubdirectories;
    }

    @NonNull
    public List<RecordingItem> getRecordings() {
        return mRecordings;
    }

    public boolean isEmpty() {
        return mSubdirectories.isEmpty() && mRecordings.isEmpty();
    }
}
