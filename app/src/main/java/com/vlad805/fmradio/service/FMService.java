package com.vlad805.fmradio.service;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import com.vlad805.fmradio.C;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.Storage;
import com.vlad805.fmradio.Utils;
import com.vlad805.fmradio.activity.MainActivity;
import com.vlad805.fmradio.controller.RadioController;
import com.vlad805.fmradio.fm.FMController;
import com.vlad805.fmradio.fm.FMEventCallback;
import com.vlad805.fmradio.fm.IFMEventListener;
import com.vlad805.fmradio.fm.IFMEventPoller;
import com.vlad805.fmradio.fm.impl.Empty;
import com.vlad805.fmradio.fm.impl.QualCommLegacy;
import com.vlad805.fmradio.fm.impl.Spirit3Impl;

import java.util.Locale;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;

import static com.vlad805.fmradio.Utils.getStorage;

@SuppressWarnings("deprecation")
public class FMService extends Service implements FMEventCallback {
	private static final int NOTIFICATION_ID = 1027;

	private RadioController mRadioController;
	private FMController mFmController;
	private FMAudioService mAudioService;
	private NotificationManager mNotificationMgr;
	private PlayerReceiver mStatusReceiver;
	private Notification.Builder mNotification;
	private Timer mTimer;

	@Override
	public void onCreate() {
		super.onCreate();

		mRadioController = new RadioController(this);
		mNotificationMgr = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
		mStatusReceiver = new PlayerReceiver();

		mAudioService = getPreferredAudioService();
		mFmController = getPreferredTunerDriver();

		if (mFmController instanceof IFMEventPoller) {
			mTimer = new Timer("Poll", true);
			mTimer.schedule(new PollTunerHandler(), C.Config.Polling.DELAY, C.Config.Polling.INTERVAL);
		}

		if (mFmController instanceof IFMEventListener) {
			((IFMEventListener) mFmController).setEventListener(this);
		}

		registerReceiver(mStatusReceiver, RadioController.sFilter);
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if (intent == null || intent.getAction() == null) {
			return START_STICKY;
		}

		switch (intent.getAction()) {
			case C.Command.SETUP: {
				mFmController.prepareBinary();
				break;
			}

			case C.Command.LAUNCH: {
				mFmController.launch();
				break;
			}

			case C.Command.ENABLE: {
				mFmController.enable();
				break;
			}

			case C.Command.DISABLE: {
				mFmController.disable();
				stopSelf();
				break;
			}

			case C.Command.SET_FREQUENCY: {
				if (!intent.hasExtra(C.Key.FREQUENCY)) {
					Log.e("FMS", "Command SET_FREQUENCY: not specified frequency");
					break;
				}

				final int frequency = intent.getIntExtra(C.Key.FREQUENCY, C.PrefDefaultValue.LAST_FREQUENCY);
				Log.d("FMS", "Set frequency to " + frequency + " kHz");
				mFmController.setFrequency(frequency);
				break;
			}

			case C.Command.JUMP: {
				mFmController.jump(intent.getIntExtra(C.Key.JUMP_DIRECTION, 1));
				break;
			}

			case C.Command.HW_SEEK: {
				mFmController.hwSeek(intent.getIntExtra(C.Key.SEEK_HW_DIRECTION, 1));
				break;
			}

			/*case C.FM_GET_STATUS:
				mFM.getRssi(mOnRssiReceived);
				break;

			case C.FM_SET_STEREO:
				mFM.sendCommand("setstereo", data -> Log.i("SET_STEREO", data));
				break;

			case C.FM_SET_MUTE:
				mFM.setMute(MuteState.valueOf(intent.getStringExtra(C.Key.MUTE)), null);
				break;

			case C.Command.SEARCH:
				mFM.search(null);
				break;*/

			case C.Command.KILL: {
				kill();
				sendBroadcast(new Intent(C.Event.KILLED));
				break;
			}
		}

		return START_STICKY;
	}

