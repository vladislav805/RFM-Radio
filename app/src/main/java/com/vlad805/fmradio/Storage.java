package com.vlad805.fmradio;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

/**
 * Typed wrapper around the application's default {@link SharedPreferences}.
 *
 * UI-bound numeric preferences ({@code ListPreference} / {@code EditTextPreference}) are stored as
 * decimal strings by AndroidX Preference and read via {@link #getPrefInt} / {@link #putPrefInt}.
 * Internal non-UI values ({@code LAST_FREQUENCY}, binary metadata, etc.) use native types via
 * {@link #getInt}/{@link #putInt} and {@link #getLong}/{@link #putLong}.
 */
public final class Storage {
	private static volatile SharedPreferences sPreferences;

	@NonNull
	public static SharedPreferences getInstance(@NonNull final Context ctx) {
		SharedPreferences prefs = sPreferences;
		if (prefs == null) {
			synchronized (Storage.class) {
				prefs = sPreferences;
				if (prefs == null) {
					prefs = PreferenceManager.getDefaultSharedPreferences(ctx.getApplicationContext());
					sPreferences = prefs;
				}
			}
		}
		return prefs;
	}

	/**
	 * Safely parses a decimal string stored by an AndroidX {@code ListPreference} or
	 * {@code EditTextPreference}, returning {@code defaultValue} on null, blank, or invalid input.
	 */
	public static int parsePrefInt(@Nullable final String rawValue, final int defaultValue) {
		if (rawValue == null) {
			return defaultValue;
		}
		final String trimmed = rawValue.trim();
		if (trimmed.isEmpty()) {
			return defaultValue;
		}
		try {
			return Integer.parseInt(trimmed);
		} catch (final NumberFormatException e) {
			return defaultValue;
		}
	}

	/**
	 * Returns true if {@code rawValue} trims to a valid non-negative decimal integer.
	 */
	public static boolean isValidNonNegativeInt(@Nullable final String rawValue) {
		if (rawValue == null) {
			return false;
		}
		final String trimmed = rawValue.trim();
		if (trimmed.isEmpty()) {
			return false;
		}
		try {
			return Integer.parseInt(trimmed) >= 0;
		} catch (final NumberFormatException e) {
			return false;
		}
	}

	public static int getPrefInt(@NonNull final Context ctx, @NonNull final String key, final int defaultValue) {
		return getPrefInt(getInstance(ctx), key, defaultValue);
	}

	public static int getPrefInt(@NonNull final SharedPreferences prefs, @NonNull final String key, final int defaultValue) {
		try {
			return parsePrefInt(prefs.getString(key, null), defaultValue);
		} catch (final ClassCastException e) {
			try {
				return prefs.getInt(key, defaultValue);
			} catch (final ClassCastException ignored) {
				return defaultValue;
			}
		}
	}

	public static void putPrefInt(@NonNull final Context ctx, @NonNull final String key, final int value) {
		putPrefInt(getInstance(ctx), key, value);
	}

	public static void putPrefInt(@NonNull final SharedPreferences prefs, @NonNull final String key, final int value) {
		prefs.edit().putString(key, String.valueOf(value)).apply();
	}

	public static int getInt(@NonNull final Context ctx, @NonNull final String key, final int defaultValue) {
		return getInt(getInstance(ctx), key, defaultValue);
	}

	public static int getInt(@NonNull final SharedPreferences prefs, @NonNull final String key, final int defaultValue) {
		try {
			return prefs.getInt(key, defaultValue);
		} catch (final ClassCastException e) {
			try {
				return parsePrefInt(prefs.getString(key, null), defaultValue);
			} catch (final ClassCastException ignored) {
				return defaultValue;
			}
		}
	}

	public static void putInt(@NonNull final Context ctx, @NonNull final String key, final int value) {
		putInt(getInstance(ctx), key, value);
	}

	public static void putInt(@NonNull final SharedPreferences prefs, @NonNull final String key, final int value) {
		prefs.edit().putInt(key, value).apply();
	}

	public static long getLong(@NonNull final Context ctx, @NonNull final String key, final long defaultValue) {
		return getLong(getInstance(ctx), key, defaultValue);
	}

	public static long getLong(@NonNull final SharedPreferences prefs, @NonNull final String key, final long defaultValue) {
		try {
			return prefs.getLong(key, defaultValue);
		} catch (final ClassCastException e) {
			try {
				return prefs.getInt(key, (int) defaultValue);
			} catch (final ClassCastException ignored) {
				return defaultValue;
			}
		}
	}

	public static void putLong(@NonNull final Context ctx, @NonNull final String key, final long value) {
		putLong(getInstance(ctx), key, value);
	}

	public static void putLong(@NonNull final SharedPreferences prefs, @NonNull final String key, final long value) {
		prefs.edit().putLong(key, value).apply();
	}

	@NonNull
	public static String getPrefString(@NonNull final Context ctx, @NonNull final String key, @NonNull final String defaultValue) {
		return getPrefString(getInstance(ctx), key, defaultValue);
	}

	@NonNull
	public static String getPrefString(@NonNull final SharedPreferences prefs, @NonNull final String key, @NonNull final String defaultValue) {
		try {
			final String val = prefs.getString(key, defaultValue);
			return val != null ? val : defaultValue;
		} catch (final ClassCastException e) {
			return defaultValue;
		}
	}

	public static void putString(@NonNull final Context ctx, @NonNull final String key, @Nullable final String value) {
		putString(getInstance(ctx), key, value);
	}

	public static void putString(@NonNull final SharedPreferences prefs, @NonNull final String key, @Nullable final String value) {
		prefs.edit().putString(key, value).apply();
	}

	public static boolean getPrefBoolean(@NonNull final Context ctx, @NonNull final String key, final boolean defaultValue) {
		return getPrefBoolean(getInstance(ctx), key, defaultValue);
	}

	public static boolean getPrefBoolean(@NonNull final SharedPreferences prefs, @NonNull final String key, final boolean defaultValue) {
		try {
			return prefs.getBoolean(key, defaultValue);
		} catch (final ClassCastException e) {
			return defaultValue;
		}
	}

	public static void putBoolean(@NonNull final Context ctx, @NonNull final String key, final boolean value) {
		putBoolean(getInstance(ctx), key, value);
	}

	public static void putBoolean(@NonNull final SharedPreferences prefs, @NonNull final String key, final boolean value) {
		prefs.edit().putBoolean(key, value).apply();
	}

	private Storage() {}
}
