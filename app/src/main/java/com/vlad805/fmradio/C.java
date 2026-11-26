package com.vlad805.fmradio;

/**
 * Shared recording format and configuration constants.
 *
 * vlad805 (c) 2019
 */
public final class C {
	/** Stable values persisted by the recording format preference. */
	public static final class RecordFormat {
		public static final int WAV = 0;
		/** Existing value retained for compatibility with saved preferences. */
		public static final int MP3_192 = 1;
		public static final int MP3_128 = 2;
		public static final int OPUS_96 = 3;

		private RecordFormat() {}
	}

	private C() {}

	public static final class Config {
		/** Number of seconds retained for recording pre-roll. */
		public static final int RECORDING_PRE_ROLL_SECONDS = 5;
	}
}
