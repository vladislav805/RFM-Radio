package com.vlad805.fmradio.recordings.trim;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.vlad805.fmradio.recordings.RecordingItem;
import com.vlad805.fmradio.recordings.RecordingsRepository;
import com.vlad805.fmradio.service.recording.RfmMetadata;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * Unified recording trim facade supporting WAV, MP3, and Ogg Opus across both
 * MediaStore (API >= 29) and direct File storage (API < 29).
 */
public final class RecordingTrimmer {
    public static final long MIN_TRIM_DURATION_MS = 1000L;

    public enum SaveMode {
        SAVE_AS_COPY,
        REPLACE_ORIGINAL
    }

    private static final String ROOT_DIRECTORY = "RFM-Recordings";

    private RecordingTrimmer() {}

    @NonNull
    public static RecordingItem trim(
            @NonNull final Context context,
            @NonNull final RecordingItem source,
            final long startMs,
            final long endMs,
            @NonNull final SaveMode mode,
            @Nullable final TrimProgressListener progress
    ) throws IOException {
        validateRange(startMs, endMs);

        final File tempDir = new File(context.getCacheDir(), "recordings");
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            throw new IOException("Cannot create temp directory");
        }

        final String ext = source.getExtension().toLowerCase(Locale.US);
        final RfmMetadata metadata = RecordingsRepository.readMetadata(context, source);
        final File trimmedTemp = File.createTempFile("rfm-trim-out-", "." + ext, tempDir);
        File copiedSource = null;

