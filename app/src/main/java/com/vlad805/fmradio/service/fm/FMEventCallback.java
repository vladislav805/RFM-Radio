package com.vlad805.fmradio.service.fm;

/**
 * vlad805 (c) 2020
 */
public interface FMEventCallback {
	void onEvent(String event);
	void onStatePatch(RadioStatePatch patch);
	void onSearchDone(int[] stations);
}