	private void kill() {
		if (mFmController instanceof IFMEventPoller) {
			if (mTimer != null) {
				mTimer.cancel();
			}
		}
		mAudioService.stopAudio();

		mFmController.kill();

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

	private FMAudioService getPreferredAudioService() {
		final int id = Storage.getPrefInt(this, C.Key.AUDIO_SERVICE, C.PrefDefaultValue.AUDIO_SERVICE);

		switch (id) {
			case FMAudioService.SERVICE_LIGHT:
				return new LightAudioService(this);

			case FMAudioService.SERVICE_SPIRIT3:
			default:
				return new Spirit3AudioService(this);
		}
	}

	/**
	 * Returns preferred tuner driver
	 * @return Tuner driver
	 */
	private FMController getPreferredTunerDriver() {
		final int id = getStorage(this).getInt(C.Key.TUNER_DRIVER, C.PrefDefaultValue.TUNER_DRIVER);

		switch (id) {
			case FMController.DRIVER_QUALCOMM:
				return new QualCommLegacy(new QualCommLegacy.Config(), this);

			case FMController.DRIVER_EMPTY:
				return new Empty(new Empty.Config(), this);

			case FMController.DRIVER_SPIRIT3:
			default:
				return new Spirit3Impl(new Spirit3Impl.Config(), this);
		}
	}

	/**
	 * Calls by FM implementation which extends IFMEventListener
	 * @param event Action
	 * @param bundle Arguments
	 */
	@Override
	public void onEvent(String event, Bundle bundle) {
		sendBroadcast(new Intent(event).putExtras(bundle));
	}

	public class PlayerReceiver extends BroadcastReceiver {

		@Override
		public void onReceive(final Context context, final Intent intent) {
			if (intent == null || intent.getAction() == null) {
				return;
			}

			Log.d("FMService", "Received event " + intent.getAction() + "; " + intent.getExtras());

			switch (intent.getAction()) {
				case C.Event.INSTALLED: {
					mRadioController.launch();
					break;
				}

				case C.Event.LAUNCHED: {
					mRadioController.enable();
					break;
				}

				case C.Event.ENABLED: {
					mAudioService.startAudio();
					int frequency = Utils.getStorage(context).getInt(C.PrefKey.LAST_FREQUENCY, C.PrefDefaultValue.LAST_FREQUENCY);
					mRadioController.setFrequency(frequency);
					break;
				}

				case C.Event.DISABLED: {
					mAudioService.stopAudio();
					break;
				}

				case C.Event.FREQUENCY_SET:
					int frequency = intent.getIntExtra(C.Key.FREQUENCY, -1);
					getStorage(FMService.this).edit().putInt(C.PrefKey.LAST_FREQUENCY, frequency).apply();
					updateNotification();
					break;

				case C.Event.UPDATE_PS:
					//mConfiguration.setPs(intent.getStringExtra(C.Key.PS));
					break;

				case C.Event.UPDATE_RT:
					//mConfiguration.setRt(intent.getStringExtra(C.Key.RT));
					break;
			}

			mRadioController.onEvent(intent);
		}
	}



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

	/**
	 * Update notification
	 */
	private void updateNotification() {
		if (mNotification == null) {
			mNotification = createNotificationBuilder();
		}

		int frequency = mRadioController.getState().getInt(C.Key.FREQUENCY);

		mNotification
				.setContentTitle(getString(R.string.app_name))
				//.setContentText(mConfiguration.getPs() == null || mConfiguration.getPs().length() == 0 ? "< no rds >" : mConfiguration.getPs())
				.setSubText(String.format(Locale.ENGLISH, "%.1f MHz", frequency / 1000d));

		Notification ntf = mNotification.build();
		startForeground(NOTIFICATION_ID, ntf);
		mNotificationMgr.notify(NOTIFICATION_ID, ntf);
	}


	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	/**
	 * Polling rssi, current frequency, ps and rt
	 */
	private class PollTunerHandler extends TimerTask {
		private Bundle last;

		public void run() {
			if (mFmController instanceof IFMEventPoller) {
				Bundle bundle = ((IFMEventPoller) mFmController).poll();

				if (last == null) {
					last = bundle;
					return;
				}

				sendIntEventIfExistsAndDiff(bundle, C.Key.RSSI, C.Event.UPDATE_RSSI);
				sendIntEventIfExistsAndDiff(bundle, C.Key.FREQUENCY, C.Event.FREQUENCY_SET);
				sendStringEventIfExistsAndDiff(bundle, C.Key.PS, C.Event.UPDATE_PS);

				last = bundle;
			}
		}

		private void sendIntEventIfExistsAndDiff(Bundle now, String key, String action) {
			if (last.containsKey(key) && now.containsKey(key) && last.getInt(key) != now.getInt(key)) {
				sendBroadcast(new Intent(action).putExtra(key, now.getInt(key)));
			}
		}

		private void sendStringEventIfExistsAndDiff(Bundle now, String key, String action) {
			if (
					last.containsKey(key) && now.containsKey(key) &&
					!Objects.equals(last.getString(key), now.get(key))
			) {
				Intent i = new Intent(action).putExtra(key, now.getString(key));
				sendBroadcast(i);
			}
		}
	}

}
