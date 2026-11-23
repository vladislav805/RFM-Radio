package com.vlad805.fmradio;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.preference.PreferenceManager;

/**
 * Typed settings wrapper around the application's default {@link SharedPreferences}.
 *
 * UI-bound numeric preferences ({@code ListPreference} / {@code EditTextPreference}) are stored as
 * decimal strings by AndroidX Preference and safely parsed internally. Internal non-UI values
 * ({@code LAST_FREQUENCY}, binary metadata, etc.) use native primitive types.
 */
public final class Storage {
	/**
	 * Public preference keys for UI-bound settings defined in {@code res/xml/preferences.xml}.
	 */
	public static final class Key {
		public static final String TUNER_REGION = "tuner_region";
		public static final String TUNER_SPACING = "tuner_spacing";
		public static final String TUNER_POWER_MODE = "tuner_power_mode";
		public static final String TUNER_ANTENNA = "tuner_antenna";
		public static final String TUNER_STEREO = "tuner_stereo";
		public static final String TUNER_SOFT_MUTE = "tuner_soft_mute";
		public static final String TUNER_NOTCH_FILTER = "tuner_notch_filter";

		public static final String RDS_AUTO_AF = "rds_auto_af";

		public static final String RECORDING_DIRECTORY = "recording_directory";
		public static final String RECORDING_FILENAME = "recording_filename";
		public static final String RECORDING_FORMAT = "recording_mode";
		public static final String RECORDING_SAVE_SRT = "recording_save_srt";
		/** Enables retaining recent PCM before the Record command. */
		public static final String RECORDING_SAVE_PAST = "recording_save_past";
		public static final String RECORDING_SHOW_NOTIFY = "recording_show_notify";

		public static final String APP_AUTO_STARTUP = "app_auto_startup";
		public static final String APP_FAVORITES_COLUMNS = "app_favorites_columns";

		public static final String NOTIFICATION_SHOW_RDS = "notification_show_ps";
		public static final String NOTIFICATION_SEEK_BY_FAVORITES = "notification_seek_by_favorites";

		private Key() {}
	}

	// Internal non-UI keys (package-private for StorageTest).
	static final String KEY_LAST_FREQUENCY = "frequency_last";
	static final String KEY_DETECTED_BACKEND = "detected_backend";
	static final String KEY_BINARY_VERSION_NATIVE = "bin_version_native";
	static final String KEY_BINARY_APK_UPDATE_TIME = "bin_apk_update_time";
	static final String KEY_FAVORITES_LIST_CURRENT = "favorites_list_current";

	// Shared defaults needed by external callers.
	public static final int DEFAULT_LAST_FREQUENCY = 87500;
	public static final int DEFAULT_NOTCH_FILTER = 0;
	public static final String DEFAULT_FAVORITES_LIST = "default";

	/** Default values kept alongside their keys (package-private for StorageTest). */
	static final class Default {
		static final int LAST_FREQUENCY = DEFAULT_LAST_FREQUENCY;
		static final int TUNER_REGION = 1;
		static final int TUNER_SPACING = 2;
		static final int TUNER_ANTENNA = 0;
		static final boolean TUNER_STEREO = true;
		static final boolean TUNER_SOFT_MUTE = true;
		static final int TUNER_NOTCH_FILTER = DEFAULT_NOTCH_FILTER;
		static final boolean TUNER_POWER_MODE = false;

		static final boolean RDS_AUTO_AF = false;

		static final String RECORDING_DIRECTORY = "$y-$m-$d";
		static final String RECORDING_FILENAME = "FM-$h$i$s-$f";
		static final int RECORDING_FORMAT = C.RecordFormat.WAV;
		static final boolean RECORDING_SAVE_SRT = false;
		/** Pre-roll capture is opt-in because it keeps audio capture active. */
		static final boolean RECORDING_SAVE_PAST = false;
		static final boolean RECORDING_SHOW_NOTIFY = true;

		static final boolean APP_AUTO_STARTUP = false;
		static final int APP_FAVORITES_COLUMNS = 3;

		static final boolean NOTIFICATION_SHOW_RDS = true;
		static final boolean NOTIFICATION_SEEK_BY_FAVORITES = false;

		static final String FAVORITES_LIST_CURRENT = DEFAULT_FAVORITES_LIST;

		private Default() {}
	}

	private static volatile Storage sInstance;

	private final SharedPreferences mPreferences;

	@VisibleForTesting
	public Storage(@NonNull final SharedPreferences preferences) {
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
		return getPrefInt(Key.TUNER_REGION, Default.TUNER_REGION);
	}

