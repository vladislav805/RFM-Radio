package com.vlad805.fmradio;

/**
 * Common constants: names of events and commands, intent extras, recording formats, etc.
 *
 * vlad805 (c) 2019
 */
final public class C {
	public static final class Event {
        private static final String BASE = BuildConfig.APPLICATION_ID + ".action.EVT_";

		public static final String ERROR_OCCURRED = BASE + "ERROR_OCCURRED";
		public static final String PREPARING = BASE + "PREPARING";
		public static final String INSTALLING = BASE + "INSTALLING";
		public static final String INSTALLED = BASE + "INSTALLED";
		public static final String LAUNCHING = BASE + "LAUNCHING";
		public static final String LAUNCHED = BASE + "LAUNCHED";
		public static final String LAUNCH_FAILED = BASE + "LAUNCHED_FAILED";
		public static final String ROOT_ACCESS_DENIED = BASE + "ROOT_ACCESS_DENIED";
		public static final String ENABLING = BASE + "ENABLING";
		public static final String ENABLED = BASE + "ENABLED";
		public static final String DISABLING = BASE + "DISABLING";
		public static final String DISABLED = BASE + "DISABLED";
		public static final String FREQUENCY_SET = BASE + "FREQUENCY_SET";
		public static final String UPDATE_PS = BASE + "UPDATE_PS";
		public static final String UPDATE_PTY = BASE + "UPDATE_PTY";
		public static final String UPDATE_PI = BASE + "UPDATE_PI";
		public static final String UPDATE_RT = BASE + "UPDATE_RT";
		public static final String UPDATE_AF = BASE + "UPDATE_AF";
		public static final String UPDATE_STEREO = BASE + "UPDATE_STEREO";
		public static final String STATE_CHANGED = BASE + "STATE_CHANGED";
		public static final String HW_SEARCH_PROGRESS = BASE + "HW_SEARCH_PROGRESS";
		public static final String HW_SEARCH_DONE = BASE + "HW_SEARCH_DONE";
		public static final String HW_SEARCH_FAILED = BASE + "HW_SEARCH_FAILED";
		public static final String JUMP_COMPLETE = BASE + "JUMP_COMPLETE";
		public static final String HW_SEEK_COMPLETE = BASE + "HW_SEEK_COMPLETE";

		public static final String RECORD_STARTED = BASE + "RECORD_STARTED";
		public static final String RECORD_TIME_UPDATE = BASE + "RECORD_TIME_UPDATED";
		public static final String RECORD_ENDED = BASE + "RECORD_ENDED";
		public static final String RECORD_FAILED = BASE + "RECORD_FAILED";

		public static final String CHANGE_SPEAKER_MODE = BASE + "CHANGE_SPEAKER_MODE";

		public static final String CURRENT_STATE = BASE + "CURRENT_STATE";

		public static final String ERROR_INVALID_ANTENNA = BASE + "ERROR_INVALID_ANTENNA";

		public static final String KILLED = BASE + "KILLED";
	}

	public static final class Command {
		public static final String INSTALL = "install";
		public static final String LAUNCH = "launch";
		public static final String ENABLE = "enable";
		public static final String DISABLE = "disable";
		public static final String HW_SEEK = "hw_seek";
		public static final String NOTIFICATION_SEEK = "notification_seek";
		public static final String SET_FREQUENCY = "set_frequency";
		public static final String JUMP = "jump";
		public static final String HW_SEARCH = "hw_search";
		public static final String HW_SEARCH_CANCEL = "hw_search_cancel";
		public static final String RECORD_START = "record_start";
		public static final String RECORD_STOP = "record_stop";
		public static final String POWER_MODE = "power_mode";
		public static final String KILL = "kill";
		public static final String REQUEST_CURRENT_STATE = "ui_started";
		public static final String SPEAKER_STATE = "speaker_state";
    }

	public static final class Key {
		public static final String FREQUENCY = "frequency";
		public static final String PS = "ps";
		public static final String RT = "rt";
		public static final String PTY = "pty";
		public static final String PI = "pi";
		public static final String COUNTRY = "country";
		public static final String RMSSI = "rmssi";
		public static final String SINR = "sinr";
		public static final String RT_PLUS_TITLE = "rt_plus_title";
		public static final String RT_PLUS_ARTIST = "rt_plus_artist";
		public static final String RT_PLUS_ALBUM = "rt_plus_album";

		public static final String STEREO_MODE = "stereo_mode";
		public static final String MUTE = "mute";
		public static final String SEEK_HW_DIRECTION = "seek_hw_direction";
		public static final String JUMP_DIRECTION = "jump_direction";

		public static final String POWER_MODE = "power_mode";

		public static final String STATION_LIST = "station_list";
		public static final String COUNT = "count";
		public static final String SIZE = "size";
		public static final String DURATION = "duration";
		public static final String PATH = "filename";
		public static final String DISPLAY_NAME = "display_name";
		public static final String RECORDING_URI = "recording_uri";
		public static final String RECORDING_FILE_PATH = "recording_file_path";
        public static final String SUBTITLES_REQUESTED = "subtitles_requested";
        public static final String SUBTITLE_URI = "subtitle_uri";
        public static final String SUBTITLE_FILE_PATH = "subtitle_file_path";
		public static final String FREQUENCIES = "frequencies";
		public static final String STATE = "state";
		public static final String IS_SPEAKER = "is_speaker";
    }

	/** Stable values persisted by the recording format preference. */
	public static final class RecordFormat {
		public static final int WAV = 0;
		/** Existing value retained for compatibility with saved preferences. */
		public static final int MP3_192 = 1;
		public static final int MP3_128 = 2;
		public static final int AAC_128 = 3;

		private RecordFormat() {}
	}

	private C() {}

	public static final class Config {
		/** Number of seconds retained for recording pre-roll. */
		public static final int RECORDING_PRE_ROLL_SECONDS = 5;
	}
}
