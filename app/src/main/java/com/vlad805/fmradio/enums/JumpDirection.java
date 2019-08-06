package com.vlad805.fmradio.enums;

/**
 * vlad805 (c) 2019
 */
public enum JumpDirection {

	UP("1"),
	DOWN("-1");

	private String value;

	JumpDirection(String val) {
		value = val;
	}

	public String getValue() {
		return value;
	}

}