	public void setRegion(final int region) {
		putPrefInt(Key.TUNER_REGION, region);
	}

	public int getSpacing() {
		return getPrefInt(Key.TUNER_SPACING, Default.TUNER_SPACING);
	}

	public boolean isStereoEnabled() {
		return getPrefBoolean(Key.TUNER_STEREO, Default.TUNER_STEREO);
	}

	public boolean isSoftMuteEnabled() {
		return getPrefBoolean(Key.TUNER_SOFT_MUTE, Default.TUNER_SOFT_MUTE);
	}

	public int getNotchFilter() {
		return getPrefInt(Key.TUNER_NOTCH_FILTER, Default.TUNER_NOTCH_FILTER);
	}

	public int getAntenna() {
		return getPrefInt(Key.TUNER_ANTENNA, Default.TUNER_ANTENNA);
	}

	public boolean isLowPowerMode() {
		return getPrefBoolean(Key.TUNER_POWER_MODE, Default.TUNER_POWER_MODE);
	}

	// --- RDS preferences (UI-bound) ---

	public boolean isAutoAfEnabled() {
		return getPrefBoolean(Key.RDS_AUTO_AF, Default.RDS_AUTO_AF);
	}

	// --- Recording preferences (UI-bound) ---

	@NonNull
	public String getRecordingDirectory() {
		return getPrefString(Key.RECORDING_DIRECTORY, Default.RECORDING_DIRECTORY);
	}

	@NonNull
	public String getRecordingFilename() {
		return getPrefString(Key.RECORDING_FILENAME, Default.RECORDING_FILENAME);
	}

	public int getRecordingFormat() {
		return getPrefInt(Key.RECORDING_FORMAT, Default.RECORDING_FORMAT);
	}

	public boolean isRecordingSaveSrt() {
		return getPrefBoolean(Key.RECORDING_SAVE_SRT, Default.RECORDING_SAVE_SRT);
	}

	public boolean isRecordingPreRollEnabled() {
		return getPrefBoolean(Key.RECORDING_SAVE_PAST, Default.RECORDING_SAVE_PAST);
	}

	public boolean isRecordingShowNotify() {
		return getPrefBoolean(Key.RECORDING_SHOW_NOTIFY, Default.RECORDING_SHOW_NOTIFY);
	}

	// --- Application & notification preferences (UI-bound) ---

	public boolean isAutoStartupEnabled() {
		return getPrefBoolean(Key.APP_AUTO_STARTUP, Default.APP_AUTO_STARTUP);
	}

	public int getFavoritesColumns() {
		final int cols = getPrefInt(Key.APP_FAVORITES_COLUMNS, Default.APP_FAVORITES_COLUMNS);
		return cols == 2 ? 2 : 3;
	}

	public boolean isNotificationShowRds() {
		return getPrefBoolean(Key.NOTIFICATION_SHOW_RDS, Default.NOTIFICATION_SHOW_RDS);
	}

	public boolean isNotificationSeekByFavorites() {
		return getPrefBoolean(
				Key.NOTIFICATION_SEEK_BY_FAVORITES,
				Default.NOTIFICATION_SEEK_BY_FAVORITES
		);
	}

	// --- Internal state (non-UI, native types) ---

	public int getLastFrequency() {
		return getInt(KEY_LAST_FREQUENCY, Default.LAST_FREQUENCY);
	}

	public void setLastFrequency(final int frequencyKhz) {
		putInt(KEY_LAST_FREQUENCY, frequencyKhz);
	}

	@NonNull
	public String getDetectedBackend() {
		return getPrefString(KEY_DETECTED_BACKEND, "");
	}

	public void setDetectedBackend(@Nullable final String backendName) {
		putString(KEY_DETECTED_BACKEND, backendName);
	}

	@NonNull
	public String getCurrentFavoriteList() {
		return getPrefString(KEY_FAVORITES_LIST_CURRENT, Default.FAVORITES_LIST_CURRENT);
	}

	public void setCurrentFavoriteList(@NonNull final String listName) {
		putString(KEY_FAVORITES_LIST_CURRENT, listName);
	}

	public int getNativeBinaryVersion() {
		return getInt(KEY_BINARY_VERSION_NATIVE, 0);
	}

	public void setNativeBinaryVersion(final int versionCode) {
		putInt(KEY_BINARY_VERSION_NATIVE, versionCode);
	}

	public long getBinaryApkUpdateTime() {
		return getLong(KEY_BINARY_APK_UPDATE_TIME, 0L);
	}

	public void setBinaryApkUpdateTime(final long updateTimeMs) {
		putLong(KEY_BINARY_APK_UPDATE_TIME, updateTimeMs);
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
