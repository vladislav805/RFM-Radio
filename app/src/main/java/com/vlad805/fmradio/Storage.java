package com.vlad805.fmradio;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;

/**
 * vlad805 (c) 2019
 */
public final class Storage {
	private static SharedPreferences mShared;
	private static SharedPreferences mPrefs;

	public static SharedPreferences getInstance(final Context ctx) {
		if (mShared == null) {
			mShared = ctx.getSharedPreferences(C.DEFAULT_PREFERENCES, Context.MODE_PRIVATE);
		}
		return mShared;
	}

	private static SharedPreferences getPrefs(final Context ctx) {
		if (mPrefs == null) {
			mPrefs = PreferenceManager.getDefaultSharedPreferences(ctx);
		}

		return mPrefs;
	}

	public static int getPrefInt(final Context ctx, final String key, final int defaultValue) {
		final String val = getPrefString(ctx, key, "" + defaultValue);
		return Utils.parseInt(val);
	}

	public static String getPrefString(final Context ctx, final String key, final String defaultValue) {
		final String val = getPrefs(ctx).getString(key, defaultValue);
		return val != null ? val : defaultValue;
	}

	public static boolean getPrefBoolean(final Context ctx, final String key, final boolean defaultValue) {
		return getPrefs(ctx).getBoolean(key, defaultValue);
	}

	private Storage() {

	}
}
