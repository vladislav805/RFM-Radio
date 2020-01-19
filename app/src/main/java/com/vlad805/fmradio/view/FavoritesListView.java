package com.vlad805.fmradio.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Menu;
import android.view.View;
import android.widget.PopupMenu;
import android.widget.Toast;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.controller.FavoriteController;
import com.vlad805.fmradio.helper.EditTextDialog;
import com.vlad805.fmradio.helper.RecyclerItemClickListener;
import com.vlad805.fmradio.models.FavoriteStation;
import com.vlad805.fmradio.view.adapter.FavoritesListAdapter;

import java.util.List;

/**
 * vlad805 (c) 2019
 */
public class FavoritesListView extends RecyclerView implements RecyclerItemClickListener.OnItemClickListener {

	public static final String TAG = "FLV";
	public static final int MENU_REMOVE = 100;
	public static final int MENU_RENAME = 101;
	public static final int MENU_CREATE = 102;
	public static final int MENU_REPLACE = 103;

	protected List<FavoriteStation> mList;
	private FavoritesListAdapter adapter;
	private OnFavoriteClick listener;
	private FavoriteController controller;

	public interface OnFavoriteClick {
		void onFavoriteClick(FavoriteStation station);
		int getCurrentFrequencyForAddFavorite();
	}

	public interface OnFavoriteListUpdated {
		void onFavoriteListUpdated();
	}

	public FavoritesListView(Context context) {
		super(context);

		init();
	}

	public FavoritesListView(Context context, AttributeSet attrs) {
		super(context, attrs);

		init();
	}

	private void init() {
		LinearLayoutManager horizontalLayoutManager = new LinearLayoutManager(getContext(), LinearLayoutManager.HORIZONTAL, false);
		setLayoutManager(horizontalLayoutManager);

		adapter = new FavoritesListAdapter(getContext());
		setAdapter(adapter);

		addOnItemTouchListener(new RecyclerItemClickListener(getContext(), this, this));

		controller = new FavoriteController(getContext());
		mList = controller.getList();
		setList(mList);
	}

	@Override
	public void onItemClick(View view, int position) {
		boolean isExists = position < mList.size();

		if (isExists) {
			if (listener != null) {
				FavoriteStation station = mList.get(position);
				listener.onFavoriteClick(station);
			}
		} else {
			if (listener != null) {
				new EditTextDialog(getContext(), "", title -> {
					FavoriteStation station = new FavoriteStation(listener.getCurrentFrequencyForAddFavorite(), title);
					mList.add(station);
					adapter.notifyItemInserted(position);
					onFavoriteListUpdated();
				}).setTitle(R.string.popup_station_create).setHint(R.string.popup_station_create_hint).open();
			}
		}
	}

	@Override
	public void onLongItemClick(View view, int position) {
		if (position >= mList.size()) {
			return;
		}

		final FavoriteStation station = mList.get(position);

		final PopupMenu popupMenu = new PopupMenu(getContext(), view);
		final Menu menu = popupMenu.getMenu();
		menu.add(1, MENU_RENAME, 1, R.string.popup_station_rename);
		menu.add(1, MENU_REMOVE, 2, R.string.popup_station_remove);

		popupMenu.setOnMenuItemClickListener(item -> {
			switch (item.getItemId()) {
				case MENU_REMOVE:
					mList.remove(station);
					adapter.notifyItemRemoved(position);
					onFavoriteListUpdated();
					break;

				case MENU_RENAME:
					new EditTextDialog(getContext(), station.getTitle(), title -> {
						station.setTitle(title);
						adapter.notifyItemChanged(position);
						onFavoriteListUpdated();
					}).setTitle(R.string.popup_station_create).open();
					break;
			}
			return true;
		});

		popupMenu.show();
	}

	public void onFavoriteListUpdated() {
		Toast.makeText(getContext(), "saved", Toast.LENGTH_LONG).show();
		controller.save();
	}

	/**
	 * Set list
	 * @param list List of stations
	 */
	public void setList(List<FavoriteStation> list) {
		mList = list;
		adapter.setList(list);
	}

	public void setOnFavoriteClick(OnFavoriteClick listener) {
		this.listener = listener;
	}
}
