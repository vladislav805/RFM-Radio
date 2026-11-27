package com.vlad805.fmradio.recordings;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.content.FileProvider;

import com.vlad805.fmradio.service.recording.RecordingMetadataIO;
import com.vlad805.fmradio.service.recording.RecordingStorage;
import com.vlad805.fmradio.service.recording.RfmMetadata;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Browses, inspects, shares, and deletes recordings inside {@code Music/RFM-Recordings}.
 */
public final class RecordingsRepository {
    private static final String ROOT_DIRECTORY = "RFM-Recordings";
    private static final String MEDIASTORE_ROOT_PREFIX = "Music/" + ROOT_DIRECTORY + "/";

    private RecordingsRepository() {}

    @NonNull
    public static DirectoryListing listDirectory(
            @NonNull final Context context,
            @NonNull final String relativeDir
    ) {
        final String normalizedDir = normalizeRelativeDir(relativeDir);

        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? listViaMediaStore(context.getContentResolver(), normalizedDir)
                : listViaFileSystem(normalizedDir);
    }

    @Nullable
    public static RfmMetadata readMetadata(
            @NonNull final Context context,
            @NonNull final RecordingItem item
    ) throws IOException {
        try (InputStream in = openInputStream(context, item)) {
            if (in == null) {
                return null;
            }

            return RecordingMetadataIO.readFromStream(in, item.getExtension());
        }
    }

    @Nullable
    public static InputStream openInputStream(
            @NonNull final Context context,
            @NonNull final RecordingItem item
    ) throws IOException {
        if (item.getContentUri() != null) {
            final InputStream stream = context.getContentResolver().openInputStream(item.getContentUri());
            return stream != null ? new BufferedInputStream(stream, 32768) : null;
        }

        if (item.getFilePath() != null) {
            return new BufferedInputStream(new FileInputStream(item.getFilePath()), 32768);
        }

        return null;
    }

    @NonNull
    public static Intent createShareIntent(
            @NonNull final Context context,
            @NonNull final RecordingItem item,
            @NonNull final CharSequence chooserTitle
    ) {
        final Uri shareUri;

        if (item.getContentUri() != null) {
            shareUri = item.getContentUri();
        } else if (item.getFilePath() != null) {
            shareUri = FileProvider.getUriForFile(
                    context,
                    context.getPackageName() + ".fileprovider",
                    new File(item.getFilePath())
            );
        } else {
            throw new IllegalArgumentException("Recording has neither contentUri nor filePath");
        }

        final Intent sendIntent = new Intent(Intent.ACTION_SEND)
                .setType(item.getMimeType())
                .putExtra(Intent.EXTRA_STREAM, shareUri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        return Intent.createChooser(sendIntent, chooserTitle);
    }

    public static boolean delete(
            @NonNull final Context context,
            @NonNull final RecordingItem item
    ) throws IOException {
        final String uriString = item.getContentUri() != null
                ? item.getContentUri().toString()
                : null;

        return RecordingStorage.deletePublished(context, uriString, item.getFilePath());
    }

    @NonNull
    public static String parentRelativeDir(@NonNull final String relativeDir) {
        final String normalized = normalizeRelativeDir(relativeDir);

        if (normalized.isEmpty()) {
            return "";
        }

        final int slash = normalized.lastIndexOf('/');
        return slash > 0 ? normalized.substring(0, slash) : "";
    }

    @NonNull
    public static String childRelativeDir(@NonNull final String parentDir, @NonNull final String childName) {
        final String normalizedParent = normalizeRelativeDir(parentDir);

        return normalizedParent.isEmpty()
                ? childName
                : normalizedParent + "/" + childName;
    }

    static boolean isSupportedAudioExtension(@NonNull final String extension) {
        final String ext = extension.toLowerCase(Locale.US);

        return (
            "wav".equals(ext) ||
            "mp3".equals(ext) ||
            "opus".equals(ext)
        );
    }

    @NonNull
    static String extractExtension(@NonNull final String filename) {
        final int dot = filename.lastIndexOf('.');

        if (dot <= 0 || dot == filename.length() - 1) {
            return "";
        }

        return filename.substring(dot + 1).toLowerCase(Locale.US);
    }

    @NonNull
    static String mimeTypeForExtension(@NonNull final String extension) {
        switch (extension.toLowerCase(Locale.US)) {
            case "wav": {
                return "audio/wav";
            }

            case "mp3": {
                return "audio/mpeg";
            }

            case "opus": {
                return "audio/ogg";
            }

            default: {
                return "audio/*";
            }
        }
    }

    @NonNull
    static String normalizeRelativeDir(@Nullable final String dir) {
        if (dir == null || dir.trim().isEmpty()) {
            return "";
        }

        String normalized = dir.trim();

        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }

        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        return normalized;
    }

    @NonNull
    static String stripMediaStoreRoot(@Nullable final String mediaStoreRelativePath) {
        if (mediaStoreRelativePath == null) {
            return "";
        }

        String path = mediaStoreRelativePath;

        if (!path.endsWith("/")) {
            path = path + "/";
        }

        if (path.startsWith(MEDIASTORE_ROOT_PREFIX)) {
            return normalizeRelativeDir(path.substring(MEDIASTORE_ROOT_PREFIX.length()));
        }

        return normalizeRelativeDir(path);
    }

