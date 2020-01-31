package com.vlad805.fmradio.service;

import android.app.Notification;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import com.vlad805.fmradio.C;
import com.vlad805.fmradio.Storage;
import com.vlad805.fmradio.controller.RadioController;
import com.vlad805.fmradio.service.audio.FMAudioService;
import com.vlad805.fmradio.service.audio.LightAudioService;
import com.vlad805.fmradio.service.audio.Spirit3AudioService;
import com.vlad805.fmradio.service.fm.FMController;
import com.vlad805.fmradio.service.fm.FMEventCallback;
import com.vlad805.fmradio.service.fm.IFMEventListener;
import com.vlad805.fmradio.service.fm.IFMEventPoller;
import com.vlad805.fmradio.service.fm.impl.Empty;
import com.vlad805.fmradio.service.fm.impl.QualCommLegacy;
import com.vlad805.fmradio.service.fm.impl.Spirit3Impl;

import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;

public class FMService extends Service implements FMEventCallback {
	private static final String TAG = "FMS";

	private RadioNotificationManager mRadioNotification;
	private RadioController mRadioController;
	private FMController mFmController;
	private FMAudioService mAudioService;
	private PlayerReceiver mStatusReceiver;
	private SharedPreferences mStorage;
	private Timer mTimer;

	@Override
	public void onCreate() {
		super.onCreate();

		mRadioController = new RadioController(this);

		mStatusReceiver = new PlayerReceiver();
		mStorage = Storage.getInstance(this);

		mAudioService = getPreferredAudioService();
		mFmController = getPreferredTunerDriver();
		mRadioNotification = getPreferredNotificationType();

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

		Log.d(TAG, "onStartCommand: " + intent.getAction());

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
				stopService(new Intent(this, FMService.class));
				sendBroadcast(new Intent(C.Event.KILLED));
				break;
			}
		}

		return START_STICKY;
	}

	/**
	 * Stop FM
	 */
	private void kill() {
		if (mFmController instanceof IFMEventPoller) {
			if (mTimer != null) {
				mTimer.cancel();
			}
		}
		mAudioService.stopAudio();

		mFmController.kill();

		if (mStatusReceiver != null) {
			unregisterReceiver(mStatusReceiver);
			mStatusReceiver = null;
		}

		stopForeground(true);
	}

	@Override
	public void onDestroy() {
		kill();

		super.onDestroy();
	}

	/**
	 * Returns preferred by user audio service (from settings)
	 * @return FMAudioService instance
	 */
	private FMAudioService getPreferredAudioService() {
		final int id = Storage.getPrefInt(this, C.Key.AUDIO_SERVICE, C.PrefDefaultValue.AUDIO_SERVICE);

		switch (id) {
			case FMAudioService.SERVICE_LIGHT: {
				return new LightAudioService(this);
			}

			case FMAudioService.SERVICE_SPIRIT3:
			default: {
				return new Spirit3AudioService(this);
			}
		}
	}

	/**
	 * Returns preferred by user tuner driver (from settings)
	 * @return Tuner driver
	 */
	private FMController getPreferredTunerDriver() {
		final int id = Storage.getPrefInt(this, C.Key.TUNER_DRIVER, C.PrefDefaultValue.TUNER_DRIVER);

		Log.d(TAG, "getPreferredTunerDriver: preferred id = " + id);

		switch (id) {
			case FMController.DRIVER_QUALCOMM: {
				return new QualCommLegacy(new QualCommLegacy.Config(), this);
			}

			case FMController.DRIVER_EMPTY: {
				return new Empty(new Empty.Config(), this);
			}

			case FMController.DRIVER_SPIRIT3:
			default: {
				return new Spirit3Impl(new Spirit3Impl.Config(), this);
			}
		}
	}

	private RadioNotificationManager getPreferredNotificationType() {
		return new RadioNotificationManager(this);
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

	/**
	 * Event listener
	 */
	public class PlayerReceiver extends BroadcastReceiver {

		@Override
		public void onReceive(final Context context, final Intent intent) {
			if (intent == null || intent.getAction() == null) {
				return;
			}

			Log.d(TAG, "onReceive: " + intent.getAction());

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
					int frequency = mStorage.getInt(C.PrefKey.LAST_FREQUENCY, C.PrefDefaultValue.LAST_FREQUENCY);
					mRadioController.setFrequency(frequency);
					updateNotification();
					break;
				}

				case C.Event.DISABLED: {
					mAudioService.stopAudio();
					break;
				}

				case C.Event.FREQUENCY_SET: {
					int frequency = intent.getIntExtra(C.Key.FREQUENCY, -1);
					mStorage.edit().putInt(C.PrefKey.LAST_FREQUENCY, frequency).apply();
					updateNotification();
					break;
				}

				case C.Event.UPDATE_PS: {
					mRadioController.getState().putString(C.Key.PS, intent.getStringExtra(C.Key.PS));
					updateNotification();
					break;
				}

				case C.Event.UPDATE_RT: {
					mRadioController.getState().putString(C.Key.RT, intent.getStringExtra(C.Key.RT));
					updateNotification();
					break;
				}

				case C.Event.UPDATE_RSSI: {
					mRadioController.getState().putInt(C.Key.RSSI, intent.getIntExtra(C.Key.RSSI, 0));
					break;
				}
			}

			mRadioController.onEvent(intent);
		}
	}



	/**
	 * Update notification
	 */
	private void updateNotification() {
		final Notification notification = mRadioNotification.update(mRadioController.getState());
		if (notification == null) {
			return;
		}
		startForeground(RadioNotificationManager.NOTIFICATION_ID, notification);
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
				((IFMEventPoller) mFmController).poll(this::get);
			}
		}

		private void get(final Bundle bundle) {
			if (last == null) {
				last = bundle;
				return;
			}

			sendIntEventIfExistsAndDiff(bundle, C.Key.RSSI, C.Event.UPDATE_RSSI);
			sendIntEventIfExistsAndDiff(bundle, C.Key.FREQUENCY, C.Event.FREQUENCY_SET);
			sendStringEventIfExistsAndDiff(bundle, C.Key.PS, C.Event.UPDATE_PS);

			last = bundle;
		}

		private void sendIntEventIfExistsAndDiff(final Bundle now, final String key, final String action) {
			if (last.containsKey(key) && now.containsKey(key) && last.getInt(key) != now.getInt(key)) {
				sendBroadcast(new Intent(action).putExtra(key, now.getInt(key)));
			}
		}

		private void sendStringEventIfExistsAndDiff(final Bundle now, final String key, final String action) {
			if (
					last.containsKey(key) && now.containsKey(key) &&
					!Objects.equals(last.getString(key), now.get(key))
			) {
				sendBroadcast(new Intent(action).putExtra(key, now.getString(key)));
			}
		}
	}

}
