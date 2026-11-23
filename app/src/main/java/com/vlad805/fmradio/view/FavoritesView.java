package com.vlad805.fmradio.view;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.TooltipCompat;
import androidx.core.content.ContextCompat;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.controller.FavoriteController;
import com.vlad805.fmradio.models.FavoriteStation;

import java.util.List;

public class FavoritesView extends FrameLayout {
	public interface OnFavoritesChangedListener {
		void onFavoritesChanged();
	}

	public interface OnErrorListener {
		void onError(String message);
	}

	public interface OnListSelectorClickListener {
		void onListSelectorClicked();
	}

	public interface OnEditModeChangeListener {
		void onEditModeChanged(boolean editMode);
	}

	public interface OnScanClickListener {
		void onScanClicked();
	}

	private FavoritesRecyclerView mRecyclerView;
	private MaterialButton mScanButton;
	private MaterialButton mAddButton;
	private MaterialButton mEditToggle;
	private View mListSelector;
	private TextView mListSelectorText;
	private TextView mEmptyHint;

	private FavoriteController mFavoriteController;
	private OnFavoritesChangedListener mOnFavoritesChangedListener;
	private OnErrorListener mOnErrorListener;
	private OnListSelectorClickListener mOnListSelectorClickListener;
	private OnEditModeChangeListener mOnEditModeChangeListener;
	private OnScanClickListener mOnScanClickListener;
	private FavoritesRecyclerView.OnStationActionListener mOnStationActionListener;
	private boolean mEditMode;
	private String mCurrentListName;

	private final FavoriteController.OnFavoritesChangeListener mControllerListener = (currentListName, stations) -> {
		if (mCurrentListName != null && !mCurrentListName.equals(currentListName) && mEditMode) {
			setEditMode(false);
		}
		mCurrentListName = currentListName;
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
		mScanButton = findViewById(R.id.favorite_scan_button);
		mAddButton = findViewById(R.id.favorite_add_button);
		mEditToggle = findViewById(R.id.favorite_edit_toggle);
		mListSelector = findViewById(R.id.favorite_list_selector);
		mListSelectorText = findViewById(R.id.favorite_list_selector_text);
		mEmptyHint = findViewById(R.id.favorite_empty_hint);

		mFavoriteController = FavoriteController.get(context);
		mRecyclerView.setFavoriteController(mFavoriteController);

		TooltipCompat.setTooltipText(mScanButton, context.getString(R.string.menu_scan_stations));
		mScanButton.setOnClickListener(v -> {
			if (!isEnabled() || mEditMode) {
				return;
			}
			if (mOnScanClickListener != null) {
				mOnScanClickListener.onScanClicked();
			}
		});
		TooltipCompat.setTooltipText(mAddButton, context.getString(R.string.favorites_add_station));
		mAddButton.setOnClickListener(v -> {
			if (!isEnabled() || mEditMode) {
				return;
			}
			if (mOnStationActionListener != null) {
				mOnStationActionListener.onAddCurrentStationRequested();
			}
		});
		mEditToggle.setOnClickListener(v -> toggleEditMode());
		mListSelector.setOnClickListener(v -> {
			if (mOnListSelectorClickListener != null) {
				mOnListSelectorClickListener.onListSelectorClicked();
			}
		});

		setEditMode(false);
		updateListSelector();
		updateEmptyState();
	}

	@Override
	public void setEnabled(final boolean enabled) {
		super.setEnabled(enabled);
		if (mRecyclerView != null) {
			mRecyclerView.setEnabled(enabled);
		}
		if (mScanButton != null) {
			mScanButton.setEnabled(enabled);
		}
		if (mAddButton != null) {
			mAddButton.setEnabled(enabled);
		}
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

	public void setOnListSelectorClickListener(@Nullable final OnListSelectorClickListener listener) {
		mOnListSelectorClickListener = listener;
	}

	public void setOnEditModeChangeListener(@Nullable final OnEditModeChangeListener listener) {
		mOnEditModeChangeListener = listener;
	}

	public void setOnScanClickListener(@Nullable final OnScanClickListener listener) {
		mOnScanClickListener = listener;
	}

	public boolean isEditMode() {
		return mEditMode;
	}

	public void setOnStationActionListener(@Nullable final FavoritesRecyclerView.OnStationActionListener listener) {
		mOnStationActionListener = listener;
		mRecyclerView.setOnStationActionListener(listener);
	}

	public void exitEditMode() {
		if (mEditMode) {
			setEditMode(false);
		}
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

	public void setColumnCount(final int columns) {
		mRecyclerView.setColumnCount(columns);
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
		mEditToggle.setIconResource(editMode ? R.drawable.ic_done : R.drawable.ic_rfm_edit);
		final String editLabel = getContext().getString(editMode ? R.string.favorites_done : R.string.favorites_edit);
		mEditToggle.setContentDescription(editLabel);
		TooltipCompat.setTooltipText(mEditToggle, editLabel);
		final int bgColor = editMode
				? MaterialColors.getColor(
						this,
						androidx.appcompat.R.attr.colorPrimary,
						ContextCompat.getColor(getContext(), R.color.rfm_md_primary)
				)
				: Color.TRANSPARENT;
		final int fgColor = MaterialColors.getColor(
				this,
				editMode ? com.google.android.material.R.attr.colorOnPrimary : com.google.android.material.R.attr.colorOnSurfaceVariant,
				ContextCompat.getColor(getContext(), editMode ? R.color.rfm_md_on_primary : R.color.text_secondary)
		);
		mEditToggle.setBackgroundTintList(ColorStateList.valueOf(bgColor));
		mEditToggle.setIconTint(ColorStateList.valueOf(fgColor));
		if (mScanButton != null) {
			mScanButton.setAlpha(editMode ? 0.35f : 1f);
		}
		if (mAddButton != null) {
			mAddButton.setAlpha(editMode ? 0.35f : 1f);
		}
		if (mOnEditModeChangeListener != null) {
			mOnEditModeChangeListener.onEditModeChanged(editMode);
		}
	}

	private void updateListSelector() {
		mCurrentListName = mFavoriteController.getCurrentFavoriteList();
		mListSelectorText.setText(mCurrentListName);
	}

	private void updateEmptyState() {
		final boolean isEmpty = getStationsCount() == 0;
		mEmptyHint.setVisibility(isEmpty ? VISIBLE : GONE);
	}
}
