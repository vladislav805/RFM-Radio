package com.vlad805.fmradio.recordings;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Browses, inspects, shares, and deletes recordings inside {@code Music/RFM-Recordings}.
 */
public final class RecordingsRepository {
    public static final int SORT_NEWEST_FIRST = 0;
    public static final int SORT_OLDEST_FIRST = 1;
    public static final int SORT_NAME_ASC = 2;
    public static final int SORT_NAME_DESC = 3;

    private static final String ROOT_DIRECTORY = "RFM-Recordings";
    private static final String MEDIASTORE_ROOT_PREFIX = "Music/" + ROOT_DIRECTORY + "/";
    private static final Pattern INVALID_FILENAME_CHARS = Pattern.compile("[\\x00-\\x1F/\\\\:*?\"<>|]");

    private RecordingsRepository() {}

    @NonNull
    public static DirectoryListing listDirectory(
            @NonNull final Context context,
            @NonNull final String relativeDir
    ) {
        return listDirectory(context, relativeDir, SORT_NEWEST_FIRST);
    }

    @NonNull
    public static DirectoryListing listDirectory(
            @NonNull final Context context,
            @NonNull final String relativeDir,
            final int sortOrder
    ) {
        final String normalizedDir = normalizeRelativeDir(relativeDir);

        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? listViaMediaStore(context.getContentResolver(), normalizedDir, sortOrder)
                : listViaFileSystem(normalizedDir, sortOrder);
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

    public static int deleteDirectory(
            @NonNull final Context context,
            @NonNull final String relativeDir
    ) throws IOException {
        final String target = normalizeRelativeDir(relativeDir);

        if (target.isEmpty()) {
            throw new IOException("Refusing to delete root recordings directory");
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            final List<RecordingItem> all = queryAllMediaStoreItems(context.getContentResolver());
            final String prefix = target + "/";
            int deleted = 0;

            for (final RecordingItem item : all) {
                final String dir = normalizeRelativeDir(item.getRelativeDir());

                if (dir.equals(target) || dir.startsWith(prefix)) {
                    if (delete(context, item)) {
                        deleted++;
                    }
                }
            }

            return deleted;
        }

        final File root = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                ROOT_DIRECTORY
        );
        final File dirFile = new File(root, target);

        if (!dirFile.exists()) {
            return 0;
        }

        return deleteRecursiveFile(dirFile);
    }

    private static int deleteRecursiveFile(@NonNull final File node) throws IOException {
        int count = 0;

        if (node.isDirectory()) {
            final File[] children = node.listFiles();
            if (children != null) {
                for (final File child : children) {
                    count += deleteRecursiveFile(child);
                }
            }
        }

        if (!node.delete()) {
            throw new IOException("Cannot delete " + node.getName());
        }

        return node.isDirectory() ? count : count + 1;
    }

    @NonNull
    public static RecordingItem rename(
            @NonNull final Context context,
            @NonNull final RecordingItem item,
            @NonNull final String requestedBaseName
    ) throws IOException {
        final String cleanBase = sanitizeRenameBaseName(requestedBaseName, item.getExtension());
        if (cleanBase == null) {
            throw new IllegalArgumentException("Invalid file name");
        }

        final String newDisplayName = item.getExtension().isEmpty()
                ? cleanBase
                : cleanBase + "." + item.getExtension();

        if (newDisplayName.equals(item.getDisplayName())) {
            return item;
        }

        final String newRelPath = item.getRelativeDir().isEmpty()
                ? newDisplayName
                : item.getRelativeDir() + "/" + newDisplayName;

        if (item.getContentUri() != null) {
            final ContentValues values = new ContentValues();
            values.put(MediaStore.Audio.Media.DISPLAY_NAME, newDisplayName);
            final int updated = context.getContentResolver().update(item.getContentUri(), values, null, null);

            if (updated <= 0) {
                throw new IOException("MediaStore refused rename");
            }

            return new RecordingItem(
                    newDisplayName,
                    item.getRelativeDir(),
                    newRelPath,
                    item.getContentUri(),
                    item.getFilePath(),
                    item.getExtension(),
                    item.getMimeType(),
                    item.getSizeBytes(),
                    item.getLastModifiedMillis(),
                    item.getDurationMillis(),
                    item.hasRfmMetadata()
            );
        }

        if (item.getFilePath() != null) {
            final File src = new File(item.getFilePath());
            final File parent = src.getParentFile();
            if (parent == null) {
                throw new IOException("Missing parent directory");
            }

            final File dst = new File(parent, newDisplayName);
            if (dst.exists()) {
                throw new IOException("Target file already exists");
            }

            if (!src.renameTo(dst)) {
                throw new IOException("Could not rename file");
            }

            return new RecordingItem(
                    newDisplayName,
                    item.getRelativeDir(),
                    newRelPath,
                    null,
                    dst.getAbsolutePath(),
                    item.getExtension(),
                    item.getMimeType(),
                    item.getSizeBytes(),
                    item.getLastModifiedMillis(),
                    item.getDurationMillis(),
                    item.hasRfmMetadata()
            );
        }

        throw new IOException("Recording has neither contentUri nor filePath");
    }

