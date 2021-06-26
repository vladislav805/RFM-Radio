package com.vlad805.fmradio;

import android.content.Context;
import net.grandcentrix.tray.AppPreferences;

/**
 * vlad805 (c) 2019
 */
public final class Storage {
	private static AppPreferences mPreferences;

	public static AppPreferences getInstance(final Context ctx) {
		if (mPreferences == null) {
			mPreferences = new AppPreferences(ctx.getApplicationContext());
		}
		return mPreferences;
	}

	public static int getPrefInt(final Context ctx, final String key, final int defaultValue) {
		final String val = getPrefString(ctx, key, "" + defaultValue);
		return Utils.parseInt(val);
	}

	public static String getPrefString(final Context ctx, final String key, final String defaultValue) {
		final String val = getInstance(ctx).getString(key, defaultValue);
		return val != null ? val : defaultValue;
	}

	public static boolean getPrefBoolean(final Context ctx, final String key, final boolean defaultValue) {
		return getInstance(ctx).getBoolean(key, defaultValue);
	}

	private Storage() {

	}
}
