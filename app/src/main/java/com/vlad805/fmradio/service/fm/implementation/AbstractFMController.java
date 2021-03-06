package com.vlad805.fmradio.service.fm.implementation;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import com.vlad805.fmradio.BuildConfig;
import com.vlad805.fmradio.C;
import com.vlad805.fmradio.Storage;
import com.vlad805.fmradio.enums.MuteState;
import com.vlad805.fmradio.service.fm.LaunchBinaryConfig;

/**
 * vlad805 (c) 2020
 */
public abstract class AbstractFMController {
	public static final int DRIVER_QUALCOMM = 0;
	public static final int DRIVER_SPIRIT3 = 1;
	public static final int DRIVER_EMPTY = 999;

	public interface Callback<T> {
		void onResult(T result);
		default void onError(Error e) {

		}
	}

	protected final LaunchBinaryConfig config;
	protected final Context context;

	public AbstractFMController(final LaunchBinaryConfig config, final Context context) {
		this.config = config;
		this.context = context;
	}

	/**
	 * Returns path to application files
	 */
	@SuppressLint("SdCardPath")
	public final String getAppRootPath() {
		return "/data/data/" + BuildConfig.APPLICATION_ID + "/files/";
	}

	public final String getAppRootPath(String path) {
		return getAppRootPath() + path;
	}

	/**
	 * Check if binary for FM is installed in system
	 * @return True if installed
	 */
	public abstract boolean isInstalled();

	/**
	 * Is obsolete binary?
	 * @return True, if binary is obsolete and need to update it
	 */
	public abstract boolean isObsolete();

	/**
	 * Install binary(ies) to system
	 * Calls when isInstalled() == false OR isObsolete() == true
	 */
	protected abstract void installImpl(final Callback<Void> callback);

	public final void install() {
		fireEvent(C.Event.INSTALLING);
		installImpl(result -> fireEvent(C.Event.INSTALLED));
	}

	/**
	 * Launch binary
	 */
	protected abstract void launchImpl(final Callback<Void> callback);

	public final void launch() {
		fireEvent(C.Event.LAUNCHING);
		launchImpl(new Callback<Void>() {
			@Override
			public void onResult(Void result) {
				fireEvent(C.Event.LAUNCHED);
			}

			@Override
			public void onError(Error e) {
				fireEvent(C.Event.LAUNCH_FAILED);
			}
		});
	}

	protected abstract void applyPreferenceImpl(String key, String value);

	/**
	 * Apply preference
	 * @param key Preference key from C.PrefKey
	 * @param value Value as string
	 */
	public final void applyPreference(final String key, final String value) {
		applyPreferenceImpl(key, value);
	}

	/**
	 * Check binary for exists and fresh
	 */
	public final void prepareBinary() {
		fireEvent(C.Event.PREPARING);
		if (!isInstalled() || isObsolete()) {
			install();
		} else {
			fireEvent(C.Event.INSTALLED);
		}
	}

	/**
	 * Kill process of binary
	 */
	protected abstract void killImpl(final Callback<Void> callback);

	public final void kill() {
		killImpl(result -> {
			fireEvent(C.Event.DISABLED);
			fireEvent(C.Event.KILLED);
		});
	}

	/**
	 * Enable radio audio stream
	 */
	protected abstract void enableImpl(final Callback<Void> callback);

	public final void enable() {
		fireEvent(C.Event.ENABLING);
		enableImpl(result -> {
			setupTunerByPreferences(new String[] {
					C.PrefKey.RDS_ENABLE,
					C.PrefKey.TUNER_REGION,
					C.PrefKey.TUNER_SPACING,
					C.PrefKey.TUNER_STEREO,
					C.PrefKey.TUNER_ANTENNA,
			});
			fireEvent(C.Event.ENABLED);
		});
	}

