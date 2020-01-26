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

	public static SharedPreferences getInstance(Context ctx) {
		if (mShared == null) {
			mShared = ctx.getSharedPreferences(C.DEFAULT_PREFERENCES, Context.MODE_PRIVATE);
		}
		return mShared;
	}

	public static SharedPreferences getPrefs(Context ctx) {
		if (mPrefs == null) {
			mPrefs = PreferenceManager.getDefaultSharedPreferences(ctx);
		}

		return mPrefs;
	}

	private Storage() {

	}
}
