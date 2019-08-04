package com.vlad805.fmradio;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * vlad805 (c) 2019
 */
public final class Storage {

	private static SharedPreferences mPrefs;

	public static SharedPreferences getInstance(Context ctx) {
		if (mPrefs == null) {
			mPrefs = ctx.getSharedPreferences(C.DEFAULT_PREFERENCES, Context.MODE_PRIVATE);
		}
		return mPrefs;
	}

	private Storage(Context ctx) {

	}

}
