package com.vlad805.fmradio.helper;

import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * vlad805 (c) 2019
 */
public class RecyclerItemClickListener implements RecyclerView.OnItemTouchListener {
	private OnItemClickListener mListener;

	public interface OnItemClickListener {
		public void onItemClick(View view, int position);

		public void onLongItemClick(View view, int position);
	}

	GestureDetector mGestureDetector;

	public RecyclerItemClickListener(Context context, final RecyclerView recyclerView, OnItemClickListener listener) {
		mListener = listener;
		mGestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
			@Override
			public boolean onSingleTapUp(MotionEvent e) {
				return true;
			}

			@Override
			public void onLongPress(MotionEvent e) {
				View child = recyclerView.findChildViewUnder(e.getX(), e.getY());
				if (child != null && mListener != null) {
					mListener.onLongItemClick(child, recyclerView.getChildAdapterPosition(child));
				}
			}
		});
	}

	@Override public boolean onInterceptTouchEvent(RecyclerView view, MotionEvent e) {
		View childView = view.findChildViewUnder(e.getX(), e.getY());
		if (childView != null && mListener != null && mGestureDetector.onTouchEvent(e)) {
			mListener.onItemClick(childView, view.getChildAdapterPosition(childView));
			return true;
		}
		return false;
	}

	@Override public void onTouchEvent(@NonNull RecyclerView view, @NonNull MotionEvent motionEvent) { }

	@Override
	public void onRequestDisallowInterceptTouchEvent (boolean disallowIntercept){}
}
