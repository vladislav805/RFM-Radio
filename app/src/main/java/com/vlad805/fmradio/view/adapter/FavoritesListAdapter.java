package com.vlad805.fmradio.view.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.Utils;
import com.vlad805.fmradio.models.FavoriteStation;

import java.util.List;

/**
 * vlad805 (c) 2020
 */
public class FavoritesListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
	private static final int MAX_COUNT = 20;

	private LayoutInflater mInflater;
	protected List<FavoriteStation> mList;

	private static final int TYPE_STATION = 0;
	private static final int TYPE_BUTTON = 1;

	private static final String[] BUTTONS = {
			"+"
	};

	public FavoritesListAdapter(Context context) {
		mInflater = LayoutInflater.from(context);
	}

	public void setList(List<FavoriteStation> stations) {
		mList = stations;
		notifyDataSetChanged();
	}

	@Override
	@NonNull
	public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		switch (viewType) {
			case TYPE_STATION:
				return new ViewHolder(mInflater.inflate(R.layout.favorite_station_item, parent, false));

			case TYPE_BUTTON:
			default:
				return new ViewHolderButton(mInflater.inflate(R.layout.favorite_station_button, parent, false));
		}
	}

	@Override
	public void onBindViewHolder(final @NonNull RecyclerView.ViewHolder holder, final int position) {
		switch (holder.getItemViewType()) {
			case TYPE_STATION: {
				final FavoriteStation station = mList.get(position);
				((ViewHolder) holder).populate(station);
				break;
			}

			case TYPE_BUTTON: {
				final int fromLast = position - mList.size();
				((ViewHolderButton) holder).setContent(BUTTONS[fromLast]);
				break;
			}

		}
	}

	@Override
	public int getItemViewType(final int position) {
		return position < mList.size()
				? TYPE_STATION
				: TYPE_BUTTON;
	}

	@Override
	public int getItemCount() {
		return Math.min(mList.size(), MAX_COUNT) + BUTTONS.length;
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
			frequency.setText(Utils.getMHz(station.getFrequency()).trim());
			title.setText(station.getTitle());
		}
	}

	public static class ViewHolderButton extends RecyclerView.ViewHolder {
		private TextView content;

		private ViewHolderButton(View root) {
			super(root);

			content = root.findViewById(R.id.favorite_station_item_button);
		}

		public void setContent(String text) {
			content.setText(text);
		}
	}
}
