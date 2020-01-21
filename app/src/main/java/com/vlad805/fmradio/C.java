package com.vlad805.fmradio;

/**
 * vlad805 (c) 2019
 */
final public class C {

	public static final String DEFAULT_PREFERENCES = "default_cfg";

	public static final class Event {
		private static final String BASE = BuildConfig.APPLICATION_ID + ".action.EVT_";

		public static final String READY = BASE + "READY";
		public static final String ENABLED = BASE + "ENABLED";
		public static final String DISABLED = BASE + "DISABLED";
		public static final String FREQUENCY_SET = BASE + "FREQUENCY_SET";
		public static final String UPDATE_RSSI = BASE + "UPDATE_RSSI";
		public static final String UPDATE_PS = BASE + "UPDATE_PS";
		public static final String UPDATE_RT = BASE + "UPDATE_RT";
		public static final String UPDATE_STEREO = BASE + "UPDATE_STEREO";
		public static final String SEARCH_DONE = BASE + "SEARCH_DONE";

		public static final String RECORD_STARTED = BASE + "RECORD_STARTED";
		public static final String RECORD_TIME_UPDATE = BASE + "RECORD_TIME_UPDATED";
		public static final String RECORD_ENDED = BASE + "RECORD_ENDED";
	}

	public static final class Command {
		public static final String INIT = "init";
		public static final String ENABLE = "enable";
		public static final String DISABLE = "disable";
		public static final String HW_SEEK = "hw_seek";
		public static final String SET_FREQUENCY = "set_frequency";
		public static final String JUMP = "jump";
		public static final String SEARCH = "search";
		public static final String KILL = "kill";
	}

	public static final class Key {
		public static final String FREQUENCY = "frequency";
		public static final String RSSI = "rssi";
		public static final String PS = "ps";
		public static final String RT = "rt";
		public static final String STEREO_MODE = "stereo_mode";
		public static final String MUTE = "mute";

		public static final String SEEK_HW_DIRECTION = "seek_hw_direction";
		public static final String JUMP_DIRECTION = "jump_direction";

		public static final String STATION_LIST = "station_list";
		public static final String AUDIO_SERVICE = "audio_service";
	}

	public static final class PrefKey {
		public static final String LAST_FREQUENCY = "frequency_last";
		public static final String RDS_ENABLE = "rds_enable";
		public static final String AUTOPLAY = "autoplay";
	}

	public static final class PrefDefaultValue {
		public static final int LAST_FREQUENCY = 87500;
		public static final boolean RDS_ENABLE = true;
		public static final boolean AUTOPLAY = false;

		public static final int AUDIO_SERVICE = 0;
	}

	private C() {}

	/** @deprecated */ public static final String FM_GET_STATUS = "fm_get_status";
	/** @deprecated */ public static final String FM_SET_STEREO = "fm_setstereo";
	/** @deprecated */ public static final String FM_SET_MUTE = "fm_setmute";

}
