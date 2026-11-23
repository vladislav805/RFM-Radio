package com.vlad805.fmradio.view;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.controller.FavoriteController;
import com.vlad805.fmradio.models.FavoriteStation;

import java.io.FileNotFoundException;
import java.util.List;

public class FavoritesView extends FrameLayout {
	public interface OnFavoritesChangedListener {
		void onFavoritesChanged();
	}

	public interface OnErrorListener {
		void onError(String message);
	}

	private FavoritesRecyclerView mRecyclerView;
	private MaterialButton mEditToggle;
	private MaterialButton mListSelector;
	private TextView mEmptyHint;

	private FavoriteController mFavoriteController;
	private OnFavoritesChangedListener mOnFavoritesChangedListener;
	private OnErrorListener mOnErrorListener;
	private boolean mEditMode;

	private final FavoriteController.OnFavoritesChangeListener mControllerListener = (currentListName, stations) -> {
		mRecyclerView.syncFromController();
		updateListSelector();
		updateEmptyState();
		if (mOnFavoritesChangedListener != null) {
			mOnFavoritesChangedListener.onFavoritesChanged();
		}
	};

	public FavoritesView(final Context context) {
		this(context, null);
	}

	public FavoritesView(final Context context, @Nullable final AttributeSet attrs) {
		super(context, attrs);
		init(context);
	}

	private void init(final Context context) {
		LayoutInflater.from(context).inflate(R.layout.view_favorites, this, true);

		mRecyclerView = findViewById(R.id.favorite_recycler);
		mEditToggle = findViewById(R.id.favorite_edit_toggle);
		mListSelector = findViewById(R.id.favorite_list_selector);
		mEmptyHint = findViewById(R.id.favorite_empty_hint);

		mFavoriteController = FavoriteController.get(context);
		mRecyclerView.setFavoriteController(mFavoriteController);

		mEditToggle.setOnClickListener(v -> toggleEditMode());
		mListSelector.setOnClickListener(v -> showFavoriteListsDialog());

		setEditMode(false);
		updateListSelector();
		updateEmptyState();
	}

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		mFavoriteController.addOnChangeListener(mControllerListener);
		mRecyclerView.syncFromController();
		updateListSelector();
		updateEmptyState();
	}

	@Override
	protected void onDetachedFromWindow() {
		mFavoriteController.removeOnChangeListener(mControllerListener);
		super.onDetachedFromWindow();
	}

	public void setOnFavoritesChangedListener(final OnFavoritesChangedListener listener) {
		mOnFavoritesChangedListener = listener;
	}

	public void setOnErrorListener(final OnErrorListener listener) {
		mOnErrorListener = listener;
	}

	public void setOnStationActionListener(@Nullable final FavoritesRecyclerView.OnStationActionListener listener) {
		mRecyclerView.setOnStationActionListener(listener);
	}

	public void promptAddStation(final int frequencyKhz, @Nullable final String suggestedTitle) {
		mRecyclerView.promptAddStation(frequencyKhz, suggestedTitle);
	}

	public void reload(final boolean force) {
		mRecyclerView.reload(force);
		updateListSelector();
		updateEmptyState();
	}

	public void setActiveFrequency(final int frequency) {
		mRecyclerView.setActiveFrequency(frequency);
	}

	public int getStationsCount() {
		return mRecyclerView.getStationsCount();
	}

	public List<FavoriteStation> getStationsInCurrentList() {
		return mFavoriteController.getStationsInCurrentList();
	}

	private void toggleEditMode() {
		setEditMode(!mEditMode);
	}

	private void setEditMode(final boolean editMode) {
		mEditMode = editMode;
		mRecyclerView.setEditMode(editMode);
		mEditToggle.setIconResource(editMode ? R.drawable.ic_done : R.drawable.ic_preset_order_toggle);
		mEditToggle.setContentDescription(getContext().getString(editMode ? R.string.favorites_done : R.string.favorites_edit));
		final int bgColor = MaterialColors.getColor(
				this,
				editMode ? androidx.appcompat.R.attr.colorPrimary : com.google.android.material.R.attr.colorSecondaryContainer,
				ContextCompat.getColor(getContext(), editMode ? R.color.rfm_md_primary : R.color.rfm_md_secondary_container)
		);
		final int fgColor = MaterialColors.getColor(
				this,
				editMode ? com.google.android.material.R.attr.colorOnPrimary : com.google.android.material.R.attr.colorOnSecondaryContainer,
				ContextCompat.getColor(getContext(), editMode ? R.color.rfm_md_on_primary : R.color.rfm_md_on_secondary_container)
		);
		mEditToggle.setBackgroundTintList(ColorStateList.valueOf(bgColor));
		mEditToggle.setIconTint(ColorStateList.valueOf(fgColor));
	}

	private void updateListSelector() {
		mListSelector.setText(mFavoriteController.getCurrentFavoriteList());
	}

	private void updateEmptyState() {
		final boolean isEmpty = getStationsCount() == 0;
		mEmptyHint.setVisibility(isEmpty ? VISIBLE : GONE);
	}

	private void showFavoriteListsDialog() {
		final List<String> listNames = mFavoriteController.getFavoriteLists();
		final int checkedIndex = listNames.indexOf(mFavoriteController.getCurrentFavoriteList());

		final ArrayAdapter<String> adapter = new ArrayAdapter<>(
				getContext(),
				android.R.layout.simple_list_item_single_choice,
				listNames
		);

		final AlertDialog dialog = new MaterialAlertDialogBuilder(getContext())
				.setTitle(R.string.favorites_select_list)
				.setAdapter(adapter, (d, which) -> {
					switchFavoriteList(listNames.get(which));
					d.dismiss();
				})
				.create();

		dialog.setOnShowListener(d -> {
			final ListView listView = dialog.getListView();
			listView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
			if (checkedIndex >= 0) {
				listView.setItemChecked(checkedIndex, true);
				listView.post(() -> listView.setSelection(checkedIndex));
			}
		});

		dialog.show();
	}

	private void switchFavoriteList(final String listName) {
		try {
			if (mEditMode) {
				setEditMode(false);
			}

			mFavoriteController.setCurrentFavoriteList(listName);
		} catch (final FileNotFoundException e) {
			if (mOnErrorListener != null) {
				mOnErrorListener.onError("Not found this list");
			}
		}
	}
}
