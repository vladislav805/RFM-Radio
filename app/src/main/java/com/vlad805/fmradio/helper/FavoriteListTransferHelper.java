package com.vlad805.fmradio.helper;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.vlad805.fmradio.controller.FavoriteController;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Handles SAF-based import and export of favorite station lists (JSON and ZIP).
 */
public final class FavoriteListTransferHelper {
	public static final class ImportResult {
		public final int count;
		@Nullable
		public final String lastImportedName;
		public final boolean switchFailed;

		ImportResult(final int count, @Nullable final String lastImportedName, final boolean switchFailed) {
			this.count = count;
			this.lastImportedName = lastImportedName;
			this.switchFailed = switchFailed;
		}
	}

	private final ContentResolver mContentResolver;
	private final FavoriteController mController;

	public FavoriteListTransferHelper(
			@NonNull final Context context,
			@NonNull final FavoriteController controller
	) {
		mContentResolver = context.getApplicationContext().getContentResolver();
		mController = controller;
	}

	@NonNull
	public static Intent createImportIntent() {
		return new Intent(Intent.ACTION_OPEN_DOCUMENT)
				.addCategory(Intent.CATEGORY_OPENABLE)
				.setType("*/*")
				.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
				.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
						"application/json",
						"application/zip",
						"application/x-zip-compressed",
						"text/json",
						"text/plain",
						"application/octet-stream"
				});
	}

	@NonNull
	public static Intent createExportSingleIntent(@NonNull final String listName) {
		return new Intent(Intent.ACTION_CREATE_DOCUMENT)
				.addCategory(Intent.CATEGORY_OPENABLE)
				.setType("application/json")
				.putExtra(Intent.EXTRA_TITLE, listName + ".json");
	}

	@NonNull
	public static Intent createExportAllIntent() {
		return new Intent(Intent.ACTION_CREATE_DOCUMENT)
				.addCategory(Intent.CATEGORY_OPENABLE)
				.setType("application/zip")
				.putExtra(Intent.EXTRA_TITLE, "favorites.zip");
	}

	@NonNull
	public ImportResult importFromIntent(@NonNull final Intent data) {
		int importedCount = 0;
		String lastImportedName = null;

		if (data.getClipData() != null) {
			for (int i = 0; i < data.getClipData().getItemCount(); i++) {
				final Uri uri = data.getClipData().getItemAt(i).getUri();
				final ImportResult result = importUri(uri);
				if (result.count > 0) {
					importedCount += result.count;
					lastImportedName = result.lastImportedName;
				}
			}
		} else if (data.getData() != null) {
			final ImportResult result = importUri(data.getData());
			if (result.count > 0) {
				importedCount = result.count;
				lastImportedName = result.lastImportedName;
			}
		}

		if (importedCount <= 0) {
			return new ImportResult(0, null, false);
		}

		boolean switchFailed = false;
		if (lastImportedName != null) {
			try {
				mController.setCurrentFavoriteList(lastImportedName);
			} catch (final FileNotFoundException e) {
				switchFailed = true;
			}
		}

		return new ImportResult(importedCount, lastImportedName, switchFailed);
	}

	public boolean exportSingleList(@NonNull final String listName, @NonNull final Uri uri) {
		try (final OutputStream outputStream = mContentResolver.openOutputStream(uri, "wt")) {
			if (outputStream == null) {
				return false;
			}
			mController.exportList(listName, outputStream);
			return true;
		} catch (final IOException e) {
			return false;
		}
	}

	/**
	 * Exports all favorite lists into a ZIP archive.
	 * @return number of exported lists, or -1 on failure.
	 */
	public int exportAllLists(@NonNull final Uri uri) {
		try (final OutputStream outputStream = mContentResolver.openOutputStream(uri, "w")) {
			if (outputStream == null) {
				return -1;
			}
			final int count = mController.getFavoriteLists().size();
			mController.exportAllLists(outputStream);
			return count;
		} catch (final IOException e) {
			return -1;
		}
	}

	@NonNull
	private ImportResult importUri(@NonNull final Uri uri) {
		try (final InputStream inputStream = mContentResolver.openInputStream(uri)) {
			if (inputStream == null) {
				return new ImportResult(0, null, false);
			}

			final String displayName = getUriDisplayName(uri);
			if (displayName.toLowerCase(Locale.ENGLISH).endsWith(".zip")) {
				return importZipArchive(inputStream);
			}

			return new ImportResult(1, mController.importList(displayName, inputStream), false);
		} catch (final IOException e) {
			return new ImportResult(0, null, false);
		}
	}

	@NonNull
	private ImportResult importZipArchive(@NonNull final InputStream inputStream) throws IOException {
		int importedCount = 0;
		String lastImportedName = null;

		try (final ZipInputStream zipInputStream = new ZipInputStream(inputStream)) {
			ZipEntry entry;
			while ((entry = zipInputStream.getNextEntry()) != null) {
				if (entry.isDirectory() || !entry.getName().toLowerCase(Locale.ENGLISH).endsWith(".json")) {
					zipInputStream.closeEntry();
					continue;
				}

				final byte[] content = readZipEntry(zipInputStream);
				lastImportedName = mController.importList(
						entry.getName(),
						new ByteArrayInputStream(content)
				);
				importedCount++;
				zipInputStream.closeEntry();
			}
		}

		return new ImportResult(importedCount, lastImportedName, false);
	}

	@NonNull
	private byte[] readZipEntry(@NonNull final ZipInputStream zipInputStream) throws IOException {
		final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		final byte[] buffer = new byte[8192];
		int read;
		while ((read = zipInputStream.read(buffer)) != -1) {
			if (outputStream.size() + read > FavoriteController.MAX_IMPORT_BYTES) {
				throw new IOException("Favorites import file is too large");
			}
			outputStream.write(buffer, 0, read);
		}
		return outputStream.toByteArray();
	}

	@NonNull
	private String getUriDisplayName(@NonNull final Uri uri) {
		try (final Cursor cursor = mContentResolver.query(
				uri,
				new String[] { OpenableColumns.DISPLAY_NAME },
				null,
				null,
				null
		)) {
			if (cursor != null && cursor.moveToFirst()) {
				final int columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
				if (columnIndex >= 0) {
					final String name = cursor.getString(columnIndex);
					if (name != null) {
						return name;
					}
				}
			}
		}

		final String lastSegment = uri.getLastPathSegment();
		return lastSegment != null ? lastSegment : "imported.json";
	}
}
