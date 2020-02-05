package com.vlad805.fmradio.service;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import com.vlad805.fmradio.C;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.Storage;
import com.vlad805.fmradio.activity.MainActivity;
import com.vlad805.fmradio.controller.FavoriteController;
import com.vlad805.fmradio.controller.RadioController;
import com.vlad805.fmradio.models.FavoriteStation;
import com.vlad805.fmradio.service.audio.FMAudioService;
import com.vlad805.fmradio.service.audio.LightAudioService;
import com.vlad805.fmradio.service.audio.Spirit3AudioService;
import com.vlad805.fmradio.service.fm.*;
import com.vlad805.fmradio.service.fm.impl.Empty;
import com.vlad805.fmradio.service.fm.impl.QualCommLegacy;
import com.vlad805.fmradio.service.fm.impl.Spirit3Impl;

import java.io.File;
import java.util.*;

import static android.app.PendingIntent.FLAG_UPDATE_CURRENT;
import static com.vlad805.fmradio.Utils.getTimeStringBySeconds;

@SuppressWarnings("deprecation")
public class FMService extends Service implements FMEventCallback {
	private static final String TAG = "FMS";
	public static final int NOTIFICATION_ID = 1027;
	public static final int NOTIFICATION_RECORD_ID = 1029;
	private static final String CHANNEL_ID = "default_channel";
	private static final String CHANNEL_RECORD_ID = "record_channel";

	private NotificationCompat.Builder mNBuilder;
	private NotificationManagerCompat mNotificationManager;
	private RadioController mRadioController;
	private FavoriteController mFavoriteController;
	private Map<Integer, String> mFavoriteList;
	private FMController mFmController;
	private FMAudioService mAudioService;
	private PlayerReceiver mStatusReceiver;
	private SharedPreferences mStorage;
	private Timer mTimer;
	private boolean mNeedRecreateNotification = true;
	private boolean mRecordingNow = false;