	public void setupTunerByPreferences(final String[] changed) {
		for (final String key : changed) {
			String value = null;
			switch (key) {
				case C.PrefKey.RDS_ENABLE: {
					final boolean isRdsEnabled = Storage.getPrefBoolean(context, key, true);
					value = isRdsEnabled ? "1" : "0";
					break;
				}

				case C.PrefKey.TUNER_REGION: {
					final int band = Storage.getPrefInt(context, key, C.PrefDefaultValue.TUNER_REGION);
					value = String.valueOf(band);
					break;
				}

				case C.PrefKey.TUNER_STEREO: {
					final boolean stereo = Storage.getPrefBoolean(context, key, C.PrefDefaultValue.TUNER_STEREO);
					value =  stereo ? "1" : "0";
					break;
				}

				case C.PrefKey.TUNER_SPACING: {
					final int spacing = Storage.getPrefInt(context, key, C.PrefDefaultValue.TUNER_SPACING);
					applyPreference(key, String.valueOf(spacing));

					// Important! After changing the spacing, stereo/mode is reset to mono.
					final boolean stereo = Storage.getPrefBoolean(context, C.PrefKey.TUNER_STEREO, C.PrefDefaultValue.TUNER_STEREO);
					applyPreference(C.PrefKey.TUNER_STEREO, stereo ? "1" : "0");
					break;
				}

				case C.PrefKey.TUNER_ANTENNA: {
					final int region = Storage.getPrefInt(context, key, C.PrefDefaultValue.TUNER_ANTENNA);
					value = String.valueOf(region);
					break;
				}

				case C.PrefKey.TUNER_POWER_MODE: {
					final boolean isLowPower = Storage.getPrefBoolean(context, key, C.PrefDefaultValue.TUNER_POWER_MODE);
					value = isLowPower ? "low" : "normal";
					break;
				}

				case C.PrefKey.RDS_AUTO_AF: {
					final boolean enabled = Storage.getPrefBoolean(context, key, C.PrefDefaultValue.RDS_AUTO_AF);
					value = enabled ? "1" : "0";
					break;
				}
			}

			if (value != null) {
				applyPreference(key, value);
			}
		}
	}

	/**
	 * Disable radio audio stream
	 */
	protected abstract void disableImpl(final Callback<Void> callback);

	public final void disable() {
		fireEvent(C.Event.DISABLING);
		disableImpl(result -> fireEvent(C.Event.DISABLED));
	}

	/**
	 * Set frequency
	 * @param kHz Frequency in kHz (88.0 MHz = 88000)
	 */
	protected abstract void setFrequencyImpl(final int kHz, final Callback<Integer> callback);

	public final void setFrequency(final int kHz) {
		setFrequencyImpl(kHz, result -> {
			Bundle bundle = new Bundle();
			bundle.putInt(C.Key.FREQUENCY, kHz);
			fireEvent(C.Event.FREQUENCY_SET, bundle);
		});
	}

	/**
	 * Jump to -0.1 MHz or +0.1 MHz
	 * @param direction Direction: "-1" or "1"
	 * @param callback Callback with
	 *   87500 to 108000 if success, new value frequency
	 *   0 if success, but without frequency
	 *   -1 if failed
	 */
	protected abstract void jumpImpl(final int direction, final Callback<Integer> callback);

	public final void jump(final int direction) {
		jumpImpl(direction, result -> {
			if (result < 0) {
				fireError("Error while jumping");
				return;
			}

			Bundle bundle = new Bundle();
			if (result > 0) {
				bundle.putInt(C.Key.FREQUENCY, result);
			}

			fireEvent(C.Event.JUMP_COMPLETE, bundle);
		});
	}

	/**
	 * Hardware automatic seek
	 * @param direction Direction: "-1" or "1"
	 * @param callback callback with value
	 *   87500 to 108000 if success, new value frequency
	 *   0 if success, but without frequency
	 *   -1 if failed
	 */
	protected abstract void hwSeekImpl(final int direction, final Callback<Integer> callback);

	public void hwSeek(final int direction) {
		hwSeekImpl(direction, result -> {
			if (result < 0) {
				fireError("Error while hwSeek");
				return;
			}

			Bundle bundle = new Bundle();
			if (result > 0) {
				bundle.putInt(C.Key.FREQUENCY, result);
			}

			fireEvent(C.Event.HW_SEEK_COMPLETE, bundle);
		});
	}

    protected abstract void hwSearchImpl();

	public void hwSearch() {
		hwSearchImpl();
	}

	protected abstract void setPowerModeImpl(final String mode);

	public void setPowerMode(final String mode) {
		setPowerModeImpl(mode);
	}

	/**
	 * Set mute state
	 * @param state State
	 */
	public abstract void setMute(final MuteState state, final Callback<Void> callback);

	/**
	 * Broadcast event with arguments
	 * @param event Event name
	 * @param bundle Arguments
	 */
	protected void fireEvent(final String event, final Bundle bundle) {
		context.sendBroadcast(new Intent(event).putExtras(bundle));
	}

	/**
	 * Broadcast event
	 * @param event Event name
	 */
	protected void fireEvent(final String event) {
		context.sendBroadcast(new Intent(event));
	}

	protected void fireError(final String message) {
		final Bundle bundle = new Bundle();
		bundle.putString(Intent.EXTRA_TEXT, message);
		fireEvent(C.Event.ERROR_OCCURRED, bundle);
	}

	public static boolean isAbleToWork() {
		return false;
	}
}