    /**
     * Pure directory tree builder used by MediaStore queries and unit tests.
     * Returns immediate child folders (descending by name) and direct audio files
     * (descending by lastModifiedMillis) for {@code targetRelativeDir}.
     */
    @NonNull
    static DirectoryListing buildListingFromEntries(
            @NonNull final List<RecordingItem> allItems,
            @NonNull final String targetRelativeDir
    ) {
        final String target = normalizeRelativeDir(targetRelativeDir);
        final String targetPrefix = target.isEmpty() ? "" : target + "/";

        final Set<String> subdirs = new LinkedHashSet<>();
        final List<RecordingItem> files = new ArrayList<>();

        for (final RecordingItem item : allItems) {
            if (!isSupportedAudioExtension(item.getExtension())) {
                continue;
            }

            final String itemDir = normalizeRelativeDir(item.getRelativeDir());

            if (itemDir.equals(target)) {
                files.add(item);
                continue;
            }

            if (targetPrefix.isEmpty()) {
                final int slash = itemDir.indexOf('/');
                final String topChild = slash >= 0 ? itemDir.substring(0, slash) : itemDir;

                if (!topChild.isEmpty()) {
                    subdirs.add(topChild);
                }
            } else if (itemDir.startsWith(targetPrefix)) {
                final String remainder = itemDir.substring(targetPrefix.length());
                final int slash = remainder.indexOf('/');
                final String nextChild = slash >= 0 ? remainder.substring(0, slash) : remainder;

                if (!nextChild.isEmpty()) {
                    subdirs.add(nextChild);
                }
            }
        }

        final List<String> sortedDirs = new ArrayList<>(subdirs);
        sortedDirs.sort(Comparator.reverseOrder());

        files.sort((a, b) -> {
            final int byTime = Long.compare(b.getLastModifiedMillis(), a.getLastModifiedMillis());
            return byTime != 0 ? byTime : b.getDisplayName().compareToIgnoreCase(a.getDisplayName());
        });

        return new DirectoryListing(target, sortedDirs, files);
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    @NonNull
    private static DirectoryListing listViaMediaStore(
            @NonNull final ContentResolver resolver,
            @NonNull final String relativeDir
    ) {
        final Uri collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        final String[] projection = new String[]{
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.RELATIVE_PATH,
                MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DATE_MODIFIED,
                MediaStore.Audio.Media.DURATION,
        };
        final String selection = MediaStore.Audio.Media.RELATIVE_PATH + " LIKE ? AND " + MediaStore.Audio.Media.IS_PENDING + " = 0";
        final String[] selectionArgs = new String[]{MEDIASTORE_ROOT_PREFIX + "%"};

        final List<RecordingItem> allItems = new ArrayList<>();

        try (Cursor cursor = resolver.query(collection, projection, selection, selectionArgs, null)) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    final long id = cursor.getLong(0);
                    final String displayName = cursor.getString(1);
                    final String relPathRaw = cursor.getString(2);
                    final String mimeTypeRaw = cursor.getString(3);
                    final long sizeBytes = cursor.getLong(4);
                    final long dateModifiedSec = cursor.getLong(5);
                    final long durationMs = cursor.getLong(6);

                    if (displayName == null) {
                        continue;
                    }

                    final String ext = extractExtension(displayName);
                    if (!isSupportedAudioExtension(ext)) {
                        continue;
                    }

                    final String itemDir = stripMediaStoreRoot(relPathRaw);
                    final String relPath = itemDir.isEmpty() ? displayName : itemDir + "/" + displayName;
                    final Uri uri = ContentUris.withAppendedId(collection, id);
                    final String mimeType = mimeTypeRaw != null ? mimeTypeRaw : mimeTypeForExtension(ext);

                    allItems.add(new RecordingItem(
                            displayName,
                            itemDir,
                            relPath,
                            uri,
                            null,
                            ext,
                            mimeType,
                            sizeBytes,
                            dateModifiedSec * 1000L,
                            durationMs,
                            false
                    ));
                }
            }
        }

        return buildListingFromEntries(allItems, relativeDir);
    }

    @NonNull
    private static DirectoryListing listViaFileSystem(@NonNull final String relativeDir) {
        final File root = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                ROOT_DIRECTORY
        );
        final File currentDir = relativeDir.isEmpty() ? root : new File(root, relativeDir);
        final File[] children = currentDir.listFiles();

        if (children == null) {
            return new DirectoryListing(relativeDir, Collections.emptyList(), Collections.emptyList());
        }

        final List<String> subdirs = new ArrayList<>();
        final List<RecordingItem> files = new ArrayList<>();

        for (final File child : children) {
            if (child.isDirectory()) {
                subdirs.add(child.getName());
                continue;
            }

            if (!child.isFile()) {
                continue;
            }

            final String name = child.getName();
            final String ext = extractExtension(name);

            if (!isSupportedAudioExtension(ext)) {
                continue;
            }

            final String relPath = relativeDir.isEmpty() ? name : relativeDir + "/" + name;

            files.add(new RecordingItem(
                    name,
                    relativeDir,
                    relPath,
                    null,
                    child.getAbsolutePath(),
                    ext,
                    mimeTypeForExtension(ext),
                    child.length(),
                    child.lastModified(),
                    0L,
                    false
            ));
        }

        subdirs.sort(Comparator.reverseOrder());
        files.sort((a, b) -> {
            final int byTime = Long.compare(b.getLastModifiedMillis(), a.getLastModifiedMillis());
            return byTime != 0 ? byTime : b.getDisplayName().compareToIgnoreCase(a.getDisplayName());
        });

        return new DirectoryListing(relativeDir, subdirs, files);
    }
}
