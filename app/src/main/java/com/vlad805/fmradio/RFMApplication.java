package com.vlad805.fmradio;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;

import com.vlad805.fmradio.enums.AppTheme;

public final class RFMApplication extends Application {
	@Override
	public void onCreate() {
		super.onCreate();
		applyTheme(Storage.get(this).getAppTheme());
	}

	public static void applyTheme(@NonNull final AppTheme theme) {
		AppCompatDelegate.setDefaultNightMode(theme.getNightMode());
	}
}
