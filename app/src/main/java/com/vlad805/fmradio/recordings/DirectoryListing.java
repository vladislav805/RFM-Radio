package com.vlad805.fmradio.recordings;

import androidx.annotation.NonNull;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Snapshot of one directory level inside {@code Music/RFM-Recordings}.
 */
public final class DirectoryListing {
    @NonNull
    private final String mCurrentRelativeDir;
    @NonNull
    private final List<String> mSubdirectories;
    @NonNull
    private final Map<String, Integer> mSubdirectoryCounts;
    @NonNull
    private final List<RecordingItem> mRecordings;

    public DirectoryListing(
            @NonNull final String currentRelativeDir,
            @NonNull final List<String> subdirectories,
            @NonNull final List<RecordingItem> recordings
    ) {
        this(currentRelativeDir, subdirectories, Collections.emptyMap(), recordings);
    }

    public DirectoryListing(
            @NonNull final String currentRelativeDir,
            @NonNull final List<String> subdirectories,
            @NonNull final Map<String, Integer> subdirectoryCounts,
            @NonNull final List<RecordingItem> recordings
    ) {
        mCurrentRelativeDir = currentRelativeDir;
        mSubdirectories = Collections.unmodifiableList(subdirectories);
        mSubdirectoryCounts = Collections.unmodifiableMap(new HashMap<>(subdirectoryCounts));
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

    public int getSubdirectoryCount(@NonNull final String dirName) {
        final Integer count = mSubdirectoryCounts.get(dirName);
        return count != null ? count : 0;
    }

    @NonNull
    public List<RecordingItem> getRecordings() {
        return mRecordings;
    }

    public boolean isEmpty() {
        return mSubdirectories.isEmpty() && mRecordings.isEmpty();
    }
}
