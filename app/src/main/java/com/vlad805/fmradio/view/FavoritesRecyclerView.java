package com.vlad805.fmradio.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Menu;
import android.view.View;
import android.widget.PopupMenu;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.controller.FavoriteController;
import com.vlad805.fmradio.controller.RadioController;
import com.vlad805.fmradio.helper.EditTextDialog;
import com.vlad805.fmradio.helper.RecyclerItemClickListener;
import com.vlad805.fmradio.helper.Toast;
import com.vlad805.fmradio.models.FavoriteStation;
import com.vlad805.fmradio.view.adapter.FavoritePanelAdapter;

import java.util.List;

public class FavoritesRecyclerView extends RecyclerView implements RecyclerItemClickListener.OnItemClickListener {
	public static final int MENU_REMOVE = 100;
	public static final int MENU_RENAME = 101;

	protected List<FavoriteStation> mStations;
	private FavoritePanelAdapter mAdapter;
	private FavoriteController mController;
	private final RadioController mRadioController;
	private final Toast mToast;
	private OnFavoritesChangedListener mOnFavoritesChangedListener;
	private SimpleItemTouchHelperCallback mTouchHelperCallback;
	private boolean mEditMode;

	public interface OnFavoritesChangedListener {
		void onFavoritesChanged();
	}

	public FavoritesRecyclerView(final Context context) {
		this(context, null);
	}

	public FavoritesRecyclerView(final Context context, final AttributeSet attrs) {
		super(context, attrs);

		mRadioController = new RadioController(context);
		mToast = Toast.create(context);

		init(context);
	}

	public void setFavoriteController(final FavoriteController controller) {
		mController = controller;
		reload(false);
	}

	public void load() {
		reload(false);
	}

	public void reload(final boolean force) {
		if (mController == null) {
			return;
		}

		if (force) {
			mController.reload();
		}

		mStations = mController.getStationsInCurrentList();
		mAdapter.setList(mStations);
		notifyFavoritesChanged();
	}

	public void setActiveFrequency(final int frequency) {
		mAdapter.setActiveFrequency(frequency);
	}

	public void setOnFavoritesChangedListener(final OnFavoritesChangedListener listener) {
		mOnFavoritesChangedListener = listener;
	}

	public int getStationsCount() {
		return mStations == null ? 0 : mStations.size();
	}

	private void init(final Context context) {
		setLayoutManager(new GridLayoutManager(getContext(), 2));

		mAdapter = new FavoritePanelAdapter(context);
		setAdapter(mAdapter);

		mTouchHelperCallback = new SimpleItemTouchHelperCallback(mAdapter, false);
		new ItemTouchHelper(mTouchHelperCallback).attachToRecyclerView(this);

		addOnItemTouchListener(new RecyclerItemClickListener(context, this, this));
	}

	@Override
	public void onItemClick(final View view, final int position) {
		if (mEditMode || mStations == null) {
			return;
		}

		if (!isEnabled()) {
			return;
		}

		final int size = mStations.size();

		if (position < size) {
			final FavoriteStation station = mStations.get(position);
			mRadioController.setFrequency(station.getFrequency());
			return;
		}

		if (position == size) {
			mRadioController.requestForCurrentState((state, mode) -> {
				setActiveFrequency(state.getFrequency());
				if (hasFrequency(state.getFrequency())) {
					mToast.text(R.string.favorite_station_already_added).show();
					return;
				}

				new EditTextDialog(getContext(), normalizeStationTitle(state.getPs()), title -> {
					FavoriteStation station = new FavoriteStation(state.getFrequency(), title);
					mStations.add(station);
					mAdapter.notifyItemInserted(position);
					onFavoriteListUpdated();
				}).setTitle(R.string.popup_station_create).setHint(R.string.popup_station_create_hint).open();
			});
		}
	}

	@Override
	public void onLongItemClick(final View view, final int position) {
		if (mEditMode || mStations == null) {
			return;
		}

		if (!isEnabled() || position >= mStations.size()) {
			return;
		}

		final FavoriteStation station = mStations.get(position);

		final PopupMenu popupMenu = new PopupMenu(getContext(), view);
		final Menu menu = popupMenu.getMenu();
		menu.add(1, MENU_RENAME, 1, R.string.popup_station_rename);
		menu.add(1, MENU_REMOVE, 2, R.string.popup_station_remove);

		popupMenu.setOnMenuItemClickListener(item -> {
			switch (item.getItemId()) {
				case MENU_REMOVE: {
					mStations.remove(station);
					mAdapter.notifyItemRemoved(position);
					onFavoriteListUpdated();
					break;
				}

				case MENU_RENAME: {
					new EditTextDialog(getContext(), station.getTitle(), title -> {
						station.setTitle(title);
						mAdapter.notifyItemChanged(position);
						onFavoriteListUpdated();
					}).setTitle(R.string.popup_station_rename).open();
					break;
				}
			}
			return true;
		});

		popupMenu.show();
	}

	public void onFavoriteListUpdated() {
		if (mController != null) {
			mController.save();
		}
		notifyFavoritesChanged();
	}

	public void setEditMode(final boolean editMode) {
		if (mEditMode == editMode) {
			return;
		}

		final boolean wasEditMode = mEditMode;
		mEditMode = editMode;
		mAdapter.setEditMode(editMode);
		if (mTouchHelperCallback != null) {
			mTouchHelperCallback.setLongPressDragEnabled(editMode);
		}

		if (wasEditMode && !editMode) {
			onFavoriteListUpdated();
		}
	}

	private boolean hasFrequency(final int frequency) {
		if (frequency <= 0 || mStations == null) {
			return false;
		}

		for (final FavoriteStation station : mStations) {
			if (station.getFrequency() == frequency) {
				return true;
			}
		}

		return false;
	}

	private String normalizeStationTitle(final String title) {
		return title == null ? "" : title.trim();
	}

	private void notifyFavoritesChanged() {
		if (mOnFavoritesChangedListener != null) {
			mOnFavoritesChangedListener.onFavoritesChanged();
		}
	}
}
