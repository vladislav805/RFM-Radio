package com.vlad805.fmradio;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

/**
 * Typed settings wrapper around the application's default {@link SharedPreferences}.
 *
 * UI-bound numeric preferences ({@code ListPreference} / {@code EditTextPreference}) are stored as
 * decimal strings by AndroidX Preference and safely parsed internally. Internal non-UI values
 * ({@code LAST_FREQUENCY}, binary metadata, etc.) use native primitive types.
 */
public final class Storage {
	private static volatile Storage sInstance;

	private final SharedPreferences mPreferences;

	Storage(@NonNull final SharedPreferences preferences) {
		mPreferences = preferences;
	}

	@NonNull
	public static Storage get(@NonNull final Context ctx) {
		Storage instance = sInstance;
		if (instance == null) {
			synchronized (Storage.class) {
				instance = sInstance;
				if (instance == null) {
					final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(
							ctx.getApplicationContext()
					);
					instance = new Storage(prefs);
					sInstance = instance;
				}
			}
		}
		return instance;
	}

	/**
	 * Registers a listener on the underlying {@link SharedPreferences}.
	 * Callers must hold a strong reference to {@code listener} and unregister it when done.
	 */
	public void registerOnChangeListener(
			@NonNull final SharedPreferences.OnSharedPreferenceChangeListener listener
	) {
		mPreferences.registerOnSharedPreferenceChangeListener(listener);
	}

	public void unregisterOnChangeListener(
			@NonNull final SharedPreferences.OnSharedPreferenceChangeListener listener
	) {
		mPreferences.unregisterOnSharedPreferenceChangeListener(listener);
	}

	// --- Tuner preferences (UI-bound) ---

	public int getRegion() {
		return getPrefInt(C.PrefKey.TUNER_REGION, C.PrefDefaultValue.TUNER_REGION);
	}

	public void setRegion(final int region) {
		putPrefInt(C.PrefKey.TUNER_REGION, region);
	}

	public int getSpacing() {
		return getPrefInt(C.PrefKey.TUNER_SPACING, C.PrefDefaultValue.TUNER_SPACING);
	}

	public boolean isStereoEnabled() {
		return getPrefBoolean(C.PrefKey.TUNER_STEREO, C.PrefDefaultValue.TUNER_STEREO);
	}

	public boolean isSoftMuteEnabled() {
		return getPrefBoolean(C.PrefKey.TUNER_SOFT_MUTE, C.PrefDefaultValue.TUNER_SOFT_MUTE);
	}

	public int getNotchFilter() {
		return getPrefInt(C.PrefKey.TUNER_NOTCH_FILTER, C.PrefDefaultValue.TUNER_NOTCH_FILTER);
	}

	public int getAntenna() {
		return getPrefInt(C.PrefKey.TUNER_ANTENNA, C.PrefDefaultValue.TUNER_ANTENNA);
	}

	public boolean isLowPowerMode() {
		return getPrefBoolean(C.PrefKey.TUNER_POWER_MODE, C.PrefDefaultValue.TUNER_POWER_MODE);
	}

	// --- RDS preferences (UI-bound) ---

	public boolean isAutoAfEnabled() {
		return getPrefBoolean(C.PrefKey.RDS_AUTO_AF, C.PrefDefaultValue.RDS_AUTO_AF);
	}

	// --- Recording preferences (UI-bound) ---

	@NonNull
	public String getRecordingDirectory() {
		return getPrefString(C.PrefKey.RECORDING_DIRECTORY, C.PrefDefaultValue.RECORDING_DIRECTORY);
	}

	@NonNull
	public String getRecordingFilename() {
		return getPrefString(C.PrefKey.RECORDING_FILENAME, C.PrefDefaultValue.RECORDING_FILENAME);
	}

	public int getRecordingFormat() {
		return getPrefInt(C.PrefKey.RECORDING_FORMAT, C.PrefDefaultValue.RECORDING_FORMAT);
	}

	public boolean isRecordingSaveSrt() {
		return getPrefBoolean(C.PrefKey.RECORDING_SAVE_SRT, C.PrefDefaultValue.RECORDING_SAVE_SRT);
	}

	public boolean isRecordingPreRollEnabled() {
		return getPrefBoolean(C.PrefKey.RECORDING_SAVE_PAST, C.PrefDefaultValue.RECORDING_SAVE_PAST);
	}

	public boolean isRecordingShowNotify() {
		return getPrefBoolean(C.PrefKey.RECORDING_SHOW_NOTIFY, C.PrefDefaultValue.RECORDING_SHOW_NOTIFY);
	}

	// --- Application & notification preferences (UI-bound) ---

