package com.vlad805.fmradio;

/**
 * vlad805 (c) 2019
 */
final public class C {

	public static final String DEFAULT_PREFERENCES = "default_cfg";

	public class Event {
		private static final String BASE = "com.vlad805.fmradio.action.EVT_";

		public static final String ENABLED = BASE + "ENABLED";
		public static final String DISABLED = BASE + "DISABLED";
		public static final String FREQUENCY_SET = BASE + "FREQUENCY_SET";
		public static final String UPDATE_RSSI = BASE + "UPDATE_RSSI";
		public static final String UPDATE_PS = BASE + "UPDATE_PS";
		public static final String UPDATE_RT = BASE + "UPDATE_RT";
		public static final String SEARCH_DONE = BASE + "SEARCH_DONE";
	}

	public static final String FETCHED_RSSI = Event.BASE + "FETCHED_RSSI";

	public static final String FM_HW_SEEK = "fm_hw_seek";
	public static final String FM_KEY_SEEK_HW_DIRECTION = "fm_seek_hw_direction";

	private C() {}

	public static final String FM_INIT = "fm_init";
	public static final String FM_ENABLE = "fm_enable";
	public static final String FM_DISABLE = "fm_disable";
	public static final String FM_SET_FREQUENCY = "fm_set_frequency";
	public static final String FM_GET_STATUS = "fm_get_status";
	public static final String FM_SET_STEREO = "fm_setstereo";
	public static final String FM_SET_MUTE = "fm_setmute";
	public static final String FM_SEARCH = "fm_search";
	public static final String FM_KILL = "fm_kill";

	public static final String KEY_FREQUENCY = "fm_frequency";
	public static final String KEY_PS = "fm_ps";
	public static final String KEY_RT = "fm_rt";
	public static final String KEY_RSSI = "fm_rssi";
	public static final String KEY_STATUS = "intent_fm_status";
	public static final String KEY_MUTE = "fm_mute";
	public static final String KEY_EVENT = "intent_fm_event";
	public static final String KEY_STATION_LIST = "fm_station_list";

}
