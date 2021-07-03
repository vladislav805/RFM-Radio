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
import com.vlad805.fmradio.controller.RadioController;
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

	protected List<FavoriteStation> mStations;
	private FavoritePanelAdapter mAdapter;
	private FavoriteController mController;
	private final RadioController mRadioController;

	private boolean mIsLocked = false;

	public FavoritesPanelView(final Context context) {
		this(context, null);

		init(context);
	}

	public FavoritesPanelView(final Context context, final AttributeSet attrs) {
		super(context, attrs);

		mRadioController = new RadioController(context);

		init(context);
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

	private void init(final Context context) {
		final LinearLayoutManager horizontalLayoutManager = new LinearLayoutManager(
				context,
				LinearLayoutManager.HORIZONTAL,
				false
		);

		setLayoutManager(horizontalLayoutManager);

		mAdapter = new FavoritePanelAdapter(context);
		setAdapter(mAdapter);

		addOnItemTouchListener(new RecyclerItemClickListener(context, this, this));

		mController = new FavoriteController(context);
		load();
	}

	/**
	 * On click by item
	 * @param view View
	 * @param position Position
	 */
	@Override
	public void onItemClick(final View view, final int position) {
		// Nothing to do if control is locked
		if (mIsLocked) {
			return;
		}

		// Check for valid index of clicked station (or +1 if we need create one)
		final int size = mStations.size();

		// If position of clicked item in range 0..size, then it station
		// and we need tune to this frequency
		if (position < size) {
			final FavoriteStation station = mStations.get(position);
			mRadioController.setFrequency(station.getFrequency());
			return;
		}

		// If position of clicked item is next from last station, then we
		// want create station
		if (position == size) {
			// request current radio state
			mRadioController.requestForCurrentState((state, mode) -> {
				// open edit window
				new EditTextDialog(getContext(), "", title -> {
					FavoriteStation station = new FavoriteStation(state.getFrequency(), title);
					mStations.add(station);
					mAdapter.notifyItemInserted(position);
					onFavoriteListUpdated();
				}).setTitle(R.string.popup_station_create).setHint(R.string.popup_station_create_hint).open();
			});
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
					}).setTitle(R.string.popup_station_create).open();
					break;
				}
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