	@Override
	public void onCreate() {
		super.onCreate();

		mRadioController = new RadioController(this);
		mFavoriteController = new FavoriteController(this);
		mNotificationManager = NotificationManagerCompat.from(this);

		mStatusReceiver = new PlayerReceiver();
		mStorage = Storage.getInstance(this);

		mAudioService = getPreferredAudioService();
		mFmController = getPreferredTunerDriver();

		if (mFmController instanceof IFMEventPoller) {
			mTimer = new Timer("Poll", true);
			mTimer.schedule(new PollTunerHandler(), C.Config.Polling.DELAY, C.Config.Polling.INTERVAL);
		}

		if (mFmController instanceof IFMEventListener) {
			((IFMEventListener) mFmController).setEventListener(this);
		}

		reloadFavorite();

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

			case C.Command.RECORD_START: {
				if (mFmController instanceof IFMRecordable && mAudioService instanceof IAudioRecordable) {
					IFMRecordable drv = (IFMRecordable) mFmController;
					IAudioRecordable audioRecord = (IAudioRecordable) mAudioService;


					drv.newRecord(driver -> {
						try {
							audioRecord.startRecord(driver);
						} catch (RecordError e) {
							Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
						}
					});

				} else {
					Toast.makeText(this, R.string.service_record_unsupported, Toast.LENGTH_LONG).show();
				}
				break;
			}

			case C.Command.RECORD_STOP: {
				if (mAudioService instanceof IAudioRecordable) {
					IAudioRecordable audioRecord = (IAudioRecordable) mAudioService;

					audioRecord.stopRecord();
				} else {
					Toast.makeText(this, R.string.service_record_unsupported, Toast.LENGTH_LONG).show();
				}
				break;
			}

			/*case C.FM_GET_STATUS:
				mFM.getRssi(mOnRssiReceived);
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

				case C.Event.FAVORITE_LIST_CHANGED: {
					reloadFavorite();
					break;
				}

				case C.Event.RECORD_STARTED: {
					mRecordingNow = true;
					mNeedRecreateNotification = true;
					break;
				}

				case C.Event.RECORD_TIME_UPDATE: {
					updateRecordingNotification(
							intent.getIntExtra(C.Key.DURATION, 0),
							intent.getIntExtra(C.Key.SIZE, 0)
					);
					break;
				}

				case C.Event.RECORD_ENDED: {
					mRecordingNow = false;
					mNeedRecreateNotification = true;
					mNotificationManager.cancel(NOTIFICATION_RECORD_ID);
					if (intent.getExtras() != null) {
						showRecorded(intent.getExtras());
					}
					break;
				}
			}

			mRadioController.onEvent(intent);
		}
	}

	/**
	 * Create notification
	 * @return Notification builder
	 */
	private NotificationCompat.Builder createNotificationBuilder() {
		final Intent mainIntent = new Intent(this, MainActivity.class)
				.setAction(Intent.ACTION_MAIN)
				.addCategory(Intent.CATEGORY_LAUNCHER);

		final PendingIntent pendingMain = PendingIntent.getActivity(
				this,
				0,
				mainIntent,
				FLAG_UPDATE_CURRENT
		);

		final PendingIntent pendingStop = PendingIntent.getService(
				this,
				1,
				new Intent(this, FMService.class).setAction(C.Command.DISABLE),
				0
		);

		final PendingIntent pendingRec = PendingIntent.getService(
				this,
				2,
				new Intent(this, FMService.class)
						.setAction(C.Command.RECORD_START),
				0
		);


		final PendingIntent pendingSeekDown = PendingIntent.getService(
				this,
				3,
				new Intent(this, FMService.class)
						.setAction(C.Command.HW_SEEK)
						.putExtra(C.Key.SEEK_HW_DIRECTION, -1),
				0
		);

		final PendingIntent pendingSeekUp = PendingIntent.getService(
				this,
				4,
				new Intent(this, FMService.class)
						.setAction(C.Command.HW_SEEK)
						.putExtra(C.Key.SEEK_HW_DIRECTION, 1),
				0
		);

		//.setMediaSession(mediaSession.getSessionToken()))

		androidx.media.app.NotificationCompat.MediaStyle ms = new androidx.media.app.NotificationCompat.MediaStyle();
		NotificationCompat.Builder n = new NotificationCompat.Builder(this, CHANNEL_ID)
				.setSmallIcon(R.drawable.ic_radio)
				.setContentIntent(pendingMain)
				.setContentTitle(getString(R.string.app_name))
				.setContentText(getString(R.string.progress_starting))
				.setPriority(NotificationCompat.PRIORITY_DEFAULT)
				.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
				.setShowWhen(false)
				.setAutoCancel(false)
				.setOngoing(true)
				.setColor(getResources().getColor(R.color.primary_blue))
				.addAction(R.drawable.ic_go_down, getString(R.string.seek_down), pendingSeekDown) // #0
				.addAction(R.drawable.ic_stop, getString(R.string.toggle_play_pause), pendingStop); // #1

		if (!mRecordingNow) {
			n.addAction(R.drawable.ic_record_off, getString(R.string.menu_record), pendingRec); // #2
			ms.setShowActionsInCompactView(1, 2);
		} else {
			ms.setShowActionsInCompactView(1);
		}

		n.addAction(R.drawable.ic_go_up, getString(R.string.seek_up), pendingSeekUp) // #3 or #2
				.setStyle(ms);

		return n;
	}

	public Notification updateNotification(final Bundle state) {
		if (mNBuilder == null || mNeedRecreateNotification) {
			mNBuilder = createNotificationBuilder();
			mNeedRecreateNotification = false;
		}

		final int frequency = state.getInt(C.Key.FREQUENCY);
		final int rssi = state.getInt(C.Key.RSSI);

		/*
		 * Subtitle
		 */
		mNBuilder.setSubText(getString(R.string.notification_mhz, frequency / 1000d, rssi));

		final boolean isNeedShowRds = Storage.getPrefBoolean(this, C.PrefKey.NOTIFICATION_SHOW_RDS, C.PrefDefaultValue.NOTIFICATION_SHOW_RDS);

		/*
		 * Title
		 */
		String title = getString(R.string.app_name);
		final String stationTitle = mFavoriteList.get(frequency);
		final String rdsPs = state.getString(C.Key.PS);

		if (isNeedShowRds && rdsPs != null && !rdsPs.isEmpty()) {
			title = rdsPs.trim();
		} else if (stationTitle != null) {
			title = stationTitle;
		}

		mNBuilder.setContentTitle(title);

		/*
		 * Text
		 */
		String text = "";
		final String rdsRt = state.getString(C.Key.RT);

		if (isNeedShowRds && rdsRt != null && !rdsRt.isEmpty()) {
			text = rdsRt;
		}

		mNBuilder.setContentText(text);

		return mNBuilder.build();
	}

	/**
	 * Update notification
	 */
	private void updateNotification() {
		final Notification notification = updateNotification(mRadioController.getState());

		startForeground(NOTIFICATION_ID, notification);
	}

	private void updateRecordingNotification(final int duration, final int size) {
		final PendingIntent pendingRecordStop = PendingIntent.getService(
				this,
				5,
				new Intent(this, FMService.class)
						.setAction(C.Command.RECORD_STOP),
				0
		);
		NotificationCompat.Builder n = new NotificationCompat.Builder(this, CHANNEL_RECORD_ID)
				.setSmallIcon(R.drawable.ic_record_on)
				.setContentTitle(getString(R.string.app_name))
				.setContentText(getString(R.string.notification_recording, getTimeStringBySeconds(duration), size / 1024f / 1024f))
				.setPriority(NotificationCompat.PRIORITY_DEFAULT)
				.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
				.setShowWhen(false)
				.setAutoCancel(false)
				.setOngoing(true)
				.setColor(getResources().getColor(R.color.record_active))
				.addAction(R.drawable.ic_record_off, getString(R.string.seek_down), pendingRecordStop)
				.setStyle(new androidx.media.app.NotificationCompat.MediaStyle().setShowActionsInCompactView(0));

		mNotificationManager.notify(NOTIFICATION_RECORD_ID, n.build());
	}

	private void showRecorded(final Bundle extras) {
		final boolean needNotification = Storage.getPrefBoolean(this, C.PrefKey.RECORDING_SHOW_NOTIFY, C.PrefDefaultValue.RECORDING_SHOW_NOTIFY);

		final int size = extras.getInt(C.Key.SIZE);
		final int duration = extras.getInt(C.Key.DURATION);
		final String path = extras.getString(C.Key.PATH);
		final String file = new File(path).getName();

		if (needNotification) {
			final NotificationCompat.Builder n = new NotificationCompat.Builder(this, CHANNEL_RECORD_ID)
					.setSmallIcon(R.drawable.ic_radio)
					.setContentTitle(getString(R.string.app_name))
					.setContentText(getString(R.string.notification_recorded, getTimeStringBySeconds(duration), size / 1024f / 1024f, file))
					.setPriority(NotificationCompat.PRIORITY_LOW)
					.setVisibility(NotificationCompat.VISIBILITY_PUBLIC);

			mNotificationManager.notify(NOTIFICATION_RECORD_ID + size, n.build());
		}

		Toast.makeText(this, getString(
				R.string.toast_record_ended,
				file,
				size / 1024f / 1024f,
				getTimeStringBySeconds(duration)
		), Toast.LENGTH_LONG).show();
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

	private void reloadFavorite() {
		mFavoriteController.reload();
		mFavoriteList = new HashMap<>();
		final List<FavoriteStation> list = mFavoriteController.getStationsInCurrentList();
		for (FavoriteStation station : list) {
			mFavoriteList.put(station.getFrequency(), station.getTitle());
		}
	}

}
