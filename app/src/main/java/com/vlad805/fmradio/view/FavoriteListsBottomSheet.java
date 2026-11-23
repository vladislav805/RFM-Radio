package com.vlad805.fmradio.view;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.PopupMenu;
import androidx.appcompat.widget.TooltipCompat;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.radiobutton.MaterialRadioButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.controller.FavoriteController;
import com.vlad805.fmradio.helper.FavoriteListTransferHelper;
import com.vlad805.fmradio.helper.Toast;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;

/**
 * Material 3 modal bottom sheet for switching, creating, renaming, deleting,
 * importing, and exporting favorite lists.
 */
public final class FavoriteListsBottomSheet extends BottomSheetDialogFragment {
	private static final String TAG = "FavoriteListsBottomSheet";
	private static final String STATE_PENDING_EXPORT_LIST = "pending_export_list";

	private static final int MENU_RENAME = 1;
	private static final int MENU_EXPORT = 2;
	private static final int MENU_DELETE = 3;

	private interface NameConfirmCallback {
		void onConfirm(@NonNull String name);
	}

	private static final class ListEntry {
		final String name;
		final int stationsCount;
		final boolean current;

		ListEntry(final String name, final int stationsCount, final boolean current) {
			this.name = name;
			this.stationsCount = stationsCount;
			this.current = current;
		}
	}

	private FavoriteController mController;
	private FavoriteListTransferHelper mTransferHelper;
	private Toast mToast;
	private final List<ListEntry> mItems = new ArrayList<>();
	private SheetAdapter mAdapter;
	@Nullable
	private String mPendingExportListName;

	private final FavoriteController.OnFavoritesChangeListener mFavoritesListener =
			(currentListName, stations) -> reloadLists();

