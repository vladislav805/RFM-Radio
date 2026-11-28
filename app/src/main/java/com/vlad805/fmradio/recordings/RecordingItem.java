package com.vlad805.fmradio.recordings;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

/**
 * Immutable descriptor for an audio recording inside {@code Music/RFM-Recordings}.
 */
public final class RecordingItem {
    @NonNull
    private final String mDisplayName;
    @NonNull
    private final String mRelativeDir;
    @NonNull
    private final String mRelativePath;
    @Nullable
    private final Uri mContentUri;
    @Nullable
    private final String mFilePath;
    @NonNull
    private final String mExtension;
    @NonNull
    private final String mMimeType;
    private final long mSizeBytes;
    private final long mLastModifiedMillis;
    private final long mDurationMillis;
    private final boolean mHasRfmMetadata;

    public RecordingItem(
            @NonNull final String displayName,
            @NonNull final String relativeDir,
            @NonNull final String relativePath,
            @Nullable final Uri contentUri,
            @Nullable final String filePath,
            @NonNull final String extension,
            @NonNull final String mimeType,
            final long sizeBytes,
            final long lastModifiedMillis,
            final long durationMillis,
            final boolean hasRfmMetadata
    ) {
        mDisplayName = displayName;
        mRelativeDir = relativeDir;
        mRelativePath = relativePath;
        mContentUri = contentUri;
        mFilePath = filePath;
        mExtension = extension;
        mMimeType = mimeType;
        mSizeBytes = Math.max(0L, sizeBytes);
        mLastModifiedMillis = Math.max(0L, lastModifiedMillis);
        mDurationMillis = Math.max(0L, durationMillis);
        mHasRfmMetadata = hasRfmMetadata;
    }

    @NonNull
    public String getDisplayName() {
        return mDisplayName;
    }

    @NonNull
    public String getRelativeDir() {
        return mRelativeDir;
    }

    @NonNull
    public String getRelativePath() {
        return mRelativePath;
    }

    @Nullable
    public Uri getContentUri() {
        return mContentUri;
    }

    @Nullable
    public String getFilePath() {
        return mFilePath;
    }

    @NonNull
    public String getExtension() {
        return mExtension;
    }

    @NonNull
    public String getMimeType() {
        return mMimeType;
    }

    public long getSizeBytes() {
        return mSizeBytes;
    }

    public long getLastModifiedMillis() {
        return mLastModifiedMillis;
    }

    public long getDurationMillis() {
        return mDurationMillis;
    }

    public boolean hasRfmMetadata() {
        return mHasRfmMetadata;
    }

    @NonNull
    public RecordingItem withProbedInfo(final long durationMillis, final boolean hasRfmMetadata) {
        return new RecordingItem(
                mDisplayName,
                mRelativeDir,
                mRelativePath,
                mContentUri,
                mFilePath,
                mExtension,
                mMimeType,
                mSizeBytes,
                mLastModifiedMillis,
                durationMillis > 0L ? durationMillis : mDurationMillis,
                hasRfmMetadata
        );
    }

    @Override
    public boolean equals(final Object other) {
        if (!(other instanceof RecordingItem)) {
            return false;
        }

        final RecordingItem that = (RecordingItem) other;
        return (
                mSizeBytes == that.mSizeBytes &&
                mLastModifiedMillis == that.mLastModifiedMillis &&
                mDurationMillis == that.mDurationMillis &&
                mHasRfmMetadata == that.mHasRfmMetadata &&
                mDisplayName.equals(that.mDisplayName) &&
                mRelativeDir.equals(that.mRelativeDir) &&
                mRelativePath.equals(that.mRelativePath) &&
                Objects.equals(mContentUri, that.mContentUri) &&
                Objects.equals(mFilePath, that.mFilePath) &&
                mExtension.equals(that.mExtension) &&
                mMimeType.equals(that.mMimeType)
        );
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                mDisplayName,
                mRelativeDir,
                mRelativePath,
                mContentUri,
                mFilePath,
                mExtension,
                mMimeType,
                mSizeBytes,
                mLastModifiedMillis,
                mDurationMillis,
                mHasRfmMetadata
        );
    }
}
