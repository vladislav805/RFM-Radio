package com.vlad805.fmradio.fm;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import com.vlad805.fmradio.BuildConfig;
import com.vlad805.fmradio.C;
import com.vlad805.fmradio.enums.MuteState;

/**
 * vlad805 (c) 2020
 */
public abstract class FMController {
	public static final int DRIVER_NEW = 0;
	public static final int DRIVER_SPIRIT3 = 1;

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
	protected abstract boolean installImpl();

	public final void install() {
		if (installImpl()) {
			fireEvent(C.Event.INSTALLED);
		}
	}

	/**
	 * Launch binary
	 */
	protected abstract boolean launchImpl();

	public final void launch() {
		if (launchImpl()) {
			fireEvent(C.Event.LAUNCHED);
		} else {
			fireError("Error while launch binary");
		}
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
	protected abstract boolean killImpl();

	public final void kill() {
		if (killImpl()) {
			fireEvent(C.Event.DISABLED);
			fireEvent(C.Event.KILLED);
		} else {
			fireError("Error while kill");
		}
	}

	/**
	 * Enable radio audio stream
	 */
	protected abstract boolean enableImpl();

	public final void enable() {
		if (enableImpl()) {
			fireEvent(C.Event.ENABLED);
		} else {
			fireError("Error while enable");
		}
	}

	/**
	 * Disable radio audio stream
	 */
	protected abstract boolean disableImpl();

	public final void disable() {
		if (disableImpl()) {
			fireEvent(C.Event.DISABLED);
		} else {
			fireError("Error while disable");
		}
	}

	/**
	 * Set frequency
	 * @param kHz Frequency in kHz (88.0 MHz = 88000)
	 */
	protected abstract boolean setFrequencyImpl(final int kHz);

	public final void setFrequency(final int kHz) {
		if (setFrequencyImpl(kHz)) {
			Bundle bundle = new Bundle();
			bundle.putInt(C.Key.FREQUENCY, kHz);
			fireEvent(C.Event.FREQUENCY_SET, bundle);
		} else {
			fireError("Error while set frequency");
		}
	}

	/**
	 * Returns RSSI
	 * TODO: range of value?
	 * @return RSSI
	 */
	protected abstract int getSignalStretchImpl();

	/**
	 * Jump to -0.1 MHz or +0.1 MHz
	 * @param direction Direction: "-1" or "1"
	 * @return
	 *   87500 to 108000 if success, new value frequency
	 *   0 if success, but without frequency
	 *   -1 if failed
	 */
	protected abstract int jumpImpl(final int direction);

	public final void jump(final int direction) {
		final int result = jumpImpl(direction);

		if (result < 0) {
			fireError("Error while jumping");
			return;
		}

		Bundle bundle = new Bundle();
		if (result > 0) {
			bundle.putInt(C.Key.FREQUENCY, result);
		}

		fireEvent(C.Event.JUMP_COMPLETE, bundle);
	}

	/**
	 * Hardware automatic seek
	 * @param direction Direction: "-1" or "1"
	 * @return
	 *   87500 to 108000 if success, new value frequency
	 *   0 if success, but without frequency
	 *   -1 if failed
	 */
	protected abstract int hwSeekImpl(final int direction);

	public void hwSeek(final int direction) {
		final int result = hwSeekImpl(direction);

		if (result < 0) {
			fireError("Error while hwSeek");
			return;
		}

		Bundle bundle = new Bundle();
		if (result > 0) {
			bundle.putInt(C.Key.FREQUENCY, result);
		}

		fireEvent(C.Event.HW_SEEK_COMPLETE, bundle);
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
