package com.vlad805.fmradio.enums;

/**
 * vlad805 (c) 2019
 */
public enum SeekDirection {

	UP("1"),
	DOWN("0");

	private String value;

	SeekDirection(String val) {
		value = val;
	}

	public String getValue() {
		return value;
	}
}
