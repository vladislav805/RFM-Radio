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
	private static final int TYPE_ADD = 1;

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

			case TYPE_ADD:
			default:
				return new ViewHolderAdd(mInflater.inflate(R.layout.favorite_station_add, parent, false));
		}
	}

	@Override
	public void onBindViewHolder(final @NonNull RecyclerView.ViewHolder holder, int position) {
		if (holder.getItemViewType() == TYPE_STATION) {
			FavoriteStation station = mList.get(position);
			((ViewHolder) holder).populate(station);
		}
	}

	@Override
	public int getItemViewType(int position) {
		return position >= mList.size() ? TYPE_ADD : TYPE_STATION;
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
