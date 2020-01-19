package com.vlad805.fmradio.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.Utils;
import com.vlad805.fmradio.helper.EditTextDialog;
import com.vlad805.fmradio.helper.RecyclerItemClickListener;
import com.vlad805.fmradio.models.FavoriteStation;

import java.util.List;

/**
 * vlad805 (c) 2019
 */
public class FavoritesListView extends RecyclerView implements RecyclerItemClickListener.OnItemClickListener {
	private static final int MAX_COUNT = 20;

	public static final int MENU_REMOVE = 100;
	public static final int MENU_RENAME = 101;
	public static final int MENU_CREATE = 102;
	public static final int MENU_REPLACE = 103;

	protected List<FavoriteStation> mList;
	private StationAdapter adapter;
	private OnFavoriteClick listener;

	public interface OnFavoriteClick {
		void onFavoriteClick(FavoriteStation station);
		int getCurrentFrequencyForAddFavorite();
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

		adapter = new StationAdapter(getContext());
		setAdapter(adapter);

		addOnItemTouchListener(new RecyclerItemClickListener(getContext(), this, this));
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
				}).setTitle(R.string.popup_station_create).setHint(R.string.popup_station_create_hint).open();
			}
		}
	}

	@Override
	public void onLongItemClick(View view, int position) {
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
					break;

				case MENU_RENAME:
					new EditTextDialog(getContext(), station.getTitle(), title -> {
						station.setTitle(title);
						adapter.notifyItemChanged(position);
					}).setTitle(R.string.popup_station_create).open();
					break;
			}
			return true;
		});

		popupMenu.show();
	}

	/**
	 * Set list
	 * @param list List of stations
	 */
	public void setList(List<FavoriteStation> list) {
		mList = list;
		adapter.setList(list);
		adapter.notifyDataSetChanged();
	}

	public void setOnFavoriteClick(OnFavoriteClick listener) {
		this.listener = listener;
	}

	public static class StationAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
		private LayoutInflater mInflater;
		protected List<FavoriteStation> mList;

		private static final int STATION = 0;
		private static final int ADD = 1;

		private StationAdapter(Context context) {
			mInflater = LayoutInflater.from(context);
		}

		public void setList(List<FavoriteStation> stations) {
			mList = stations;
		}

		@Override
		@NonNull
		public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			switch (viewType) {
				case STATION:
					return new ViewHolder(mInflater.inflate(R.layout.favorite_station_item, parent, false));

				case ADD:
				default:
					return new ViewHolderAdd(mInflater.inflate(R.layout.favorite_station_add, parent, false));
			}
		}

		@Override
		public void onBindViewHolder(final @NonNull RecyclerView.ViewHolder holder, int position) {
			if (holder.getItemViewType() == STATION) {
				FavoriteStation station = mList.get(position);
				((ViewHolder) holder).populate(station);
			}
		}

		@Override
		public int getItemViewType(int position) {
			return position >= mList.size() ? ADD : STATION;
		}

		@Override
		public int getItemCount() {
			return Math.min(mList.size() + 1, MAX_COUNT);
		}

		public static class ViewHolder extends RecyclerView.ViewHolder {
			private TextView frequency;
			private TextView title;

			private ViewHolder(View root) {
				super(root);

				frequency = root.findViewById(R.id.favorite_station_item_frequency);
				title = root.findViewById(R.id.favorite_station_item_title);
			}

			public void populate(FavoriteStation station) {
				frequency.setText(Utils.getMHz(station.getFrequency()));
				title.setText(station.getTitle());
			}
		}

		public static class ViewHolderAdd extends RecyclerView.ViewHolder {
			private ViewHolderAdd(View root) {
				super(root);
			}
		}
	}
}
