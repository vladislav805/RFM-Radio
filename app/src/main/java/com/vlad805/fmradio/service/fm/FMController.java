package com.vlad805.fmradio.service.fm;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.SparseArray;
import com.vlad805.fmradio.BuildConfig;
import com.vlad805.fmradio.C;
import com.vlad805.fmradio.enums.MuteState;

/**
 * vlad805 (c) 2020
 */
public abstract class FMController {
	public static final int DRIVER_QUALCOMM = 0;
	public static final int DRIVER_SPIRIT3 = 1;
	public static final int DRIVER_EMPTY = 999;

	public static final SparseArray<String> sDrivers;

	static {
		sDrivers = new SparseArray<>();
		sDrivers.put(DRIVER_QUALCOMM, "QualComm V4L2 service");
		sDrivers.put(DRIVER_SPIRIT3, "QualComm V4L2 service from Spirit3 (installed spirit3 required)");
		if (BuildConfig.DEBUG) {
			sDrivers.put(DRIVER_EMPTY, "Test service (empty)");
		}
	}

	public interface Callback<T> {
		void onResult(T result);
		default void onError(Error e) {

		}
	}

	protected final LaunchConfig config;
	protected final Context context;

	public FMController(final LaunchConfig config, final Context context) {
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
		installImpl(result -> fireEvent(C.Event.INSTALLED));
	}

	/**
	 * Launch binary
	 */
	protected abstract void launchImpl(final Callback<Void> callback);

	public final void launch() {
		launchImpl(result -> fireEvent(C.Event.LAUNCHED));
	}

	/**
	 * Check binary for exists and fresh
	 */
	public final void prepareBinary() {
		if (!isInstalled() || isObsolete()) {
			install();
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
		enableImpl(result -> {
			fireEvent(C.Event.ENABLED);
		});
	}

	/**
	 * Disable radio audio stream
	 */
	protected abstract void disableImpl(final Callback<Void> callback);

	public final void disable() {
		disableImpl(result -> {
			fireEvent(C.Event.DISABLED);
		});
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
	 * Returns RSSI
	 * TODO: range of value?
	 */
	protected abstract void getSignalStretchImpl(final Callback<Integer> result);

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

	/**
	 * Set mute state
	 * @param state State
	 */
	public abstract void setMute(final MuteState state);

	/**
	 * Search stations
	 */
	public abstract void search();

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

}
