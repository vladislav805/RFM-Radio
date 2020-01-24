package com.vlad805.fmradio.enums;

/**
 * vlad805 (c) 2019
 */
public enum Direction {
	UP(1),
	DOWN(-1);

	private int value;

	Direction(int val) {
		value = val;
	}

	public int getValue() {
		return value;
	}
}
