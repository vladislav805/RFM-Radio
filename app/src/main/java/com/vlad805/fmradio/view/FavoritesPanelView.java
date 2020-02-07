package com.vlad805.fmradio.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Menu;
import android.view.View;
import android.widget.PopupMenu;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.controller.FavoriteController;
import com.vlad805.fmradio.helper.EditTextDialog;
import com.vlad805.fmradio.helper.RecyclerItemClickListener;
import com.vlad805.fmradio.models.FavoriteStation;
import com.vlad805.fmradio.view.adapter.FavoritePanelAdapter;

import java.util.List;

/**
 * vlad805 (c) 2019
 */
public class FavoritesPanelView extends RecyclerView implements RecyclerItemClickListener.OnItemClickListener {
	public static final int MENU_REMOVE = 100;
	public static final int MENU_RENAME = 101;
	public static final int MENU_REPLACE = 103;

	protected List<FavoriteStation> mStations;
	private FavoritePanelAdapter mAdapter;
	private OnFavoriteClick mClickListener;
	private FavoriteController mController;

	private boolean mIsLocked = false;

	public interface OnFavoriteClick {
		void onFavoriteClick(final FavoriteStation station);
		int getCurrentFrequencyForAddFavorite();
	}

	public FavoritesPanelView(final Context context) {
		super(context);

		init();
	}

	public FavoritesPanelView(final Context context, final AttributeSet attrs) {
		super(context, attrs);

		init();
	}

	/**
	 * Set stations in view
	 */
	public void load() {
		reload(false);
	}

	/**
	 * Set stations in view. If force is true - will be reloaded data from file.
	 * @param force If true, data will be fetched from file
	 */
	public void reload(final boolean force) {
		if (force) {
			mController.reload();
		}
		mStations = mController.getStationsInCurrentList();
		mAdapter.setList(mStations);
	}

	private void init() {
		final LinearLayoutManager horizontalLayoutManager = new LinearLayoutManager(getContext(), LinearLayoutManager.HORIZONTAL, false);
		setLayoutManager(horizontalLayoutManager);

		mAdapter = new FavoritePanelAdapter(getContext());
		setAdapter(mAdapter);

		addOnItemTouchListener(new RecyclerItemClickListener(getContext(), this, this));

		mController = new FavoriteController(getContext());
		load();
	}

	/**
	 * On click by item
	 * @param view View
	 * @param position Position
	 */
	@Override
	public void onItemClick(final View view, final int position) {
		if (mIsLocked) {
			return;
		}

		final int size = mStations.size();
		boolean isExists = position < size;

		if (mClickListener == null) {
			return;
		}

		if (isExists) {
			FavoriteStation station = mStations.get(position);
			mClickListener.onFavoriteClick(station);
			return;
		}

		if (position == size) {
			new EditTextDialog(getContext(), "", title -> {
				FavoriteStation station = new FavoriteStation(mClickListener.getCurrentFrequencyForAddFavorite(), title);
				mStations.add(station);
				mAdapter.notifyItemInserted(position);
				onFavoriteListUpdated();
			}).setTitle(R.string.popup_station_create).setHint(R.string.popup_station_create_hint).open();
		}
	}

	/**
	 * On long click will be opened popup menu
	 * @param view View
	 * @param position Position
	 */
	@Override
	public void onLongItemClick(final View view, final int position) {
		if (mIsLocked || position >= mStations.size()) {
			return;
		}

		final FavoriteStation station = mStations.get(position);

		final PopupMenu popupMenu = new PopupMenu(getContext(), view);
		final Menu menu = popupMenu.getMenu();
		menu.add(1, MENU_RENAME, 1, R.string.popup_station_rename);
		menu.add(1, MENU_REMOVE, 2, R.string.popup_station_remove);

		popupMenu.setOnMenuItemClickListener(item -> {
			switch (item.getItemId()) {
				case MENU_REMOVE:
					mStations.remove(station);
					mAdapter.notifyItemRemoved(position);
					onFavoriteListUpdated();
					break;

				case MENU_RENAME:
					new EditTextDialog(getContext(), station.getTitle(), title -> {
						station.setTitle(title);
						mAdapter.notifyItemChanged(position);
						onFavoriteListUpdated();
					}).setTitle(R.string.popup_station_create).open();
					break;
			}
			return true;
		});

		popupMenu.show();
	}

	/**
	 * Calls when user updated info station
	 */
	public void onFavoriteListUpdated() {
		mController.save();
	}

	/**
	 * Set callback for click by favorite item
	 * @param listener Listener
	 */
	public void setOnFavoriteClick(final OnFavoriteClick listener) {
		mClickListener = listener;
	}

	@Override
	public boolean canScrollVertically(int direction) {
		return !mIsLocked;
	}

	@Override
	public void setEnabled(final boolean enabled) {
		super.setEnabled(enabled);

		mIsLocked = !enabled;
	}
}