        try {
            if ("opus".equals(ext)) {
                try (ParcelFileDescriptor pfd = openReadPfd(context, source)) {
                    OpusTrimmer.trim(pfd, tempDir, trimmedTemp, startMs, endMs, metadata, progress);
                }
            } else {
                final File inputFile;
                if (source.getFilePath() != null) {
                    inputFile = new File(source.getFilePath());
                } else {
                    copiedSource = File.createTempFile("rfm-trim-src-", "." + ext, tempDir);
                    copySourceToFile(context, source, copiedSource);
                    inputFile = copiedSource;
                }

                if ("wav".equals(ext)) {
                    WavTrimmer.trim(inputFile, trimmedTemp, startMs, endMs, metadata, progress);
                } else if ("mp3".equals(ext)) {
                    final String title = source.getDisplayName();
                    final long timestamp = source.getLastModifiedMillis() > 0L
                            ? source.getLastModifiedMillis()
                            : System.currentTimeMillis();
                    Mp3Trimmer.trim(inputFile, trimmedTemp, startMs, endMs, title, timestamp, metadata, progress);
                } else {
                    throw new IOException("Unsupported format for trimming: " + ext);
                }
            }

            final long newDurationMs = endMs - startMs;

            if (mode == SaveMode.REPLACE_ORIGINAL) {
                return replaceOriginal(context, source, trimmedTemp, newDurationMs, metadata != null);
            }

            return publishCopy(context, source, trimmedTemp, newDurationMs, metadata != null);
        } finally {
            if (copiedSource != null && copiedSource.exists()) {
                //noinspection ResultOfMethodCallIgnored
                copiedSource.delete();
            }

            if (trimmedTemp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                trimmedTemp.delete();
            }
        }
    }

    static void validateRange(final long startMs, final long endMs) throws IOException {
        if (startMs < 0L || endMs <= startMs) {
            throw new IOException("Invalid trim range");
        }

        if (endMs - startMs < MIN_TRIM_DURATION_MS) {
            throw new IOException("Trim selection must be at least 1 second");
        }
    }

    @NonNull
    static String buildTrimCopyName(@NonNull final String displayName) {
        final int dot = displayName.lastIndexOf('.');
        if (dot <= 0) {
            return displayName + "_trim";
        }

        final String stem = displayName.substring(0, dot);
        final String ext = displayName.substring(dot);
        return stem + "_trim" + ext;
    }

    @NonNull
    private static ParcelFileDescriptor openReadPfd(
            @NonNull final Context context,
            @NonNull final RecordingItem source
    ) throws IOException {
        if (source.getContentUri() != null) {
            final ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(source.getContentUri(), "r");
            if (pfd == null) {
                throw new IOException("Cannot open recording URI");
            }
            return pfd;
        }

        if (source.getFilePath() != null) {
            return ParcelFileDescriptor.open(new File(source.getFilePath()), ParcelFileDescriptor.MODE_READ_ONLY);
        }

        throw new IOException("Missing recording location");
    }

    private static void copySourceToFile(
            @NonNull final Context context,
            @NonNull final RecordingItem source,
            @NonNull final File dst
    ) throws IOException {
        try (
                InputStream in = RecordingsRepository.openInputStream(context, source);
                OutputStream out = new FileOutputStream(dst, false)
        ) {
            if (in == null) {
                throw new IOException("Cannot read recording");
            }

            final byte[] buf = new byte[65536];
            int read;
            while ((read = in.read(buf)) > 0) {
                out.write(buf, 0, read);
            }
        }
    }

    @NonNull
    private static RecordingItem replaceOriginal(
            @NonNull final Context context,
            @NonNull final RecordingItem source,
            @NonNull final File trimmedTemp,
            final long newDurationMs,
            final boolean hasMetadata
    ) throws IOException {
        final long newSize = trimmedTemp.length();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && source.getContentUri() != null) {
            final ContentResolver resolver = context.getContentResolver();
            try (
                    InputStream in = new FileInputStream(trimmedTemp);
                    OutputStream out = resolver.openOutputStream(source.getContentUri(), "wt")
            ) {
                if (out == null) {
                    throw new IOException("Cannot open MediaStore output stream");
                }

                final byte[] buf = new byte[65536];
                int read;
                while ((read = in.read(buf)) > 0) {
                    out.write(buf, 0, read);
                }
            }

            return new RecordingItem(
                    source.getDisplayName(),
                    source.getRelativeDir(),
                    source.getRelativePath(),
                    source.getContentUri(),
                    null,
                    source.getExtension(),
                    source.getMimeType(),
                    newSize,
                    System.currentTimeMillis(),
                    newDurationMs,
                    hasMetadata
            );
        }

        if (source.getFilePath() == null) {
            throw new IOException("Missing file path");
        }

        final File originalFile = new File(source.getFilePath());
        final File siblingTmp = new File(originalFile.getParentFile(), "." + originalFile.getName() + ".trim.tmp");

        try (
                InputStream in = new FileInputStream(trimmedTemp);
                OutputStream out = new FileOutputStream(siblingTmp, false)
        ) {
            final byte[] buf = new byte[65536];
            int read;
            while ((read = in.read(buf)) > 0) {
                out.write(buf, 0, read);
            }
        }

        if (originalFile.exists() && !originalFile.delete()) {
            //noinspection ResultOfMethodCallIgnored
            siblingTmp.delete();
            throw new IOException("Cannot replace original recording");
        }

        if (!siblingTmp.renameTo(originalFile)) {
            throw new IOException("Cannot move trimmed recording into place");
        }

        return new RecordingItem(
                source.getDisplayName(),
                source.getRelativeDir(),
                source.getRelativePath(),
                null,
                originalFile.getAbsolutePath(),
                source.getExtension(),
                source.getMimeType(),
                originalFile.length(),
                originalFile.lastModified(),
                newDurationMs,
                hasMetadata
        );
    }

    @NonNull
    private static RecordingItem publishCopy(
            @NonNull final Context context,
            @NonNull final RecordingItem source,
            @NonNull final File trimmedTemp,
            final long newDurationMs,
            final boolean hasMetadata
    ) throws IOException {
        final String copyName = buildTrimCopyName(source.getDisplayName());
        final String relDir = source.getRelativeDir();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return publishCopyToMediaStore(context.getContentResolver(), source, copyName, trimmedTemp, newDurationMs, hasMetadata);
        }

        final File root = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                ROOT_DIRECTORY
        );
        final File parentDir = relDir.isEmpty() ? root : new File(root, relDir);

        if (!parentDir.exists() && !parentDir.mkdirs()) {
            throw new IOException("Cannot create target folder");
        }

        File targetFile = new File(parentDir, copyName);
        int suffix = 2;
        while (targetFile.exists()) {
            final int dot = copyName.lastIndexOf('.');
            final String disambiguated = dot > 0
                    ? copyName.substring(0, dot) + "_" + suffix + copyName.substring(dot)
                    : copyName + "_" + suffix;
            targetFile = new File(parentDir, disambiguated);
            suffix++;
        }

        try (
                InputStream in = new FileInputStream(trimmedTemp);
                OutputStream out = new FileOutputStream(targetFile, false)
        ) {
            final byte[] buf = new byte[65536];
            int read;
            while ((read = in.read(buf)) > 0) {
                out.write(buf, 0, read);
            }
        }

        final String finalName = targetFile.getName();
        final String relPath = relDir.isEmpty() ? finalName : relDir + "/" + finalName;

        return new RecordingItem(
                finalName,
                relDir,
                relPath,
                null,
                targetFile.getAbsolutePath(),
                source.getExtension(),
                source.getMimeType(),
                targetFile.length(),
                targetFile.lastModified(),
                newDurationMs,
                hasMetadata
        );
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    @NonNull
    private static RecordingItem publishCopyToMediaStore(
            @NonNull final ContentResolver resolver,
            @NonNull final RecordingItem source,
            @NonNull final String copyName,
            @NonNull final File trimmedTemp,
            final long newDurationMs,
            final boolean hasMetadata
    ) throws IOException {
        final String relDir = source.getRelativeDir();
        final String mediaRelPath = relDir.isEmpty()
                ? Environment.DIRECTORY_MUSIC + "/" + ROOT_DIRECTORY + "/"
                : Environment.DIRECTORY_MUSIC + "/" + ROOT_DIRECTORY + "/" + relDir + "/";

        final ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, copyName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, source.getMimeType());
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, mediaRelPath);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        final Uri collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        final Uri uri = resolver.insert(collection, values);

        if (uri == null) {
            throw new IOException("Cannot create MediaStore entry for trimmed copy");
        }

        try (
                InputStream in = new FileInputStream(trimmedTemp);
                OutputStream out = resolver.openOutputStream(uri, "w")
        ) {
            if (out == null) {
                resolver.delete(uri, null, null);
                throw new IOException("Cannot open MediaStore output");
            }

            final byte[] buf = new byte[65536];
            int read;
            while ((read = in.read(buf)) > 0) {
                out.write(buf, 0, read);
            }
        } catch (final IOException error) {
            resolver.delete(uri, null, null);
            throw error;
        }

        final ContentValues ready = new ContentValues();
        ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
        resolver.update(uri, ready, null, null);

        final String relPath = relDir.isEmpty() ? copyName : relDir + "/" + copyName;

        return new RecordingItem(
                copyName,
                relDir,
                relPath,
                uri,
                null,
                source.getExtension(),
                source.getMimeType(),
                trimmedTemp.length(),
                System.currentTimeMillis(),
                newDurationMs,
                hasMetadata
        );
    }
}
