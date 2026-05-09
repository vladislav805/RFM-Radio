package com.vlad805.fmradio.enums;

public enum AudioOutputRoute {
	SPEAKER("speaker"),
	WIRED("wired"),
	BLUETOOTH("bluetooth");

	private final String value;

	AudioOutputRoute(final String value) {
		this.value = value;
	}

	public String getValue() {
		return value;
	}

	public static AudioOutputRoute fromValue(final String value) {
		if (value == null) {
			return WIRED;
		}

		for (final AudioOutputRoute route : values()) {
			if (route.value.equals(value)) {
				return route;
			}
		}

		return WIRED;
	}
}
