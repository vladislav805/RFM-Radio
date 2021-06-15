package com.vlad805.fmradio.service.fm;

import com.vlad805.fmradio.service.fm.implementation.AbstractFMController;

/**
 * The interface for the controller, which constantly polls FM for updates,
 * for example, for RSSI, PS, RT, etc..
 * vlad805 (c) 2020
 */
public interface IFMEventPoller {
	/**
	 * Call a poll for fresh data
	 * @param callback Call back with Bundle fresh data
	 * Supported values:
	 * 	- C.Key.FREQUENCY
	 * 	- C.Key.RSSI
	 *  - C.Key.PS
	 *  - C.Key.RT
	 */
	void poll(final AbstractFMController.Callback<android.os.Bundle> callback);
}
