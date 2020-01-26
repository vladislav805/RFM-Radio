package com.vlad805.fmradio.view;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

/**
 * vlad805 (c) 2020
 */
public class SimpleItemTouchHelperCallback extends ItemTouchHelper.Callback {
	private final ItemTouchHelperAdapter mAdapter;

	public static final float ALPHA_FULL = 1f;

	public SimpleItemTouchHelperCallback(ItemTouchHelperAdapter adapter) {
		mAdapter = adapter;
	}

	@Override
	public boolean isLongPressDragEnabled() {
		return true;
	}

	@Override
	public boolean isItemViewSwipeEnabled() {
		return false;
	}

	@Override
	public int getMovementFlags(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
		int dragFlags = ItemTouchHelper.UP | ItemTouchHelper.DOWN;
		int swipeFlags = ItemTouchHelper.START | ItemTouchHelper.END;
		return makeMovementFlags(dragFlags, swipeFlags);
	}

	@Override
	public boolean onMove(@NonNull RecyclerView recyclerView, RecyclerView.ViewHolder source, RecyclerView.ViewHolder target) {
		if (source.getItemViewType() != target.getItemViewType()) {
			return false;
		}

		mAdapter.onItemMove(source.getAdapterPosition(), target.getAdapterPosition());
		return true;
	}

	@Override
	public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {
		mAdapter.onItemDismiss(viewHolder.getAdapterPosition());
	}

	@Override
	public void onSelectedChanged(RecyclerView.ViewHolder viewHolder, int actionState) {
		// We only want the active item to change
		if (actionState != ItemTouchHelper.ACTION_STATE_IDLE) {
			if (viewHolder instanceof ItemTouchHelperViewHolder) {
				// Let the view holder know that this item is being moved or dragged
				ItemTouchHelperViewHolder itemViewHolder = (ItemTouchHelperViewHolder) viewHolder;
				itemViewHolder.onItemSelected();
			}
		}

		super.onSelectedChanged(viewHolder, actionState);
	}

	@Override
	public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
		super.clearView(recyclerView, viewHolder);

		viewHolder.itemView.setAlpha(ALPHA_FULL);

		if (viewHolder instanceof ItemTouchHelperViewHolder) {
			// Tell the view holder it's time to restore the idle state
			ItemTouchHelperViewHolder itemViewHolder = (ItemTouchHelperViewHolder) viewHolder;
			itemViewHolder.onItemClear();
		}
	}
}
