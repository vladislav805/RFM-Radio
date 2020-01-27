package com.vlad805.fmradio.view;

import android.content.Context;
import android.util.AttributeSet;
import androidx.preference.EditTextPreference;

/**
 * vlad805 (c) 2020
 */
public class IntEditTextPreference extends EditTextPreference {

	public IntEditTextPreference(Context context) {
		super(context);
	}

	public IntEditTextPreference(Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	public IntEditTextPreference(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
	}

	@Override
	protected String getPersistedString(String defaultReturnValue) {
		return String.valueOf(getPersistedInt(-1));
	}

	@Override
	protected boolean persistString(String value) {
		return persistInt(Integer.parseInt(value));
	}
}