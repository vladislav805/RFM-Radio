package com.vlad805.fmradio.view;

import androidx.recyclerview.widget.RecyclerView;

/**
 * vlad805 (c) 2020
 */
public interface OnDragListener {

	/**
	 * Called when a view is requesting a start of a drag.
	 *
	 * @param viewHolder The holder of the view to drag.
	 */
	void onStartDrag(RecyclerView.ViewHolder viewHolder);

	/**
	 * Called when a view is dropped
	 */
	void onEndDrag();
}