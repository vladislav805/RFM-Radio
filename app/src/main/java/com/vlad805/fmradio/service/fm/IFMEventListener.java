package com.vlad805.fmradio.service.fm;

/**
 * Interface for a controller that can receive and process events
 * vlad805 (c) 2020
 */
public interface IFMEventListener {
	/**
	 * Changing the callback of ready-made, processed events
	 * @param callback Callback
	 */
	void setEventListener(final FMEventCallback callback);
}
