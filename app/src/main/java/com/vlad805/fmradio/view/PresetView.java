package com.vlad805.fmradio.view;

import android.content.Context;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.PopupMenu;
import com.vlad805.fmradio.R;

import static android.util.TypedValue.COMPLEX_UNIT_DIP;
import static android.view.HapticFeedbackConstants.LONG_PRESS;

/**
 * vlad805 (c) 2018
 */
public class PresetView extends Button {

	private int mIndex;
	private int mFrequency;
	private String mTitle;
	private OnMenuPresetSelected mMenuListener;

	public static final int MENU_REMOVE = 100;
	public static final int MENU_RENAME = 101;
	public static final int MENU_CREATE = 102;
	public static final int MENU_REPLACE = 103;

	private int mSizeMHzPx;
	private int mSizeTitlePx;

	public interface OnMenuPresetSelected {
		public void onClick(int action, PresetView v);
	}

	public PresetView(Context context) {
		super(context);

		setMaxLines(2);
		setLayoutParams(new ViewGroup.LayoutParams(getResources().getDimensionPixelSize(R.dimen.preset_width), ViewGroup.LayoutParams.MATCH_PARENT));

		mSizeMHzPx = getResources().getDimensionPixelSize(R.dimen.preset_mhz_size);
		mSizeTitlePx = getResources().getDimensionPixelSize(R.dimen.preset_title_size);

		setAllCaps(false);
		setHapticFeedbackEnabled(true);


		populate(0, "DFM");
	}

	public PresetView populate(int index, int frequency, String title) {
		mFrequency = frequency;
		mIndex = index;
		mTitle = title;

		if (isEmpty()) {
			Spannable span = new SpannableString("+");
			span.setSpan(new AbsoluteSizeSpan(mSizeMHzPx), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
			setText(span);
		} else {
			float freq = mFrequency / 1000f;
			int n = String.valueOf(freq).length(); // FIXME
			String name = mTitle != null && !mTitle.isEmpty() ? mTitle : "-";
			int m = name.length();
			String full = freq + "\n" + name;

			Spannable span = new SpannableString(full);
			span.setSpan(new AbsoluteSizeSpan(mSizeMHzPx), 0, n, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

			if (mTitle == null) {
				span.setSpan(new ForegroundColorSpan(0x88ffffff), n + 1, n + m + 1,  Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
			} else {
				span.setSpan(new AbsoluteSizeSpan(mSizeTitlePx), n + 1, n + m + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
			}

			setText(span);
		}

		return this;
	}

	public PresetView populate(int frequency, String title) {
		return populate(mIndex, frequency, title);
	}

	public PresetView setListeners(OnClickListener onClick, OnMenuPresetSelected menuListener) {
		setOnClickListener(onClick);
		setOnLongClickListener(new OnLongClickListener() {
			@Override
			public boolean onLongClick(View v) {
				v.performHapticFeedback(LONG_PRESS);
				onMenuOpen();
				return true;
			}
		});
		mMenuListener = menuListener;
		return this;
	}

	private void onMenuOpen() {
		Context c = getContext();
		PopupMenu popupMenu = new PopupMenu(c, this);
		Menu menu = popupMenu.getMenu();
		if (isEmpty()) {
			menu.add(1, MENU_CREATE, 1, c.getString(R.string.popup_preset_add));
		} else {
			menu.add(1, MENU_RENAME, 1, c.getString(R.string.popup_preset_rename));
			menu.add(1, MENU_REPLACE, 2, c.getString(R.string.popup_preset_replace));
			menu.add(1, MENU_REMOVE, 3, c.getString(R.string.popup_preset_remove));
		}

		popupMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
			@Override
			public boolean onMenuItemClick(MenuItem item) {
				mMenuListener.onClick(item.getItemId(), PresetView.this);
				return true;
			}
		});

		popupMenu.show();
	}

	public int getIndex() {
		return mIndex;
	}

	/**
	 * Frequency in KHz
	 * @return KHz
	 */
	public int getFrequency() {
		return mFrequency;
	}

	public String getTitle() {
		return mTitle;
	}

	public boolean isEmpty() {
		return mFrequency == 0;
	}

}
