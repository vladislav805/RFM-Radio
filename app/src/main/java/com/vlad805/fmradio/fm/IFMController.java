package com.vlad805.fmradio.fm;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import com.vlad805.fmradio.BuildConfig;
import com.vlad805.fmradio.enums.MuteState;
import com.vlad805.fmradio.service.FMService;

/**
 * vlad805 (c) 2020
 */
public abstract class IFMController {

	protected final LaunchConfig config;

	public IFMController(LaunchConfig config) {
		this.config = config;
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
	 * Install binaries to system
	 * Calls when isInstalled() == false OR isObsolete() == true
	 */
	public abstract void install(Context context);

	/**
	 * Launch binary
	 */
	public abstract void launch(LaunchConfig config);

	public void init(Context context) {
		if (!isInstalled() || isObsolete()) {
			install(context);
		}
	}

	/**
	 * Kill process of binary
	 */
	public abstract void kill();

	/**
	 * Enable radio audio stream
	 */
	public abstract void enable();

	/**
	 * Disable radio audio stream
	 */
	public abstract void disable();

	/**
	 * Set frequency
	 * @param kHz Frequency in kHz (88.0 MHz = 88000)
	 */
	public abstract void setFrequency(int kHz);

	/**
	 * Returns RSSI
	 * TODO: range of value?
	 * @return RSSI
	 */
	public abstract int getSignalStretch();

	/**
	 * Jump to -0.1 MHz or +0.1 MHz
	 * @param direction Direction: "-1" or "1"
	 */
	public abstract void jump(int direction);

	/**
	 * Hardware automatic seek
	 * @param direction Direction: "-1" or "1"
	 */
	public abstract void hwSeek(int direction);

	/**
	 * Set mute state
	 * @param state State
	 */
	public abstract void setMute(MuteState state);

	/**
	 * Search stations
	 */
	public abstract void search();

	/**
	 * Returns RDS info
	 * @return RDS info
	 */
	public abstract IRdsStruct getRds();

	public abstract Intent poll();

	public void fireEvent(Context context, String event, Intent intent) {
		context.startService(intent.setAction(event).setClass(context, FMService.class));
	}

}
