package com.vlad805.fmradio.enums;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;

public enum AppTheme {
	SYSTEM("system", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
	LIGHT("light", AppCompatDelegate.MODE_NIGHT_NO),
	DARK("dark", AppCompatDelegate.MODE_NIGHT_YES);

	private final String mPreferenceValue;
	private final int mNightMode;

	AppTheme(@NonNull final String preferenceValue, final int nightMode) {
		mPreferenceValue = preferenceValue;
		mNightMode = nightMode;
	}

	@NonNull
	public String getPreferenceValue() {
		return mPreferenceValue;
	}

	public int getNightMode() {
		return mNightMode;
	}

	@NonNull
	public static AppTheme fromPreferenceValue(@Nullable final String value) {
		if (LIGHT.mPreferenceValue.equals(value)) {
			return LIGHT;
		}
		if (DARK.mPreferenceValue.equals(value)) {
			return DARK;
		}
		return SYSTEM;
	}
}
