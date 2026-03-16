package com.vlad805.fmradio.service.recording;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import androidx.annotation.RequiresApi;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

public final class RecordingTarget {
    private final ContentResolver mResolver;
    private final File mTemporaryFile;
    private final File mFinalFile;
    private final String mRelativePath;
    private final String mDisplayName;
    private final String mMimeType;
    private final String mDisplayPath;

    private FileOutputStream mOutputStream;

    RecordingTarget(
            final ContentResolver resolver,
            final File temporaryFile,
            final File finalFile,
            final String relativePath,
            final String displayName,
            final String mimeType,
            final String displayPath
    ) {
        mResolver = resolver;
        mTemporaryFile = temporaryFile;
        mFinalFile = finalFile;
        mRelativePath = relativePath;
        mDisplayName = displayName;
        mMimeType = mimeType;
        mDisplayPath = displayPath;
    }

    /**
     * Opens the temporary local file that is used for live recording writes.
     */
    public FileOutputStream openOutputStream() throws IOException {
        if (mOutputStream == null) {
            mOutputStream = new FileOutputStream(mTemporaryFile, false);
        }

        return mOutputStream;
    }

    /**
     * Publishes the completed temporary recording into its final public destination.
     */
    public void commit() throws IOException {
        closeQuietly();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            commitToMediaStore();
            return;
        }

        commitToFile();
    }

    /**
     * Discards the temporary recording file without publishing it anywhere.
     */
    public void abort() {
        closeQuietly();

        if (mTemporaryFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            mTemporaryFile.delete();
        }
    }

    /**
     * Closes the temporary recording stream if it is currently open.
     */
    public void closeQuietly() {
        if (mOutputStream != null) {
            try {
                mOutputStream.close();
            } catch (IOException ignored) {
            }
            mOutputStream = null;
        }
    }

    /**
     * Returns the current size of the temporary recording file.
     */
    public long getLength() {
        return mTemporaryFile.exists() ? mTemporaryFile.length() : 0L;
    }

    /**
     * Returns the final user-visible path that is shown in the UI and notifications.
     */
    public String getDisplayPath() {
        return mDisplayPath;
    }

    /**
     * Returns the final published file name without directory components.
     */
    public String getDisplayName() {
        return mDisplayName;
    }

    /**
     * Moves or copies the finished temporary file into the legacy shared Music directory.
     */
    private void commitToFile() throws IOException {
        if (mFinalFile == null) {
            throw new IOException("Final file is not configured");
        }

        final File parent = mFinalFile.getParentFile();

        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create recording directory");
        }

        if (mFinalFile.exists()) {
            throw new IOException("Target file already exists");
        }

        if (!mTemporaryFile.renameTo(mFinalFile)) {
            copyFile(mTemporaryFile, mFinalFile);

            if (mTemporaryFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                mTemporaryFile.delete();
            }
        }
    }

    /**
     * Copies the finished temporary file into a MediaStore audio entry and marks it as ready.
     */
    @RequiresApi(api = Build.VERSION_CODES.Q)
    private void commitToMediaStore() throws IOException {
        if (mResolver == null) {
            throw new IOException("ContentResolver is not configured");
        }

        final ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, mDisplayName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mMimeType);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, mRelativePath);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        final Uri collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        final Uri uri = mResolver.insert(collection, values);
        if (uri == null) {
            throw new IOException("Cannot create MediaStore item");
        }

        try (FileInputStream inputStream = new FileInputStream(mTemporaryFile);
             ParcelFileDescriptor parcelFileDescriptor = mResolver.openFileDescriptor(uri, "w")) {
            if (parcelFileDescriptor == null) {
                mResolver.delete(uri, null, null);
                throw new IOException("Cannot open MediaStore output");
            }
            try (FileOutputStream outputStream = new FileOutputStream(parcelFileDescriptor.getFileDescriptor())) {
                final byte[] buffer = new byte[131072];
                int read;
                while ((read = inputStream.read(buffer)) > 0) {
                    outputStream.write(buffer, 0, read);
                }
            }
        } catch (IOException e) {
            mResolver.delete(uri, null, null);
            throw e;
        }

        final ContentValues ready = new ContentValues();
        ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
        mResolver.update(uri, ready, null, null);

        if (mTemporaryFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            mTemporaryFile.delete();
        }
    }

    /**
     * Copies one file to another using buffered stream I/O.
     */
    private static void copyFile(final File source, final File destination) throws IOException {
        try (final FileInputStream inputStream = new FileInputStream(source);
            final FileOutputStream outputStream = new FileOutputStream(destination, false)) {
            final byte[] buffer = new byte[131072];
            int read;

            while ((read = inputStream.read(buffer)) > 0) {
                outputStream.write(buffer, 0, read);
            }
        }
    }
}