	public boolean isAutoStartupEnabled() {
		return getPrefBoolean(C.PrefKey.APP_AUTO_STARTUP, C.PrefDefaultValue.APP_AUTO_STARTUP);
	}

	public boolean isNotificationShowRds() {
		return getPrefBoolean(C.PrefKey.NOTIFICATION_SHOW_RDS, C.PrefDefaultValue.NOTIFICATION_SHOW_RDS);
	}

	public boolean isNotificationSeekByFavorites() {
		return getPrefBoolean(
				C.PrefKey.NOTIFICATION_SEEK_BY_FAVORITES,
				C.PrefDefaultValue.NOTIFICATION_SEEK_BY_FAVORITES
		);
	}

	// --- Internal state (non-UI, native types) ---

	public int getLastFrequency() {
		return getInt(C.PrefKey.LAST_FREQUENCY, C.PrefDefaultValue.LAST_FREQUENCY);
	}

	public void setLastFrequency(final int frequencyKhz) {
		putInt(C.PrefKey.LAST_FREQUENCY, frequencyKhz);
	}

	@NonNull
	public String getDetectedBackend() {
		return getPrefString(C.PrefKey.DETECTED_BACKEND, "");
	}

	public void setDetectedBackend(@Nullable final String backendName) {
		putString(C.PrefKey.DETECTED_BACKEND, backendName);
	}

	@NonNull
	public String getCurrentFavoriteList() {
		return getPrefString(C.PrefKey.FAVORITES_LIST_CURRENT, C.PrefDefaultValue.FAVORITES_LIST_CURRENT);
	}

	public void setCurrentFavoriteList(@NonNull final String listName) {
		putString(C.PrefKey.FAVORITES_LIST_CURRENT, listName);
	}

	public int getNativeBinaryVersion() {
		return getInt(C.PrefKey.BINARY_VERSION_NATIVE, 0);
	}

	public void setNativeBinaryVersion(final int versionCode) {
		putInt(C.PrefKey.BINARY_VERSION_NATIVE, versionCode);
	}

	public long getBinaryApkUpdateTime() {
		return getLong(C.PrefKey.BINARY_APK_UPDATE_TIME, 0L);
	}

	public void setBinaryApkUpdateTime(final long updateTimeMs) {
		putLong(C.PrefKey.BINARY_APK_UPDATE_TIME, updateTimeMs);
	}

	// --- Validation & low-level parsing helpers ---

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

	/**
	 * Safely parses a decimal string stored by an AndroidX {@code ListPreference} or
	 * {@code EditTextPreference}, returning {@code defaultValue} on null, blank, or invalid input.
	 */
	static int parsePrefInt(@Nullable final String rawValue, final int defaultValue) {
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

	private int getPrefInt(@NonNull final String key, final int defaultValue) {
		try {
			return parsePrefInt(mPreferences.getString(key, null), defaultValue);
		} catch (final ClassCastException e) {
			try {
				return mPreferences.getInt(key, defaultValue);
			} catch (final ClassCastException ignored) {
				return defaultValue;
			}
		}
	}

	private void putPrefInt(@NonNull final String key, final int value) {
		mPreferences.edit().putString(key, String.valueOf(value)).apply();
	}

	private int getInt(@NonNull final String key, final int defaultValue) {
		try {
			return mPreferences.getInt(key, defaultValue);
		} catch (final ClassCastException e) {
			try {
				return parsePrefInt(mPreferences.getString(key, null), defaultValue);
			} catch (final ClassCastException ignored) {
				return defaultValue;
			}
		}
	}

	private void putInt(@NonNull final String key, final int value) {
		mPreferences.edit().putInt(key, value).apply();
	}

	private long getLong(@NonNull final String key, final long defaultValue) {
		try {
			return mPreferences.getLong(key, defaultValue);
		} catch (final ClassCastException e) {
			try {
				return mPreferences.getInt(key, (int) defaultValue);
			} catch (final ClassCastException ignored) {
				return defaultValue;
			}
		}
	}

	private void putLong(@NonNull final String key, final long value) {
		mPreferences.edit().putLong(key, value).apply();
	}

	@NonNull
	private String getPrefString(@NonNull final String key, @NonNull final String defaultValue) {
		try {
			final String val = mPreferences.getString(key, defaultValue);
			return val != null ? val : defaultValue;
		} catch (final ClassCastException e) {
			return defaultValue;
		}
	}

	private void putString(@NonNull final String key, @Nullable final String value) {
		mPreferences.edit().putString(key, value).apply();
	}

	private boolean getPrefBoolean(@NonNull final String key, final boolean defaultValue) {
		try {
			return mPreferences.getBoolean(key, defaultValue);
		} catch (final ClassCastException e) {
			return defaultValue;
		}
	}
}
