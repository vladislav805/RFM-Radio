package com.vlad805.fmradio.service;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.*;
import android.os.IBinder;
import android.util.Log;
import com.vlad805.fmradio.C;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.activity.MainActivity;
import com.vlad805.fmradio.enums.MuteState;
import com.vlad805.fmradio.fm.Configuration;
import com.vlad805.fmradio.fm.OnResponseReceived;
import com.vlad805.fmradio.fm.QualComm;

import java.util.Locale;

import static com.vlad805.fmradio.Utils.getStorage;
import static com.vlad805.fmradio.Utils.parseInt;

@SuppressWarnings("deprecation")
public class FMService extends Service {

	private FM mFM;

	private FMAudioService mAudioService;

	private NotificationManager mNotificationMgr;

	private OnResponseReceived<Integer> mOnRssiReceived = rssi -> {
		Intent i = new Intent(C.Event.UPDATE_RSSI);
		i.putExtra(C.Key.RSSI, rssi);
		sendBroadcast(i);
	};

	private static Configuration mConfiguration;

	private PlayerReceiver mStatusReceiver;

	public class PlayerReceiver extends BroadcastReceiver {

		@Override
		public void onReceive(Context context, final Intent intent) {
			if (intent == null || intent.getAction() == null) {
				return;
			}

			switch (intent.getAction()) {
				case C.Event.FREQUENCY_SET:
					int frequency = intent.getIntExtra(C.Key.FREQUENCY, -1);
					mConfiguration.setFrequency(frequency);
					getStorage(FMService.this).edit().putInt(C.PrefKey.LAST_FREQUENCY, frequency).apply();
					break;

				case C.Event.UPDATE_PS:
					mConfiguration.setPs(intent.getStringExtra(C.Key.PS));
					break;

				case C.Event.UPDATE_RT:
					mConfiguration.setRt(intent.getStringExtra(C.Key.RT));
					break;
			}

			showNotification();
		}
	}

	@Override
	public void onCreate() {
		super.onCreate();

		mStatusReceiver = new PlayerReceiver();

		mConfiguration = new Configuration();

		IntentFilter filter = new IntentFilter();
		filter.addAction(C.Event.FREQUENCY_SET);
		filter.addAction(C.Event.UPDATE_PS);
		filter.addAction(C.Event.UPDATE_RT);
		filter.addAction(C.Event.UPDATE_RSSI);
		registerReceiver(mStatusReceiver, filter);
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if (intent == null || intent.getAction() == null) {
			return START_STICKY;
		}

		String tmp;

		switch (intent.getAction()) {
			case C.Command.INIT:
				init();
				break;

			case C.Command.ENABLE:
				init();
				mAudioService.startAudio();
				mFM.enable((Void) -> mFM.setFrequency(mConfiguration.getFrequency(), null));
				break;

			case C.Command.DISABLE:
				mFM.disable(null);
				stopSelf();
				break;

			case C.Command.SET_FREQUENCY:
				if (!intent.hasExtra(C.Key.FREQUENCY)) {
					break;
				}
				int frequency = parseInt(intent.getStringExtra(C.Key.FREQUENCY));

				mFM.setFrequency(frequency, null);
				break;

			case C.FM_GET_STATUS:
				mFM.getRssi(mOnRssiReceived);
				break;

			case C.Command.HW_SEEK:
				tmp = intent.getStringExtra(C.Key.SEEK_HW_DIRECTION);

				if (tmp == null) {
					tmp = "-1";
				}

				int direction = parseInt(tmp);
				mFM.hardwareSeek(direction, null);
				break;

			case C.Command.JUMP: {
				tmp = intent.getStringExtra(C.Key.JUMP_DIRECTION);

				if (tmp == null) {
					tmp = "-1";
				}

				mFM.jump(parseInt(tmp), null);
			break; }

			case C.FM_SET_STEREO:
				mFM.sendCommand("setstereo", data -> Log.i("SET_STEREO", data));
				break;

			case C.FM_SET_MUTE:
				mFM.setMute(MuteState.valueOf(intent.getStringExtra(C.Key.MUTE)), null);
				break;

			case C.Command.SEARCH:
				mFM.search(null);
				break;

			case C.Command.KILL:
				kill();
		}

		return START_STICKY;
	}

