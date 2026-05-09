package com.vlad805.fmradio.view.adapter;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.Utils;
import com.vlad805.fmradio.models.FavoriteStation;
import com.vlad805.fmradio.view.ItemTouchHelperAdapter;
import com.vlad805.fmradio.view.ItemTouchHelperViewHolder;

import java.util.Collections;
import java.util.List;

/**
 * vlad805 (c) 2020
 */
public class FavoritePanelAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> implements ItemTouchHelperAdapter {
	private final LayoutInflater mInflater;
	protected List<FavoriteStation> mList;
	private int mActiveFrequency = -1;
	private boolean mEditMode;

	private static final int TYPE_STATION = 0;
	private static final int TYPE_BUTTON = 1;

	private static final String[] BUTTONS = {
			"+"
	};

	public FavoritePanelAdapter(Context context) {
		mInflater = LayoutInflater.from(context);
	}

	public void setList(List<FavoriteStation> stations) {
		mList = stations;
		notifyDataSetChanged();
	}

	public void setActiveFrequency(final int activeFrequency) {
		if (mActiveFrequency == activeFrequency) {
			return;
		}

		final int previousIndex = findStationIndexByFrequency(mActiveFrequency);
		mActiveFrequency = activeFrequency;
		final int currentIndex = findStationIndexByFrequency(mActiveFrequency);

		if (previousIndex >= 0) {
			notifyItemChanged(previousIndex);
		}

		if (currentIndex >= 0) {
			notifyItemChanged(currentIndex);
		}
	}

	public void setEditMode(final boolean editMode) {
		if (mEditMode == editMode) {
			return;
		}

		mEditMode = editMode;
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
				((ViewHolder) holder).populate(station, station.getFrequency() == mActiveFrequency, mEditMode);
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
		return mList.size() + BUTTONS.length;
	}

	@Override
	public void onItemMove(final int fromPosition, final int toPosition) {
		if (mList == null || fromPosition < 0 || toPosition < 0) {
			return;
		}

		if (fromPosition >= mList.size() || toPosition >= mList.size()) {
			return;
		}

		if (fromPosition < toPosition) {
			for (int i = fromPosition; i < toPosition; i++) {
				Collections.swap(mList, i, i + 1);
			}
		} else {
			for (int i = fromPosition; i > toPosition; i--) {
				Collections.swap(mList, i, i - 1);
			}
		}

		notifyItemMoved(fromPosition, toPosition);
	}

	@Override
	public void onItemDismiss(final int position) {
	}

	private int findStationIndexByFrequency(final int frequency) {
		if (mList == null) {
			return -1;
		}

		for (int i = 0; i < mList.size(); ++i) {
			if (mList.get(i).getFrequency() == frequency) {
				return i;
			}
		}

		return -1;
	}

	public static class ViewHolder extends RecyclerView.ViewHolder implements ItemTouchHelperViewHolder {
		private final TextView frequency;
		private final TextView title;
		private final ImageView reorder;
		private final ColorStateList defaultFrequencyColor;
		private final ColorStateList defaultTitleColor;
		private final int activeFrequencyColor;

		private ViewHolder(View root) {
			super(root);

			frequency = root.findViewById(R.id.favorite_panel_item_frequency);
			title = root.findViewById(R.id.favorite_panel_item_title);
			reorder = root.findViewById(R.id.favorite_panel_item_reorder);
			defaultFrequencyColor = frequency.getTextColors();
			defaultTitleColor = title.getTextColors();
			activeFrequencyColor = ContextCompat.getColor(root.getContext(), R.color.color_accent);
		}

		public void populate(final FavoriteStation station, final boolean active, final boolean editMode) {
			itemView.setSelected(active);
			itemView.setAlpha(ALPHA_FULL);
			frequency.setText(Utils.getMHz(station.getFrequency()).trim());
			title.setText(station.getTitle());
			frequency.setTextColor(active ? ColorStateList.valueOf(activeFrequencyColor) : defaultFrequencyColor);
			title.setTextColor(defaultTitleColor);
			reorder.setVisibility(editMode ? View.VISIBLE : View.GONE);
		}

		@Override
		public void onItemSelected() {
			itemView.setAlpha(0.7f);
		}

		@Override
		public void onItemClear() {
			itemView.setAlpha(ALPHA_FULL);
		}
	}

	private static final float ALPHA_FULL = 1f;

	public static class ViewHolderButton extends RecyclerView.ViewHolder {
		private final TextView content;

		private ViewHolderButton(final View root) {
			super(root);

			content = root.findViewById(R.id.favorite_station_item_button);
		}

		public void setContent(final String text) {
			content.setText(text);
		}
	}
}
