package com.vlad805.fmradio.activity;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.RecyclerView;
import com.vlad805.fmradio.C;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.Utils;
import com.vlad805.fmradio.controller.FavoriteController;
import com.vlad805.fmradio.controller.RadioController;
import com.vlad805.fmradio.controller.TunerStatus;
import com.vlad805.fmradio.helper.EditTextDialog;
import com.vlad805.fmradio.helper.ProgressDialog;
import com.vlad805.fmradio.helper.Toast;
import com.vlad805.fmradio.models.FavoriteStation;

import java.io.FileNotFoundException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class FavoriteListsActivity extends AppCompatActivity {
	private static final int REQUEST_CODE_IMPORT_FAVORITES = 2001;
	private static final int REQUEST_CODE_EXPORT_FAVORITES = 2002;
	private static final int REQUEST_CODE_EXPORT_ALL_FAVORITES = 2003;
	private static final int REQUEST_CODE_EXPORT_SELECTED_FAVORITES = 2004;
	private static final int MENU_LIST_RENAME = 1;
	private static final int MENU_LIST_REMOVE = 2;
	private static final int MENU_LIST_EXPORT = 3;
	private ProgressDialog mProgress;
	private Menu mMenu;
	private String mCurrentNameList;

	private RadioController mRadioCtl;

	private FavoriteController mController;
	private List<FavoriteListItem> mFavoriteListItems = new ArrayList<>();
	private RecyclerView mRecyclerView;
	private FavoriteListsAdapter mListsAdapter;
	private final Set<String> mSelectedLists = new LinkedHashSet<>();
	private boolean mSelectionMode;
	private String mPendingExportListName;

	private Toast mToast;

	private static final class FavoriteListItem {
		final String name;
		final int stationsCount;
		final boolean current;

		FavoriteListItem(final String name, final int stationsCount, final boolean current) {
			this.name = name;
			this.stationsCount = stationsCount;
			this.current = current;
		}
	}

	private static final class ImportResult {
		final int count;
		final String lastImportedName;

		ImportResult(final int count, final String lastImportedName) {
			this.count = count;
			this.lastImportedName = lastImportedName;
		}
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_favorite_lists);

		mRadioCtl = new RadioController(this);

		mToast = Toast.create(this);
		mController = new FavoriteController(this);

		initUI();
	}

	@Override
	protected void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
		if (resultCode == Activity.RESULT_OK && data != null) {
			switch (requestCode) {
				case REQUEST_CODE_IMPORT_FAVORITES: {
					importFavoriteLists(data);
					break;
				}

				case REQUEST_CODE_EXPORT_FAVORITES: {
					if (data.getData() != null) {
						exportFavoriteList(data.getData());
					}
					break;
				}

				case REQUEST_CODE_EXPORT_ALL_FAVORITES: {
					if (data.getData() != null) {
						exportAllFavoriteLists(data.getData());
					}
					break;
				}

				case REQUEST_CODE_EXPORT_SELECTED_FAVORITES: {
					if (data.getData() != null) {
						if (exportSelectedFavoriteLists(data.getData())) {
							exitSelectionMode();
						}
					}
					break;
				}
			}
		}

		super.onActivityResult(requestCode, resultCode, data);
	}

	/**
	 * Initialize UI
	 */
	private void initUI() {
		final Toolbar toolbar = findViewById(R.id.favorite_lists_toolbar);
		mRecyclerView = findViewById(R.id.favorite_lists_content);
		mListsAdapter = new FavoriteListsAdapter();
		mRecyclerView.setAdapter(mListsAdapter);

		setSupportActionBar(toolbar);

		final ActionBar ab = getSupportActionBar();
		if (ab != null) {
			ab.setDisplayHomeAsUpEnabled(true);
			ab.setTitle(R.string.toolbar_favorite_title);
		}

		reloadLists();
	}

	/**
	 * Update list of favorite lists in recycler
	 */
	private void reloadLists() {
		mCurrentNameList = mController.getCurrentFavoriteList();
		mFavoriteListItems.clear();
		for (final String listName : mController.getFavoriteLists()) {
			mFavoriteListItems.add(new FavoriteListItem(
					listName,
					mController.getStationsCountInList(listName),
					listName.equals(mCurrentNameList)
			));
		}
		mListsAdapter.notifyDataSetChanged();
		reloadContent();
		updateSelectionUi();
	}

	private void reloadContent() {
		mController.reload();
	}

	private void setCurrentList(final String item) {
		if (mSelectionMode) {
			toggleSelection(item);
			return;
		}

		try {
			mController.setCurrentFavoriteList(item);
			mCurrentNameList = item;
			reloadLists();

			final Intent intent = new Intent().putExtra("changed", true);
			setResult(Activity.RESULT_OK, intent);

			sendBroadcast(new Intent(C.Event.FAVORITE_LIST_CHANGED));
		} catch (final FileNotFoundException e) {
			mToast.text("Not found this list").show();
		}
	}

	private void enterSelectionMode(final String initialSelection) {
		if (!mSelectionMode) {
			mSelectionMode = true;
		}
		toggleSelection(initialSelection);
	}

	private void exitSelectionMode() {
		mSelectionMode = false;
		mSelectedLists.clear();
		updateSelectionUi();
	}

	private void toggleSelection(final String listName) {
		if (mSelectedLists.contains(listName)) {
			mSelectedLists.remove(listName);
		} else {
			mSelectedLists.add(listName);
		}

		if (mSelectedLists.isEmpty()) {
			mSelectionMode = false;
		}

		updateSelectionUi();
	}

	private void updateSelectionUi() {
		if (mListsAdapter != null) {
			mListsAdapter.notifyDataSetChanged();
		}

		invalidateOptionsMenu();

		final ActionBar actionBar = getSupportActionBar();
		if (actionBar != null) {
			if (mSelectionMode) {
				actionBar.setTitle(getString(R.string.favorite_lists_selected_count, mSelectedLists.size()));
				actionBar.setSubtitle(null);
			} else {
				actionBar.setTitle(R.string.toolbar_favorite_title);
				actionBar.setSubtitle(null);
			}
		}
	}

	@Override
	public boolean onCreateOptionsMenu(final Menu menu) {
		super.onCreateOptionsMenu(menu);
		mMenu = menu;
		getMenuInflater().inflate(R.menu.favorite, menu);
		menu.findItem(R.id.menu_favorite_add).setVisible(true);
		menu.findItem(R.id.menu_favorite_remove).setVisible(false);
		menu.findItem(R.id.menu_favorite_rename).setVisible(false);
		menu.findItem(R.id.menu_favorite_import).setVisible(true);
		menu.findItem(R.id.menu_favorite_export_selected).setVisible(false);
		menu.findItem(R.id.menu_favorite_remove_selected).setVisible(false);
		return true;
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		final boolean hasSelection = !mSelectedLists.isEmpty();
		menu.findItem(R.id.menu_favorite_add).setVisible(!mSelectionMode);
		menu.findItem(R.id.menu_favorite_import).setVisible(!mSelectionMode);
		menu.findItem(R.id.menu_favorite_search).setVisible(!mSelectionMode);
		menu.findItem(R.id.menu_favorite_export_all).setVisible(!mSelectionMode);
		menu.findItem(R.id.menu_favorite_export_selected).setVisible(mSelectionMode && hasSelection);
		menu.findItem(R.id.menu_favorite_remove_selected).setVisible(mSelectionMode && hasSelection);
		return super.onPrepareOptionsMenu(menu);
	}

	@SuppressLint("NonConstantResourceId")
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();

		if (itemId == R.id.menu_favorite_add) {
			addDialog();
		} else if (itemId == R.id.menu_favorite_import) {
			openImportFavoritePicker();
		} else if (itemId == R.id.menu_favorite_search) {
			searchDialog();
		} else if (itemId == R.id.menu_favorite_export_all) {
			openExportAllFavoritesPicker();
		} else if (itemId == R.id.menu_favorite_export_selected) {
			openExportSelectedFavoritesPicker();
		} else if (itemId == R.id.menu_favorite_remove_selected) {
			removeSelectedDialog();
		} else if (itemId == android.R.id.home) {
			if (mSelectionMode) {
				exitSelectionMode();
			} else {
				finish();
			}
		}

		return super.onOptionsItemSelected(item);
	}

	private void addDialog() {
		final EditTextDialog dialog = new EditTextDialog(this, "", title -> {
			try {
				mController.addList(title);
				mToast.text("Created list '" + title + "'");
				reloadLists();
			} catch (Error e) {
				mToast.text(e.getMessage()).show();
			}
		});
		final EditText et = dialog.getView();
		dialog.setTitle(R.string.popup_favorite_list_create).setOnKeyListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {
				String value = et.getText().toString();

				if (mController.isAlreadyExists(value)) {
					et.setError(getString(R.string.favorite_list_create_error_already_exists));
					return;
				}

				if (mController.isInvalidName(value)) {
					et.setError(getString(R.string.favorite_list_create_error_invalid_name));
					return;
				}

				et.setError(null);
			}

			@Override
			public void afterTextChanged(Editable s) {}
		});

		dialog.open();
	}

	private void renameDialog(final String listName) {
		mCurrentNameList = listName;
		if (FavoriteController.DEFAULT_NAME.equals(mCurrentNameList)) {
			mToast.text(getString(R.string.favorite_list_rename_default_error)).show();
			return;
		}

		final EditTextDialog dialog = new EditTextDialog(this, mCurrentNameList, title -> {
			try {
				if (mCurrentNameList.equals(title)) {
					return;
				}

				if (!mController.renameList(mCurrentNameList, title)) {
					mToast.text(getString(R.string.favorite_list_rename_default_error)).show();
					return;
				}
				mCurrentNameList = title;
				reloadLists();
				reloadContent();
				setResult(Activity.RESULT_OK, new Intent().putExtra("changed", true));
				sendBroadcast(new Intent(C.Event.FAVORITE_LIST_CHANGED));
				mToast.text("Renamed list to '" + title + "'").show();
			} catch (Error e) {
				mToast.text(e.getMessage()).show();
			}
		});
		final EditText et = dialog.getView();
		dialog.setTitle(R.string.popup_favorite_list_rename).setOnKeyListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {
				final String value = et.getText().toString();

				if (!value.equals(mCurrentNameList) && mController.isAlreadyExists(value)) {
					et.setError(getString(R.string.favorite_list_create_error_already_exists));
					return;
				}

				if (mController.isInvalidName(value)) {
					et.setError(getString(R.string.favorite_list_create_error_invalid_name));
					return;
				}

				et.setError(null);
			}

			@Override
			public void afterTextChanged(Editable s) {}
		});

		dialog.open();
	}

	private void openImportFavoritePicker() {
		final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
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
		startActivityForResult(intent, REQUEST_CODE_IMPORT_FAVORITES);
	}

	private void openExportFavoritePicker(final String listName) {
		mPendingExportListName = listName;
		final Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
				.addCategory(Intent.CATEGORY_OPENABLE)
				.setType("application/json")
				.putExtra(Intent.EXTRA_TITLE, listName + ".json");
		startActivityForResult(intent, REQUEST_CODE_EXPORT_FAVORITES);
	}

	private void openExportAllFavoritesPicker() {
		final Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
				.addCategory(Intent.CATEGORY_OPENABLE)
				.setType("application/zip")
				.putExtra(Intent.EXTRA_TITLE, "favorites.zip");
		startActivityForResult(intent, REQUEST_CODE_EXPORT_ALL_FAVORITES);
	}

	private void openExportSelectedFavoritesPicker() {
		final Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
				.addCategory(Intent.CATEGORY_OPENABLE)
				.setType("application/zip")
				.putExtra(Intent.EXTRA_TITLE, "favorites-selected.zip");
		startActivityForResult(intent, REQUEST_CODE_EXPORT_SELECTED_FAVORITES);
	}

	private void importFavoriteLists(final Intent data) {
		int importedCount = 0;
		String lastImportedName = null;

		if (data.getClipData() != null) {
			for (int i = 0; i < data.getClipData().getItemCount(); i++) {
				final Uri uri = data.getClipData().getItemAt(i).getUri();
				final ImportResult result = importFavoriteList(uri);
				if (result.count > 0) {
					importedCount += result.count;
					lastImportedName = result.lastImportedName;
				}
			}
		} else if (data.getData() != null) {
			final ImportResult result = importFavoriteList(data.getData());
			if (result.count > 0) {
				importedCount = result.count;
				lastImportedName = result.lastImportedName;
			}
		}

		if (importedCount <= 0) {
			mToast.text(R.string.favorite_list_import_error).show();
			return;
		}

		if (lastImportedName != null) {
			try {
				mController.setCurrentFavoriteList(lastImportedName);
				mCurrentNameList = lastImportedName;
			} catch (FileNotFoundException e) {
				mToast.text(R.string.favorite_list_import_error).show();
				return;
			}
		}

		reloadLists();
		reloadContent();
		setResult(Activity.RESULT_OK, new Intent().putExtra("changed", true));
		sendBroadcast(new Intent(C.Event.FAVORITE_LIST_CHANGED));

		if (importedCount == 1 && lastImportedName != null) {
			mToast.text(getString(R.string.favorite_list_import_success, lastImportedName)).show();
		} else {
			mToast.text(getString(R.string.favorite_list_import_success_many, importedCount)).show();
		}
	}

	private ImportResult importFavoriteList(final Uri uri) {
		try (final InputStream inputStream = getContentResolver().openInputStream(uri)) {
			if (inputStream == null) {
				return new ImportResult(0, null);
			}

			if (isZipArchive(getDisplayName(uri))) {
				return importFavoriteArchive(inputStream);
			}

			return new ImportResult(1, mController.importList(getDisplayName(uri), inputStream));
		} catch (IOException e) {
			return new ImportResult(0, null);
		}
	}

	private ImportResult importFavoriteArchive(final InputStream inputStream) throws IOException {
		int importedCount = 0;
		String lastImportedName = null;

		try (final ZipInputStream zipInputStream = new ZipInputStream(inputStream)) {
			ZipEntry entry;
			while ((entry = zipInputStream.getNextEntry()) != null) {
				if (entry.isDirectory() || !entry.getName().toLowerCase().endsWith(".json")) {
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

		return new ImportResult(importedCount, lastImportedName);
	}

	private void exportFavoriteList(final Uri uri) {
		try (final java.io.OutputStream outputStream = getContentResolver().openOutputStream(uri, "wt")) {
			if (outputStream == null) {
				mToast.text(R.string.favorite_list_export_error).show();
				return;
			}

			final String listName = mPendingExportListName != null
					? mPendingExportListName
					: mController.getCurrentFavoriteList();
			mController.exportList(listName, outputStream);
			mToast.text(getString(R.string.favorite_list_export_success, listName)).show();
			mPendingExportListName = null;
		} catch (IOException e) {
			mToast.text(R.string.favorite_list_export_error).show();
			mPendingExportListName = null;
		}
	}

	private void exportAllFavoriteLists(final Uri uri) {
		try (final java.io.OutputStream outputStream = getContentResolver().openOutputStream(uri, "w")) {
			if (outputStream == null) {
				mToast.text(R.string.favorite_list_export_all_error).show();
				return;
			}

			final int exportedCount = mController.getFavoriteLists().size();
			mController.exportAllLists(outputStream);
			mToast.text(getString(R.string.favorite_list_export_all_success, exportedCount)).show();
		} catch (IOException e) {
			mToast.text(R.string.favorite_list_export_all_error).show();
		}
	}

	private boolean exportSelectedFavoriteLists(final Uri uri) {
		try (final java.io.OutputStream outputStream = getContentResolver().openOutputStream(uri, "w")) {
			if (outputStream == null) {
				mToast.text(R.string.favorite_list_export_all_error).show();
				return false;
			}

			final List<String> names = new ArrayList<>(mSelectedLists);
			mController.exportLists(outputStream, names);
			mToast.text(getString(R.string.favorite_list_export_all_success, names.size())).show();
			return true;
		} catch (IOException e) {
			mToast.text(R.string.favorite_list_export_all_error).show();
			return false;
		}
	}

	private boolean isZipArchive(final String displayName) {
		return displayName.toLowerCase().endsWith(".zip");
	}

	private byte[] readZipEntry(final ZipInputStream zipInputStream) throws IOException {
		final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		final byte[] buffer = new byte[8192];
		int read;
		while ((read = zipInputStream.read(buffer)) != -1) {
			outputStream.write(buffer, 0, read);
		}
		return outputStream.toByteArray();
	}

	private String getDisplayName(final Uri uri) {
		try (final Cursor cursor = getContentResolver().query(uri, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
			if (cursor != null && cursor.moveToFirst()) {
				final int columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
				if (columnIndex >= 0) {
					return cursor.getString(columnIndex);
				}
			}
		}

		final String lastSegment = uri.getLastPathSegment();
		return lastSegment != null ? lastSegment : "imported.json";
	}

	private void removeDialog(final String listName) {
		mCurrentNameList = listName;

		final AlertDialog.Builder dialog = new AlertDialog.Builder(this)
				.setTitle(R.string.favorite_list_remove_title)
				.setMessage(getString(R.string.favorite_list_remove_message, mCurrentNameList))
				.setCancelable(false)
				.setPositiveButton(android.R.string.yes, (dlg, buttonId) -> {
					mController.removeList(mCurrentNameList);
					mController.reload();
					reloadLists();
					reloadContent();
				})
				.setNegativeButton(android.R.string.no, (dlg, buttonId) -> {})
				.setIcon(android.R.drawable.ic_dialog_alert);
		dialog.create().show();
	}

	private void removeSelectedDialog() {
		final int selectedCount = mSelectedLists.size();
		if (selectedCount <= 0) {
			return;
		}

		new AlertDialog.Builder(this)
				.setTitle(R.string.favorite_list_remove_title)
				.setMessage(getString(R.string.favorite_lists_remove_selected_confirm, selectedCount))
				.setCancelable(false)
				.setPositiveButton(android.R.string.yes, (dlg, buttonId) -> {
					int removed = 0;
					for (final String name : new ArrayList<>(mSelectedLists)) {
						if (FavoriteController.DEFAULT_NAME.equals(name)) {
							continue;
						}
						if (mController.removeList(name)) {
							removed++;
						}
					}

					exitSelectionMode();
					reloadLists();
					if (removed > 0) {
						setResult(Activity.RESULT_OK, new Intent().putExtra("changed", true));
						sendBroadcast(new Intent(C.Event.FAVORITE_LIST_CHANGED));
					}
				})
				.setNegativeButton(android.R.string.no, null)
				.setIcon(android.R.drawable.ic_dialog_alert)
				.show();
	}

	private void searchDialog() {
		mRadioCtl.getCurrentState(state -> {
			if (state.getStatus() != TunerStatus.ENABLED) {
				mToast.text(R.string.favorite_list_search_error_tuner_not_enabled).show();
				return;
			}

			final AlertDialog.Builder dialog = new AlertDialog.Builder(this)
					.setTitle(R.string.favorite_list_search_confirm_title)
					.setMessage(R.string.favorite_list_search_confirm_message)
					.setCancelable(false)
					.setPositiveButton(R.string.favorite_list_search_confirm_continue, (dlg, buttonId) -> searchStart())
					.setNegativeButton(R.string.favorite_list_search_confirm_discard, (dlg, buttonId) -> {
					})
					.setIcon(android.R.drawable.ic_dialog_alert);
			dialog.create().show();
		});
	}

	private void searchStart() {
		mRadioCtl.hwSearch();

		mProgress = ProgressDialog.create(this).text(R.string.favorite_list_search_progress).show();

		registerReceiver(mSearchDone, new IntentFilter(C.Event.HW_SEARCH_DONE));
	}

	private final BroadcastReceiver mSearchDone = new BroadcastReceiver() {
		@Override
		public void onReceive(final Context context, final Intent intent) {
			if (intent == null || intent.getAction() == null) {
				return;
			}

			unregisterReceiver(mSearchDone);

			final int[] frequencies = intent.getIntArrayExtra(C.Key.STATION_LIST);

			final List<FavoriteStation> stations = mController.getStationsInCurrentList();
			stations.clear();

			for (final int kHz : frequencies) {
				stations.add(new FavoriteStation(kHz, Utils.getMHz(kHz)));
			}

			mController.save();

			reloadLists();
			reloadContent();

			if (mProgress != null) {
				mProgress.hide();
			}
		}
	};

	private class FavoriteListsAdapter extends RecyclerView.Adapter<FavoriteListsAdapter.ViewHolder> {
		@Override
		public ViewHolder onCreateViewHolder(final android.view.ViewGroup parent, final int viewType) {
			final View view = getLayoutInflater().inflate(R.layout.favorite_list_manager_item, parent, false);
			return new ViewHolder(view);
		}

		@Override
		public void onBindViewHolder(final ViewHolder holder, final int position) {
			holder.bind(mFavoriteListItems.get(position));
		}

		@Override
		public int getItemCount() {
			return mFavoriteListItems.size();
		}

		class ViewHolder extends RecyclerView.ViewHolder {
			private final TextView name;
			private final TextView count;
			private final CheckBox check;
			private final View menuButton;

			ViewHolder(final View itemView) {
				super(itemView);
				name = itemView.findViewById(R.id.favorite_list_item_name);
				count = itemView.findViewById(R.id.favorite_list_item_count);
				check = itemView.findViewById(R.id.favorite_list_item_check);
				menuButton = itemView.findViewById(R.id.favorite_list_item_menu);
			}

			void bind(final FavoriteListItem item) {
				name.setText(item.name);
				count.setText(getString(R.string.favorite_lists_stations_count, item.stationsCount));
				final boolean selected = mSelectedLists.contains(item.name);
				itemView.setAlpha(selected ? 1f : (item.current ? 1f : 0.85f));
				check.setVisibility(mSelectionMode ? View.VISIBLE : View.GONE);
				check.setChecked(selected);
				menuButton.setVisibility(mSelectionMode ? View.GONE : View.VISIBLE);

				itemView.setOnClickListener(v -> setCurrentList(item.name));
				itemView.setOnLongClickListener(v -> {
					enterSelectionMode(item.name);
					return true;
				});

				menuButton.setOnClickListener(v -> {
					final android.widget.PopupMenu popupMenu = new android.widget.PopupMenu(FavoriteListsActivity.this, v);
					popupMenu.getMenu().add(0, MENU_LIST_RENAME, 1, R.string.popup_station_rename);
					if (!FavoriteController.DEFAULT_NAME.equals(item.name)) {
						popupMenu.getMenu().add(0, MENU_LIST_REMOVE, 2, R.string.popup_station_remove);
					}
					popupMenu.getMenu().add(0, MENU_LIST_EXPORT, 3, R.string.menu_favorite_list_export);
					popupMenu.setOnMenuItemClickListener(menuItem -> {
						final int itemId = menuItem.getItemId();
						if (itemId == MENU_LIST_RENAME) {
							renameDialog(item.name);
						} else if (itemId == MENU_LIST_REMOVE) {
							removeDialog(item.name);
						} else if (itemId == MENU_LIST_EXPORT) {
							openExportFavoritePicker(item.name);
						}
						return true;
					});
					popupMenu.show();
				});
			}
		}
	}
}