	private void kill() {
		mAudioService.stopAudio();

		if (mFM != null) {
			mFM.kill(null);
		}

		mNotificationMgr.cancel(NOTIFICATION_ID);
		stopForeground(true);

		if (mStatusReceiver != null) {
			unregisterReceiver(mStatusReceiver);
			mStatusReceiver = null;
		}
	}

	@Override
	public void onDestroy() {
		kill();

		super.onDestroy();
	}

	private void init() {
		if (mAudioService == null) {
			mAudioService = createAudioService();
		}

		if (mNotificationMgr == null) {
			mNotificationMgr = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
		}

		if (mFM == null) {
			mFM = FM.getInstance();
			mFM.setImpl(new QualComm());
			mFM.setup(this, data -> onReadyIntent());
		} else {
			new Thread(this::onReadyIntent);
		}
	}

	private void onReadyIntent() {
		final SharedPreferences sp = getStorage(this);
		final Intent intent = new Intent(C.Event.READY);
		final int last = sp.getInt(C.PrefKey.LAST_FREQUENCY, C.PrefDefaultValue.LAST_FREQUENCY);
		mConfiguration.setFrequency(last);

		intent.putExtra(C.PrefKey.LAST_FREQUENCY, last);
		intent.putExtra(C.PrefKey.AUTOPLAY, sp.getBoolean(C.PrefKey.AUTOPLAY, C.PrefDefaultValue.AUTOPLAY));
		intent.putExtra(C.PrefKey.RDS_ENABLE, sp.getBoolean(C.PrefKey.RDS_ENABLE, C.PrefDefaultValue.RDS_ENABLE));

		//intent.putExtra(C.Key.STATION_LIST, getStationList().toArray(new Station[0]));

		sendBroadcast(intent);
	}

	/*private List<Station> getStationList() {
		return mDatabase.stationDao().getAll();
	}*/

	private FMAudioService createAudioService() {
		final int id = getStorage(this).getInt(C.Key.AUDIO_SERVICE, C.PrefDefaultValue.AUDIO_SERVICE);

		switch (id) {
			case FMAudioService.SERVICE_LIGHT:
				return new LightAudioService(this);

			case FMAudioService.SERVICE_LEGACY:
			default:
				return new LegacyAudioService(this);
		}
	}

	private Notification.Builder mNotification;
	private static final int NOTIFICATION_ID = 1027;

	private Notification.Builder createNotificationBuilder() {
		Intent mainIntent = new Intent(this, MainActivity.class);
		mainIntent.setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);

		PendingIntent pendingMain = PendingIntent.getActivity(this, 0, mainIntent, PendingIntent.FLAG_UPDATE_CURRENT);

		return new Notification.Builder(this)
				.setColor(getResources().getColor(R.color.primary_blue))
				.setAutoCancel(false)
				.setSmallIcon(R.drawable.ic_radio)
				.setOnlyAlertOnce(true)
				.setContentIntent(pendingMain)
				.setPriority(Notification.PRIORITY_HIGH)
				.setOngoing(true)
				.setShowWhen(false);
	}

	private void showNotification() {
		if (mNotification == null) {
			mNotification = createNotificationBuilder();
		}

		mNotification
				.setContentTitle(getString(R.string.app_name))
				.setContentText(mConfiguration.getPs() == null || mConfiguration.getPs().length() == 0 ? "< no rds >" : mConfiguration.getPs())
				.setSubText(String.format(Locale.ENGLISH, "%.1f MHz", mConfiguration.getFrequency() / 1000d));

		Notification ntf = mNotification.build();
		mNotificationMgr.notify(NOTIFICATION_ID, ntf);
	}


	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

}
