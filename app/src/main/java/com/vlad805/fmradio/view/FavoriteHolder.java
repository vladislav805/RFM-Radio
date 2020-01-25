package com.vlad805.fmradio.view;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.Utils;
import com.vlad805.fmradio.models.FavoriteStation;

/**
 * vlad805 (c) 2020
 * Station view holder in recycler view in favorite manager
 */
public class FavoriteHolder extends RecyclerView.ViewHolder implements View.OnTouchListener, ItemTouchHelperViewHolder {
	private TextView mFrequency;
	private TextView mTitle;
	private ImageView mHandle;

	private OnDragListener mListener;

	private static final int BG_COLOR_DEFAULT = Color.TRANSPARENT;
	private static final int BG_COLOR_MOVING = Color.argb(128, 128, 128, 128);

	public FavoriteHolder(View v, OnDragListener listener) {
		super(v);
		mListener = listener;

		mFrequency = v.findViewById(R.id.station_item_frequency);
		mTitle = v.findViewById(R.id.station_item_title);
		mHandle = v.findViewById(R.id.station_item_reorder);
	}

	@SuppressLint("ClickableViewAccessibility")
	public void set(FavoriteStation s) {
		mFrequency.setText(Utils.getMHz(s.getFrequency()).trim());
		mTitle.setText(s.getTitle());
		mHandle.setOnTouchListener(this);
	}

	@SuppressLint("ClickableViewAccessibility")
	@Override
	public boolean onTouch(View v, MotionEvent event) {
		if (event.getAction() == MotionEvent.ACTION_DOWN) {
			mListener.onStartDrag(this);
		}
		return false;
	}

	@Override
	public void onItemSelected() {
		itemView.setBackgroundColor(BG_COLOR_MOVING);
	}

	@Override
	public void onItemClear() {
		itemView.setBackgroundColor(BG_COLOR_DEFAULT);
		mListener.onEndDrag();
	}
}
