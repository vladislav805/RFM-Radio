package com.vlad805.fmradio.service.recording;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import com.vlad805.fmradio.C;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.Storage;
import com.vlad805.fmradio.helper.RecordSchemaHelper;
import com.vlad805.fmradio.service.fm.RecordError;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

public final class RecordingStorage {
    private static final String ROOT_DIRECTORY = "RFM-Recordings";

    private RecordingStorage() {}

    public static RecordingTarget create(
            final Context context,
            final int kHz,
            final String extension,
            final String mimeType
    ) throws RecordError {
        final String relativeDirectory = sanitizeRelativeDirectory(RecordSchemaHelper.prepareString(
                Storage.getPrefString(
                        context,
                        C.PrefKey.RECORDING_DIRECTORY,
                        context.getString(R.string.pref_recording_path_value)
                ),
                kHz
        ));
        final String displayName = sanitizeFilename(RecordSchemaHelper.prepareString(
                Storage.getPrefString(
                        context,
                        C.PrefKey.RECORDING_FILENAME,
                        context.getString(R.string.pref_recording_name_value)
                ),
                kHz
        )) + "." + extension;

        final File temporaryFile = createTemporaryFile(context, extension);
        final String displayPath = buildDisplayPath(relativeDirectory, displayName);

        // Android 10+ exposes public media through MediaStore, so the temp file is copied there
        // only after recording has finished. Older versions still publish into Music via File I/O.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return new RecordingTarget(
                    context.getContentResolver(),
                    temporaryFile,
                    null,
                    buildMediaStoreRelativePath(relativeDirectory),
                    displayName,
                    mimeType,
                    displayPath
            );
        }

        return new RecordingTarget(
                null,
                temporaryFile,
                buildLegacyFile(relativeDirectory, displayName),
                null,
                displayName,
                mimeType,
                displayPath
        );
    }

    /**
     * Creates a temporary local file used as the live recording target while audio is being written.
     */
    private static File createTemporaryFile(final Context context, final String extension) throws RecordError {
        final File cacheDir = new File(context.getCacheDir(), "recordings");

        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            throw new RecordError("Cannot create temporary recording directory");
        }

        try {
            return File.createTempFile("rfm-recording-", "." + extension, cacheDir);
        } catch (IOException e) {
            throw new RecordError("Cannot create temporary recording file");
        }
    }

    /**
     * Builds the final public file path for pre-Android 10 devices that still write directly into Music.
     */
    private static File buildLegacyFile(final String relativeDirectory, final String displayName) {
        final File root = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                ROOT_DIRECTORY
        );
        final File directory = relativeDirectory.isEmpty() ? root : new File(root, relativeDirectory);
        return new File(directory, displayName);
    }

    /**
     * Returns the relative MediaStore path inside shared Music storage, including the app root directory.
     */
    private static String buildMediaStoreRelativePath(final String relativeDirectory) {
        final StringBuilder builder = new StringBuilder(Environment.DIRECTORY_MUSIC)
                .append('/')
                .append(ROOT_DIRECTORY)
                .append('/');

        if (!relativeDirectory.isEmpty()) {
            builder.append(relativeDirectory).append('/');
        }

        return builder.toString();
    }

    /**
     * Builds the human-readable final location that is shown to the app/UI for the saved recording.
     */
    private static String buildDisplayPath(final String relativeDirectory, final String displayName) {
        return relativeDirectory.isEmpty()
                ? String.format(Locale.US, "%s/%s", ROOT_DIRECTORY, displayName)
                : String.format(Locale.US, "%s/%s/%s", ROOT_DIRECTORY, relativeDirectory, displayName);
    }

    /**
     * Normalizes the user-provided relative directory template and rejects attempts to escape the root directory.
     */
    private static String sanitizeRelativeDirectory(final String relativeDirectory) throws RecordError {
        final String[] parts = relativeDirectory
                .replace('\\', '/')
                .split("/");

        final StringBuilder builder = new StringBuilder();

        for (final String rawPart : parts) {
            final String part = rawPart.trim();

            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }

            if ("..".equals(part)) {
                throw new RecordError("Recording path must stay inside Music/" + ROOT_DIRECTORY);
            }

            if (builder.length() > 0) {
                builder.append('/');
            }

            builder.append(part);
        }

        return builder.toString();
    }

    /**
     * Validates the generated filename so it can be safely published as a single media file name.
     */
    private static String sanitizeFilename(final String filename) throws RecordError {
        final String value = filename.trim();

        if (value.isEmpty()) {
            throw new RecordError("Recording filename cannot be empty");
        }

        if (value.contains("/") || value.contains("\\") || value.contains("..")) {
            throw new RecordError("Recording filename contains forbidden characters");
        }

        return value;
    }
}
