package com.vlad805.fmradio.fm;

/**
 * vlad805 (c) 2019
 */
public enum MuteState {
	NONE(0),
	LEFT(1),
	RIGHT(2),
	BOTH(3);

	private int state;

	MuteState(int val) {
		state = val;
	}

	public int getState() {
		return state;
	}
}
