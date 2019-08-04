package com.vlad805.fmradio.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.LinearLayout;
import com.vlad805.fmradio.R;

/**
 * vlad805 (c) 2019
 */
public class PresetListView extends LinearLayout {

	private static final int PRESET_COUNT = 20;

	public PresetListView(Context context) {
		super(context);

		init();
	}

	public PresetListView(Context context, AttributeSet attrs) {
		super(context, attrs);

		init();
	}


	private void init() {

		LayoutInflater.from(getContext()).inflate(R.layout.preset_list, this, true);

		setOrientation(HORIZONTAL);

		for (int i = 0; i < PRESET_COUNT; ++i) {
			addView(new PresetView(getContext()));
		}
	}


}
