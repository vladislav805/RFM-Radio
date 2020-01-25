package com.vlad805.fmradio.view.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.models.FavoriteStation;
import com.vlad805.fmradio.view.FavoriteHolder;
import com.vlad805.fmradio.view.ItemTouchHelperAdapter;
import com.vlad805.fmradio.view.OnDragListener;

import java.util.Collections;
import java.util.List;

/**
 * vlad805 (c) 2020
 * Reordering items: https://github.com/iPaulPro/Android-ItemTouchHelper-Demo
 */
public class FavoriteAdapter extends RecyclerView.Adapter<FavoriteHolder> implements ItemTouchHelperAdapter {
	private List<FavoriteStation> mDataset;
	private OnDragListener mOnDragListener;

	public FavoriteAdapter(List<FavoriteStation> dataset, OnDragListener dragListener) {
		setDataset(dataset);
		mOnDragListener = dragListener;
	}

	public void setDataset(List<FavoriteStation> dataset) {
		mDataset = dataset;
	}

	@Override
	@NonNull
	public FavoriteHolder onCreateViewHolder(ViewGroup parent, int viewType) {
		View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.station_list_item, parent, false);

		return new FavoriteHolder(v, mOnDragListener);
	}

	@Override
	public void onBindViewHolder(FavoriteHolder holder, int position) {
		holder.set(mDataset.get(position));
	}

	@Override
	public int getItemCount() {
		return mDataset.size();
	}

	@Override
	public void onItemDismiss(int position) {
		mDataset.remove(position);
		notifyItemRemoved(position);
	}

	@Override
	public void onItemMove(int fromPosition, int toPosition) {
		if (fromPosition < toPosition) {
			for (int i = fromPosition; i < toPosition; i++) {
				Collections.swap(mDataset, i, i + 1);
			}
		} else {
			for (int i = fromPosition; i > toPosition; i--) {
				Collections.swap(mDataset, i, i - 1);
			}
		}
		notifyItemMoved(fromPosition, toPosition);
	}
}
