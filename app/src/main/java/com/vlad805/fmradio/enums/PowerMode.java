package com.vlad805.fmradio.enums;

/**
 * vlad805 (c) 2021
 */
public enum PowerMode {
	NORMAL("normal"),
	LOW("low");

	private final String value;

	PowerMode(String val) {
		value = val;
	}

	public String getValue() {
		return value;
	}
}