	private final ActivityResultLauncher<Intent> mImportLauncher = registerForActivityResult(
			new ActivityResultContracts.StartActivityForResult(),
			result -> {
				if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
					return;
				}
				handleImportResult(mTransferHelper.importFromIntent(result.getData()));
			}
	);

	private final ActivityResultLauncher<Intent> mExportSingleLauncher = registerForActivityResult(
			new ActivityResultContracts.StartActivityForResult(),
			result -> {
				final String listName = mPendingExportListName != null
						? mPendingExportListName
						: mController.getCurrentFavoriteList();
				mPendingExportListName = null;

				if (
						result.getResultCode() != Activity.RESULT_OK ||
						result.getData() == null ||
						result.getData().getData() == null
				) {
					return;
				}

				if (mTransferHelper.exportSingleList(listName, result.getData().getData())) {
					mToast.text(getString(R.string.favorite_list_export_success, listName)).show();
				} else {
					mToast.text(R.string.favorite_list_export_error).show();
				}
			}
	);

	private final ActivityResultLauncher<Intent> mExportAllLauncher = registerForActivityResult(
			new ActivityResultContracts.StartActivityForResult(),
			result -> {
				if (
						result.getResultCode() != Activity.RESULT_OK ||
						result.getData() == null ||
						result.getData().getData() == null
				) {
					return;
				}

				final int exportedCount = mTransferHelper.exportAllLists(result.getData().getData());
				if (exportedCount >= 0) {
					mToast.text(getString(R.string.favorite_list_export_all_success, exportedCount)).show();
				} else {
					mToast.text(R.string.favorite_list_export_all_error).show();
				}
			}
	);

	public static void show(@NonNull final FragmentManager fragmentManager) {
		if (fragmentManager.findFragmentByTag(TAG) != null) {
			return;
		}

		new FavoriteListsBottomSheet().show(fragmentManager, TAG);
	}

	@Override
	public void onCreate(@Nullable final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		mController = FavoriteController.get(requireContext());
		mTransferHelper = new FavoriteListTransferHelper(requireContext(), mController);
		mToast = Toast.create(requireContext());

		if (savedInstanceState != null) {
			mPendingExportListName = savedInstanceState.getString(STATE_PENDING_EXPORT_LIST);
		}
	}

	@Override
	public void onSaveInstanceState(@NonNull final Bundle outState) {
		super.onSaveInstanceState(outState);
		outState.putString(STATE_PENDING_EXPORT_LIST, mPendingExportListName);
	}

	@NonNull
	@Override
	public Dialog onCreateDialog(@Nullable final Bundle savedInstanceState) {
		final BottomSheetDialog dialog = (BottomSheetDialog) super.onCreateDialog(savedInstanceState);
		dialog.setDismissWithAnimation(true);
		dialog.setOnShowListener(d -> {
			final BottomSheetBehavior<?> behavior = dialog.getBehavior();
			behavior.setSkipCollapsed(true);
			behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
		});
		return dialog;
	}

	@Nullable
	@Override
	public View onCreateView(
			@NonNull final LayoutInflater inflater,
			@Nullable final ViewGroup container,
			@Nullable final Bundle savedInstanceState
	) {
		return inflater.inflate(R.layout.bottom_sheet_favorite_lists, container, false);
	}

	@Override
	public void onViewCreated(@NonNull final View view, @Nullable final Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		final RecyclerView recyclerView = view.findViewById(R.id.sheet_favorite_lists_recycler);
		mAdapter = new SheetAdapter();
		recyclerView.setAdapter(mAdapter);

		final View createButton = view.findViewById(R.id.btn_sheet_create_list);
		final View importButton = view.findViewById(R.id.btn_sheet_import);
		final View exportButton = view.findViewById(R.id.btn_sheet_export);

		TooltipCompat.setTooltipText(createButton, getString(R.string.menu_favorite_list_create));
		TooltipCompat.setTooltipText(importButton, getString(R.string.menu_favorite_list_import));
		TooltipCompat.setTooltipText(exportButton, getString(R.string.menu_favorite_list_export_all));

		createButton.setOnClickListener(v -> showCreateDialog());
		importButton.setOnClickListener(v -> mImportLauncher.launch(FavoriteListTransferHelper.createImportIntent()));
		exportButton.setOnClickListener(v -> mExportAllLauncher.launch(FavoriteListTransferHelper.createExportAllIntent()));

		reloadLists();
	}

	@Override
	public void onStart() {
		super.onStart();
		mController.addOnChangeListener(mFavoritesListener);
		reloadLists();
	}

	@Override
	public void onStop() {
		mController.removeOnChangeListener(mFavoritesListener);
		super.onStop();
	}

	@Override
	public void onDestroyView() {
		mAdapter = null;
		super.onDestroyView();
	}

	private void reloadLists() {
		if (mAdapter == null || mController == null) {
			return;
		}
		final String current = mController.getCurrentFavoriteList();
		mItems.clear();
		for (final String name : mController.getFavoriteLists()) {
			mItems.add(new ListEntry(
					name,
					mController.getStationsCountInList(name),
					name.equals(current)
			));
		}
		mAdapter.notifyDataSetChanged();
	}

	private void handleImportResult(@NonNull final FavoriteListTransferHelper.ImportResult result) {
		if (result.count <= 0 || result.switchFailed) {
			mToast.text(R.string.favorite_list_import_error).show();
			return;
		}

		reloadLists();

		if (result.count == 1 && result.lastImportedName != null) {
			mToast.text(getString(R.string.favorite_list_import_success, result.lastImportedName)).show();
		} else {
			mToast.text(getString(R.string.favorite_list_import_success_many, result.count)).show();
		}
	}

	private void selectList(final String listName) {
		try {
			mController.setCurrentFavoriteList(listName);
			dismiss();
		} catch (final FileNotFoundException e) {
			mToast.text("Not found this list").show();
		}
	}

	private void showCreateDialog() {
		showListNameDialog(R.string.popup_favorite_list_create, "", null, title -> {
			try {
				mController.addList(title);
				mToast.text("Created list '" + title + "'").show();
				reloadLists();
			} catch (final Error e) {
				mToast.text(e.getMessage()).show();
			}
		});
	}

	private void showRenameDialog(final String oldName) {
		if (FavoriteController.DEFAULT_NAME.equals(oldName)) {
			mToast.text(getString(R.string.favorite_list_rename_default_error)).show();
			return;
		}

		showListNameDialog(R.string.popup_favorite_list_rename, oldName, oldName, newName -> {
			try {
				if (oldName.equals(newName)) {
					return;
				}
				if (!mController.renameList(oldName, newName)) {
					mToast.text(getString(R.string.favorite_list_rename_default_error)).show();
					return;
				}
				reloadLists();
				mToast.text("Renamed list to '" + newName + "'").show();
			} catch (final Error e) {
				mToast.text(e.getMessage()).show();
			}
		});
	}

	private void showDeleteDialog(final String listName) {
		if (FavoriteController.DEFAULT_NAME.equals(listName)) {
			return;
		}
		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.favorite_list_remove_title)
				.setMessage(getString(R.string.favorite_list_remove_message, listName))
				.setPositiveButton(android.R.string.ok, (dlg, which) -> {
					mController.removeList(listName);
					mController.reload();
					reloadLists();
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void showListNameDialog(
			@StringRes final int titleRes,
			@NonNull final String initialValue,
			@Nullable final String currentName,
			@NonNull final NameConfirmCallback callback
	) {
		final View content = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_favorite_list_name, null);
		final TextInputLayout inputLayout = content.findViewById(R.id.favorite_list_name_layout);
		final TextInputEditText editText = content.findViewById(R.id.favorite_list_name_edit);

		editText.setText(initialValue);
		editText.setSelection(initialValue.length());

		final AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
				.setTitle(titleRes)
				.setView(content)
				.setNegativeButton(android.R.string.cancel, null)
				.setPositiveButton(android.R.string.ok, (dlg, which) -> {
					final String value = editText.getText() != null ? editText.getText().toString().trim() : "";
					callback.onConfirm(value);
				})
				.create();

		dialog.setOnShowListener(dlg -> {
			final View positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
			positive.setEnabled(validateListName(inputLayout, initialValue, currentName));
			editText.addTextChangedListener(new TextWatcher() {
				@Override
				public void beforeTextChanged(final CharSequence s, final int start, final int count, final int after) {}

				@Override
				public void onTextChanged(final CharSequence s, final int start, final int before, final int count) {
					positive.setEnabled(validateListName(inputLayout, s.toString(), currentName));
				}

				@Override
				public void afterTextChanged(final Editable s) {}
			});
		});

		dialog.show();
	}

	private boolean validateListName(
			final TextInputLayout inputLayout,
			final String rawValue,
			@Nullable final String currentName
	) {
		final String value = rawValue.trim();
		final boolean isRenameSame = currentName != null && currentName.equals(value);
		if (!isRenameSame && mController.isAlreadyExists(value)) {
			inputLayout.setError(getString(R.string.favorite_list_create_error_already_exists));
			return false;
		}
		if (mController.isInvalidName(value)) {
			inputLayout.setError(getString(R.string.favorite_list_create_error_invalid_name));
			return false;
		}
		inputLayout.setError(null);
		return true;
	}

	private void showItemMenu(final View anchor, final ListEntry entry) {
		final PopupMenu popup = new PopupMenu(requireContext(), anchor);
		final Menu menu = popup.getMenu();
		final boolean isDefault = FavoriteController.DEFAULT_NAME.equals(entry.name);

		if (!isDefault) {
			menu.add(0, MENU_RENAME, 1, R.string.popup_station_rename);
		}
		menu.add(0, MENU_EXPORT, 2, R.string.menu_favorite_list_export);
		if (!isDefault) {
			menu.add(0, MENU_DELETE, 3, R.string.popup_station_remove);
		}

		popup.setOnMenuItemClickListener(item -> {
			final int id = item.getItemId();
			if (id == MENU_RENAME) {
				showRenameDialog(entry.name);
				return true;
			} else if (id == MENU_EXPORT) {
				mPendingExportListName = entry.name;
				mExportSingleLauncher.launch(FavoriteListTransferHelper.createExportSingleIntent(entry.name));
				return true;
			} else if (id == MENU_DELETE) {
				showDeleteDialog(entry.name);
				return true;
			}
			return false;
		});

		popup.show();
	}

	private final class SheetAdapter extends RecyclerView.Adapter<SheetAdapter.ViewHolder> {
		@NonNull
		@Override
		public ViewHolder onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType) {
			final View view = LayoutInflater.from(parent.getContext())
					.inflate(R.layout.item_favorite_list_sheet, parent, false);
			return new ViewHolder(view);
		}

		@Override
		public void onBindViewHolder(@NonNull final ViewHolder holder, final int position) {
			holder.bind(mItems.get(position));
		}

		@Override
		public int getItemCount() {
			return mItems.size();
		}

		final class ViewHolder extends RecyclerView.ViewHolder {
			private final MaterialRadioButton radio;
			private final TextView name;
			private final TextView count;
			private final View menuButton;

			ViewHolder(@NonNull final View itemView) {
				super(itemView);
				radio = itemView.findViewById(R.id.sheet_list_radio);
				name = itemView.findViewById(R.id.sheet_list_name);
				count = itemView.findViewById(R.id.sheet_list_count);
				menuButton = itemView.findViewById(R.id.sheet_list_menu);
			}

			void bind(final ListEntry entry) {
				radio.setChecked(entry.current);
				name.setText(entry.name);
				count.setText(getString(R.string.favorite_lists_stations_count, entry.stationsCount));

				itemView.setOnClickListener(v -> selectList(entry.name));
				menuButton.setOnClickListener(v -> showItemMenu(v, entry));
			}
		}
	}
}
