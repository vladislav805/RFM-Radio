package com.vlad805.fmradio;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.*;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.util.Locale;

@SuppressWarnings("deprecation")
public class FMService extends Service {

	private FM mFM;

	private FMAudioService mAudioService;

	private NotificationManager mNotificationMgr;

	private OnResponseReceived<Integer> mOnRssiReceived = rssi -> {
		Intent i = new Intent(C.FETCHED_RSSI);
		i.putExtra(C.KEY_RSSI, rssi);
		sendBroadcast(i);
	};

	static class Station {
		private int frequency;
		private String ps;

		public int getFrequency() {
			return frequency;
		}

		public String getPs() {
			return ps;
		}
	}

	private static Station mStation = new Station();

	public Station getStation() {
		return mStation;
	}

	private PlayerReceiver mStatusReceiver;

	public class PlayerReceiver extends BroadcastReceiver {

		@Override
		public void onReceive(Context context, final Intent intent) {
			if (intent == null || intent.getAction() == null) {
				return;
			}

			switch (intent.getAction()) {
				case C.Event.FREQUENCY_SET: mStation.frequency = intent.getIntExtra(C.KEY_FREQUENCY, -1); break;
				case C.Event.UPDATE_PS: mStation.ps = intent.getStringExtra(C.KEY_PS); break;
			}

			showNotification();
		}
	}

	@Override
	public void onCreate() {
		super.onCreate();

		mStatusReceiver = new PlayerReceiver();

		mStation.frequency = getStorage().getInt(C.KEY_FREQUENCY, 87500);

		IntentFilter filter = new IntentFilter();
		filter.addAction(C.Event.FREQUENCY_SET);
		filter.addAction(C.Event.UPDATE_PS);
		filter.addAction(C.FETCHED_RSSI);
		registerReceiver(mStatusReceiver, filter);
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {

		if (intent == null || intent.getAction() == null) {
			return START_STICKY;
		}

		switch (intent.getAction()) {
			case C.FM_INIT:
				init();
				break;

			case C.FM_ENABLE:
				init();
				mAudioService.startAudio();
				mFM.enable((Void) -> mFM.setFrequency(mStation.frequency, null));
				break;

			case C.FM_DISABLE:
				mFM.disable(null);
				//mTimerRssi.cancel();
				stopSelf();
				break;

			case C.FM_SET_FREQUENCY:
				if (!intent.hasExtra(C.KEY_FREQUENCY)) {
					break;
				}
				int frequency = Integer.valueOf(intent.getStringExtra(C.KEY_FREQUENCY));

				mFM.setFrequency(frequency, null);
				break;

			case C.FM_GET_STATUS:
				mFM.getRssi(mOnRssiReceived);
				break;

			case C.FM_HW_SEEK:
				String str = intent.getStringExtra(C.FM_KEY_SEEK_HW_DIRECTION);

				if (str == null) {
					str = "-1";
				}

				int direction = Integer.valueOf(str);
				mFM.hardwareSeek(direction, kHz -> {
					/*Intent i = new Intent(C.FM_EVENT);
					i.putExtra(C.KEY_EVENT, new FMEvent(FMEvent.EVENT_SEEK_ENDED, kHz));
					sendBroadcast(i);*/
				});
				break;

			case C.FM_SET_STEREO:
				mFM.sendCommand("setstereo", data -> Log.i("SET_STEREO", data));
				break;

			case C.FM_SET_MUTE:
				mFM.setMute(MuteState.valueOf(intent.getStringExtra(C.KEY_MUTE)), null);
				break;

			case C.FM_SEARCH:
				mFM.search(data -> {
					Log.i("FMSEARCH", "done");
					StringBuilder sb = new StringBuilder();
					for (Integer kHz : data) {
						sb.append(kHz).append("\n");
					}
					final String s = sb.toString();
					new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(FMService.this, s, Toast.LENGTH_LONG).show());
				});
				break;

			case C.FM_KILL:
		}

		return START_STICKY;
	}

	@Override
	public void onDestroy() {
		mAudioService.stopAudio();

		if (mFM != null) {
			mFM.kill(null);
		}

		if (mStatusReceiver != null) {
			unregisterReceiver(mStatusReceiver);
			mStatusReceiver = null;
		}

		super.onDestroy();
	}

	private void init() {
		if (mAudioService == null) {
			mAudioService = new FMAudioService(this);
		}

		if (mNotificationMgr == null) {
			mNotificationMgr = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
		}

		if (mFM == null) {
			mFM = FM.getInstance();
			mFM.setImpl(new QualComm());
			mFM.setup(this);
		}
	}

	private final IBinder binder = new LocalBinder();

	public class LocalBinder extends Binder {
		FMService getService() {
			return FMService.this;
		}
	}

	@Override
	public IBinder onBind(Intent intent) {
		return binder;
	}

	private Notification.Builder mNotification;
	private static final int NOTIFICATION_ID = 1027;

	private Notification.Builder createNotificationBuilder() {
		return new Notification.Builder(this)
				.setColor(getResources().getColor(R.color.primary_blue))
				.setAutoCancel(false)
				.setSmallIcon(R.drawable.ic_radio)
				.setOnlyAlertOnce(true)
				//.setContentIntent(pendingMain)
				.setPriority(Notification.PRIORITY_HIGH)
				.setOngoing(true);
	}

	private void showNotification() {
		if (mNotification == null) {
			mNotification = createNotificationBuilder();
		}

		mNotification
				.setContentTitle(getString(R.string.app_name))
				.setContentText(mStation.ps == null || mStation.ps.length() == 0 ? "< no rds >" : mStation.ps)
				.setSubText(String.format(Locale.ENGLISH, "%.1f MHz", mStation.frequency / 1000d))
				.setShowWhen(false);

		Notification ntf = mNotification.build();
		startForeground(NOTIFICATION_ID, ntf);
		mNotificationMgr.notify(NOTIFICATION_ID, ntf);
	}

	public SharedPreferences getStorage() {
		return Storage.getInstance(this);
	}

}
