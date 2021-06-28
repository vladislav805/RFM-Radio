package com.vlad805.fmradio;

import com.vlad805.fmradio.service.audio.FMAudioService;
import com.vlad805.fmradio.service.fm.implementation.AbstractFMController;

/**
 * Common constants: names of events and commands, keys of preferences, default values, etc
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
		public static final String ENABLING = BASE + "ENABLING";
		public static final String ENABLED = BASE + "ENABLED";
		public static final String DISABLING = BASE + "DISABLING";
		public static final String DISABLED = BASE + "DISABLED";
		public static final String FREQUENCY_SET = BASE + "FREQUENCY_SET";
		public static final String UPDATE_RSSI = BASE + "UPDATE_RSSI";
		public static final String UPDATE_PS = BASE + "UPDATE_PS";
		public static final String UPDATE_RT = BASE + "UPDATE_RT";
		public static final String UPDATE_STEREO = BASE + "UPDATE_STEREO";
		public static final String HW_SEARCH_DONE = BASE + "HW_SEARCH_DONE";
		public static final String JUMP_COMPLETE = BASE + "JUMP_COMPLETE";
		public static final String HW_SEEK_COMPLETE = BASE + "HW_SEEK_COMPLETE";
		public static final String UPDATE_PTY = BASE + "UPDATE_PTY";

		public static final String RECORD_STARTED = BASE + "RECORD_STARTED";
		public static final String RECORD_TIME_UPDATE = BASE + "RECORD_TIME_UPDATED";
		public static final String RECORD_ENDED = BASE + "RECORD_ENDED";

		public static final String FAVORITE_LIST_CHANGED = BASE + "FAVORITE_LIST_CHANGED";

		public static final String ERROR_INVALID_ANTENNA = BASE + "ERROR_INVALID_ANTENNA";

		public static final String KILLED = BASE + "KILLED";
	}

	public static final class Command {
		public static final String SETUP = "setup";
		public static final String LAUNCH = "launch";
		public static final String ENABLE = "enable";
		public static final String DISABLE = "disable";
		public static final String HW_SEEK = "hw_seek";
		public static final String NOTIFICATION_SEEK = "notification_seek";
		public static final String SET_FREQUENCY = "set_frequency";
		public static final String JUMP = "jump";
		public static final String HW_SEARCH = "hw_search";
		public static final String RECORD_START = "record_start";
		public static final String RECORD_STOP = "record_stop";
		public static final String POWER_MODE = "power_mode";
		public static final String KILL = "kill";
		public static final String UI_STARTED = "ui_started";
    }

	public static final class Key {
		public static final String FREQUENCY = "frequency";
		public static final String RSSI = "rssi";
		public static final String PS = "ps";
		public static final String RT = "rt";
		public static final String STEREO_MODE = "stereo_mode";
		public static final String MUTE = "mute";
		public static final String PTY = "pty";

		public static final String SEEK_HW_DIRECTION = "seek_hw_direction";
		public static final String JUMP_DIRECTION = "jump_direction";

		public static final String POWER_MODE = "power_mode";

		public static final String STATION_LIST = "station_list";
		public static final String MESSAGE = "message";
		public static final String STAGE = "stage_ctl";
		public static final String SIZE = "size";
		public static final String DURATION = "duration";
		public static final String PATH = "filename";
	}

	public static final class PrefKey {
		public static final String LAST_FREQUENCY = "frequency_last";
		public static final String NOTIFICATION_SHOW_RDS = "notification_show_ps";
		public static final String NOTIFICATION_SEEK_BY_FAVORITES = "notification_seek_by_favorites";
		public static final String APP_AUTO_STARTUP = "app_auto_startup";
		public static final String RDS_ENABLE = "rds_enable";

		public static final String TUNER_DRIVER = "tuner_driver";
		public static final String TUNER_REGION = "tuner_region";
		public static final String TUNER_SPACING = "tuner_spacing";

		public static final String TUNER_POWER_MODE = "tuner_power_mode";
		public static final String TUNER_ANTENNA = "tuner_antenna";


		public static final String AUDIO_SERVICE = "audio_service";
		public static final String AUDIO_SOURCE = "audio_source";

		public static final String RECORDING_DIRECTORY = "recording_directory";
		public static final String RECORDING_FILENAME = "recording_filename";
		public static final String RECORDING_SHOW_NOTIFY = "recording_show_notify";
		public static final String RECORDING_FORMAT = "recording_mode";
		public static final String BINARY_VERSION = "bin_version";
		public static final String TUNER_STEREO = "tuner_stereo";
		// public static final String RECORDING_SAVE_PAST = "recording_save_past";
    }

	public static final class PrefDefaultValue {
		public static final int LAST_FREQUENCY = 87500;
		public static final boolean NOTIFICATION_SHOW_RDS = true;
		public static final boolean NOTIFICATION_SEEK_BY_FAVORITES = false;
		public static final boolean APP_AUTO_STARTUP = false;
		public static final boolean RDS_ENABLE = true;

		public static final int TUNER_DRIVER = AbstractFMController.DRIVER_QUALCOMM;
		public static final int TUNER_REGION = 1;
		public static final int TUNER_SPACING = 2;
		public static final int TUNER_ANTENNA = 0;
		public static final boolean TUNER_STEREO = true;
		public static final boolean TUNER_POWER_MODE = false;

		public static final int AUDIO_SERVICE = FMAudioService.SERVICE_LIGHT;
		public static final int AUDIO_SOURCE = 0;

		public static final boolean RECORDING_SHOW_NOTIFY = true;
		public static final int RECORDING_FORMAT = 0;
		public static final boolean RECORDING_SAVE_PAST = false;
	}

	private C() {}

	public static final class Config {
		public static final class Polling {
			public static final int DELAY = 3000;
			public static final int INTERVAL = 1000;
		}
	}

	public static final class FMStage {
		public static final int IDLE = 0;
		public static final int LAUNCHING = 1;
		public static final int LAUNCHED = 2;
		public static final int ENABLING = 3;
		public static final int ENABLED = 4;
	}
}