    @Nullable
    public static String sanitizeRenameBaseName(
            @Nullable final String rawName,
            @NonNull final String extension
    ) {
        if (rawName == null) {
            return null;
        }

        String trimmed = rawName.trim();
        if (!extension.isEmpty()) {
            final String suffix = "." + extension;
            if (trimmed.toLowerCase(Locale.US).endsWith(suffix.toLowerCase(Locale.US))) {
                trimmed = trimmed.substring(0, trimmed.length() - suffix.length()).trim();
            }
        }

        if (trimmed.isEmpty() || ".".equals(trimmed) || "..".equals(trimmed)) {
            return null;
        }

        if (INVALID_FILENAME_CHARS.matcher(trimmed).find()) {
            return null;
        }

        return trimmed;
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
        return buildListingFromEntries(allItems, targetRelativeDir, SORT_NEWEST_FIRST);
    }

    @NonNull
    static DirectoryListing buildListingFromEntries(
            @NonNull final List<RecordingItem> allItems,
            @NonNull final String targetRelativeDir,
            final int sortOrder
    ) {
        final String target = normalizeRelativeDir(targetRelativeDir);
        final String targetPrefix = target.isEmpty() ? "" : target + "/";

        final Set<String> subdirs = new LinkedHashSet<>();
        final Map<String, Integer> subdirCounts = new HashMap<>();
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
                    subdirCounts.put(topChild, subdirCounts.getOrDefault(topChild, 0) + 1);
                }
            } else if (itemDir.startsWith(targetPrefix)) {
                final String remainder = itemDir.substring(targetPrefix.length());
                final int slash = remainder.indexOf('/');
                final String nextChild = slash >= 0 ? remainder.substring(0, slash) : remainder;

                if (!nextChild.isEmpty()) {
                    subdirs.add(nextChild);
                    subdirCounts.put(nextChild, subdirCounts.getOrDefault(nextChild, 0) + 1);
                }
            }
        }

        final List<String> sortedDirs = new ArrayList<>(subdirs);
        sortedDirs.sort(Comparator.reverseOrder());

        sortRecordings(files, sortOrder);

        return new DirectoryListing(target, sortedDirs, subdirCounts, files);
    }

    static void sortRecordings(@NonNull final List<RecordingItem> files, final int sortOrder) {
        switch (sortOrder) {
            case SORT_OLDEST_FIRST: {
                files.sort((a, b) -> {
                    final int byTime = Long.compare(a.getLastModifiedMillis(), b.getLastModifiedMillis());
                    return byTime != 0 ? byTime : a.getDisplayName().compareToIgnoreCase(b.getDisplayName());
                });
                break;
            }

            case SORT_NAME_ASC: {
                files.sort((a, b) -> a.getDisplayName().compareToIgnoreCase(b.getDisplayName()));
                break;
            }

            case SORT_NAME_DESC: {
                files.sort((a, b) -> b.getDisplayName().compareToIgnoreCase(a.getDisplayName()));
                break;
            }

            case SORT_NEWEST_FIRST:
            default: {
                files.sort((a, b) -> {
                    final int byTime = Long.compare(b.getLastModifiedMillis(), a.getLastModifiedMillis());
                    return byTime != 0 ? byTime : b.getDisplayName().compareToIgnoreCase(a.getDisplayName());
                });
                break;
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    @NonNull
    private static DirectoryListing listViaMediaStore(
            @NonNull final ContentResolver resolver,
            @NonNull final String relativeDir,
            final int sortOrder
    ) {
        return buildListingFromEntries(queryAllMediaStoreItems(resolver), relativeDir, sortOrder);
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    @NonNull
    private static List<RecordingItem> queryAllMediaStoreItems(@NonNull final ContentResolver resolver) {
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

        return allItems;
    }

    @NonNull
    private static DirectoryListing listViaFileSystem(
            @NonNull final String relativeDir,
            final int sortOrder
    ) {
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
        final Map<String, Integer> subdirCounts = new HashMap<>();
        final List<RecordingItem> files = new ArrayList<>();

        for (final File child : children) {
            if (child.isDirectory()) {
                final String dirName = child.getName();
                subdirs.add(dirName);
                subdirCounts.put(dirName, countAudioFilesRecursive(child));
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
        sortRecordings(files, sortOrder);

        return new DirectoryListing(relativeDir, subdirs, subdirCounts, files);
    }

    private static int countAudioFilesRecursive(@NonNull final File dir) {
        final File[] children = dir.listFiles();
        if (children == null) {
            return 0;
        }

        int count = 0;

        for (final File child : children) {
            if (child.isDirectory()) {
                count += countAudioFilesRecursive(child);
            } else if (child.isFile() && isSupportedAudioExtension(extractExtension(child.getName()))) {
                count++;
            }
        }

        return count;
    }
}
