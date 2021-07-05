package com.vlad805.fmradio.service;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
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
import com.vlad805.fmradio.controller.RadioState;
import com.vlad805.fmradio.controller.RadioStateUpdater;
import com.vlad805.fmradio.helper.Audio;
import com.vlad805.fmradio.models.FavoriteStation;
import com.vlad805.fmradio.service.audio.AudioService;
import com.vlad805.fmradio.service.audio.LightAudioService;
import com.vlad805.fmradio.service.audio.Spirit3AudioService;
import com.vlad805.fmradio.service.fm.FMEventCallback;
import com.vlad805.fmradio.service.fm.IFMEventListener;
import com.vlad805.fmradio.service.fm.IFMEventPoller;
import com.vlad805.fmradio.service.fm.RecordError;
import com.vlad805.fmradio.service.fm.implementation.AbstractFMController;
import com.vlad805.fmradio.service.fm.implementation.Empty;
import com.vlad805.fmradio.service.fm.implementation.QualcommLegacy;
import com.vlad805.fmradio.service.fm.implementation.Spirit3Impl;
import com.vlad805.fmradio.service.recording.IAudioRecordable;
import com.vlad805.fmradio.service.recording.RecordLameService;
import com.vlad805.fmradio.service.recording.RecordRawService;
import com.vlad805.fmradio.service.recording.RecordService;
import net.grandcentrix.tray.AppPreferences;
import net.grandcentrix.tray.core.OnTrayPreferenceChangeListener;
import net.grandcentrix.tray.core.TrayItem;

import java.io.File;
import java.util.*;

import static android.app.PendingIntent.FLAG_UPDATE_CURRENT;
import static com.vlad805.fmradio.Utils.getTimeStringBySeconds;

@SuppressWarnings({"deprecation", "unused"})
public class FMService extends Service implements FMEventCallback, OnTrayPreferenceChangeListener {
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

    private AbstractFMController mTunerDriver;
    private AudioService mAudioService;

    private BroadcastReceiver mEventReaction;
    private BroadcastReceiver mTunerStateUpdater;

    private AppPreferences mStorage;
    private Timer mTimer;
    private boolean mNeedRecreateNotification = true;
    private boolean mRecordingNow = false;

    private RadioState mState;

    @Override
    public void onCreate() {
        super.onCreate();

        // Main state
        mState = new RadioState();

        // Controllers&managers
        mRadioController = new RadioController(this);
        mFavoriteController = new FavoriteController(this);
        mNotificationManager = NotificationManagerCompat.from(this);

        // Preferences
        mStorage = Storage.getInstance(this);
        mStorage.registerOnTrayPreferenceChangeListener(this);

        // Services
        mAudioService = getPreferredAudioService();
        mTunerDriver = getPreferredTunerDriver();

        // Broadcast receivers
        mEventReaction = new EventReaction();
        mTunerStateUpdater = new RadioStateUpdater(mState);

        if (mTunerDriver instanceof IFMEventListener) {
            ((IFMEventListener) mTunerDriver).setEventListener(this);
        }

        reloadFavorite();

        registerReceiver(mTunerStateUpdater, RadioStateUpdater.sFilter);
        registerReceiver(mEventReaction, RadioStateUpdater.sFilter);
    }

    /**
     * Handling commands sent by the RadioController
     * Upon completion of the command, a specific event is triggered
     * @param intent Intent with command and arguments (in extras)
     */
    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        if (intent == null || intent.getAction() == null) {
            return START_STICKY;
        }

        switch (intent.getAction()) {
            // Installing the native part to system, if required by the selected tuner.
            // When completed, will fire event INSTALLED.
            case C.Command.INSTALL: {
                mTunerDriver.prepareBinary();
                break;
            }

            // Native launch command.
            // When completed, will fire event LAUNCHED.
            case C.Command.LAUNCH: {
                mTunerDriver.launch();
                break;
            }

            // Tuner start and unmute command.
            // When completed, will fire event ENABLED.
            case C.Command.ENABLE: {
                mTunerDriver.enable();
                if (mTunerDriver instanceof IFMEventPoller) {
                    mTimer = new Timer("Poll", true);
                    mTimer.schedule(new PollTunerHandler(), C.Config.Polling.DELAY, C.Config.Polling.INTERVAL);
                }
                break;
            }

            // Tuner stop and mute command.
            // When complete, will fire event DISABLED.
            case C.Command.DISABLE: {
                mTunerDriver.disable();
                stopSelf();
                break;
            }

            // Frequency change command.
            // The required frequency is passed in extras.
            // When complete, will fire event FREQUENCY_SET.
            case C.Command.SET_FREQUENCY: {
                if (!intent.hasExtra(C.Key.FREQUENCY)) {
                    Log.e("FMS", "Command SET_FREQUENCY: not specified frequency");
                    break;
                }

                if (mTunerDriver == null) {
                    Toast.makeText(this, "Error", Toast.LENGTH_SHORT).show();
                    break;
                }

                final int frequency = intent.getIntExtra(C.Key.FREQUENCY, C.PrefDefaultValue.LAST_FREQUENCY);
                mTunerDriver.setFrequency(frequency);
                break;
            }

            // The command to change the frequency to the next or previous.
            // The direction of movement is passed in additional parameters.
            // When complete, will fire event FREQUENCY_SET.
            case C.Command.JUMP: {
                mTunerDriver.jump(intent.getIntExtra(C.Key.JUMP_DIRECTION, 1));
                break;
            }

            // Search command for next or previous station.
            // The direction of search is passed in additional parameters.
            // When complete, will fire event FREQUENCY_SET.
            case C.Command.HW_SEEK: {
                mTunerDriver.hwSeek(intent.getIntExtra(C.Key.SEEK_HW_DIRECTION, 1));
                break;
            }

            // The command to change the power mode.
            // When complete, the event does not fire.
            case C.Command.POWER_MODE: {
                mTunerDriver.setPowerMode(intent.getStringExtra(C.Key.POWER_MODE));
                break;
            }

            // The command to start recording the radio broadcast to a file.
            // Event does not fire.
            case C.Command.RECORD_START: {
                if (mAudioService instanceof IAudioRecordable) {
                    try {
                        ((IAudioRecordable) mAudioService).startRecord(getPreferredRecorder());
                    } catch (RecordError e) {
                        Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                } else {
                    Toast.makeText(this, R.string.service_record_unsupported, Toast.LENGTH_LONG).show();
                }
                break;
            }

            // The command to stop recording the radio broadcast to a file.
            case C.Command.RECORD_STOP: {
                if (mAudioService instanceof IAudioRecordable) {
                    ((IAudioRecordable) mAudioService).stopRecord();
                } else {
                    Toast.makeText(this, R.string.service_record_unsupported, Toast.LENGTH_LONG).show();
                }
                break;
            }

            // Station switch command from notification.
            // The principle of operation differs in the "Navigate over favorites" setting.
            // If it is on, then switching occurs according to the principle of finding
            // the current one and moving forward/backward.
            // If disabled, works as HW_SEEK.
            case C.Command.NOTIFICATION_SEEK: {
                final int direction = intent.getIntExtra(C.Key.SEEK_HW_DIRECTION, 1);
                final boolean byFavorite = Storage.getPrefBoolean(this, C.PrefKey.NOTIFICATION_SEEK_BY_FAVORITES, C.PrefDefaultValue.NOTIFICATION_SEEK_BY_FAVORITES);

                if (!byFavorite) {
                    startService(intent.setAction(C.Command.HW_SEEK));
                    return START_STICKY;
                }

                navigateThroughFavorite(direction);
                break;
            }

            case C.Command.HW_SEARCH: {
                mTunerDriver.hwSearch();
                break;
            }

            case C.Command.KILL: {
                if (mState.isRecording() && mAudioService instanceof IAudioRecordable) {
                    ((IAudioRecordable) mAudioService).stopRecord();
                }

                stopService(new Intent(this, FMService.class));
                sendBroadcast(new Intent(C.Event.KILLED));
                break;
            }

            case C.Command.REQUEST_CURRENT_STATE: {
                sendBroadcast(new Intent(C.Event.CURRENT_STATE).putExtra(C.Key.STATE, mState));
                break;
            }

            case C.Command.SPEAKER_STATE: {
                // Now state
                final boolean isSpeaker = Audio.isForceSpeakerNow();
                // Change state
                Audio.toggleThroughSpeaker(!isSpeaker);
                // Inform
                sendBroadcast(new Intent(C.Event.CHANGE_SPEAKER_MODE).putExtra(C.Key.IS_SPEAKER, !isSpeaker));
                break;
            }
        }

        return START_STICKY;
    }

    /**
     * Stop FM
     * Kill the tuner:
     *   - stop the periodic polling, if it was started;
     *   - the audio recording service;
     *   - the radio controller;
     *   - unregister the event receivers;
     *   - stop the background operation of the service
     */
    private void kill() {
        if (mTunerDriver instanceof IFMEventPoller) {
            if (mTimer != null) {
                mTimer.cancel();
            }
        }
        mAudioService.stopAudio();

        mTunerDriver.kill();

        if (mEventReaction != null) {
            unregisterReceiver(mEventReaction);
            mEventReaction = null;
        }

        if (mTunerStateUpdater != null) {
            unregisterReceiver(mTunerStateUpdater);
            mTunerStateUpdater = null;
        }

        mStorage.unregisterOnTrayPreferenceChangeListener(this);

        stopForeground(true);
    }

    /**
     * When the service is destroyed, we kill everything: notifications, recorder
     * and controller
     */
    @Override
    public void onDestroy() {
        kill();

        super.onDestroy();
    }

    /**
     * Returns preferred by user audio service (from settings)
     * @return FMAudioService instance
     */
    private AudioService getPreferredAudioService() {
        final int id = Storage.getPrefInt(this, C.PrefKey.AUDIO_SERVICE, C.PrefDefaultValue.AUDIO_SERVICE);

        switch (id) {
            case AudioService.SERVICE_LIGHT: {
                return new LightAudioService(this);
            }

            case AudioService.SERVICE_SPIRIT3:
            default: {
                return new Spirit3AudioService(this);
            }
        }
    }

    /**
     * Returns preferred by user tuner driver
     * @return Tuner driver
     */
    private AbstractFMController getPreferredTunerDriver() {
        final int id = Storage.getPrefInt(this, C.PrefKey.TUNER_DRIVER, C.PrefDefaultValue.TUNER_DRIVER);

        switch (id) {
            case AbstractFMController.DRIVER_SPIRIT3: {
                return new Spirit3Impl(this);
            }

            case AbstractFMController.DRIVER_EMPTY: {
                return new Empty(this);
            }

            case AbstractFMController.DRIVER_QUALCOMM:
            default: {
                return new QualcommLegacy( this);
            }
        }
    }

    /**
     * Returns preferred by user audio recorder (WAV / MP3)
     * @return Recorder
     */
    private RecordService getPreferredRecorder() {
        final int mode = Storage.getPrefInt(this, C.PrefKey.RECORDING_FORMAT, C.PrefDefaultValue.RECORDING_FORMAT);
        final int kHz = mStorage.getInt(C.PrefKey.LAST_FREQUENCY, 0);

        switch (mode) {
            case 0: {
                return new RecordRawService(this, kHz);
            }

            case 1: {
                return new RecordLameService(this, kHz);
            }
        }

        return null;
    }

    /**
     * Calls by FM implementation which extends IFMEventListener
     * @param event Action
     * @param bundle Arguments
     */
    @Override
    public void onEvent(final String event, final Bundle bundle) {
        sendBroadcast(new Intent(event).putExtras(bundle));
    }

    private void navigateThroughFavorite(final int direction) {
        final List<FavoriteStation> stations = mFavoriteController.getStationsInCurrentList();
        final int currentFrequency = mStorage.getInt(C.PrefKey.LAST_FREQUENCY, C.PrefDefaultValue.LAST_FREQUENCY);
        int currentPosition = -1;

        for (int i = 0; i < stations.size(); i++) {
            FavoriteStation station = stations.get(i);
            if (station.getFrequency() == currentFrequency) {
                currentPosition = i;
                break;
            }
        }

        if (currentPosition < 0) {
            // If 0 - nowhere to iterate
            // If 1 - will always be the same
            if (stations.size() < 2) {
                return;
            }
            currentPosition = direction > 0 ? -1 : stations.size();
        }

        currentPosition += direction > 0 ? 1 : -1;

        if (currentPosition < 0 || currentPosition >= stations.size()) {
            currentPosition = 0;
        }

        mTunerDriver.setFrequency(stations.get(currentPosition).getFrequency());
    }

    @Override
    public void onTrayPreferenceChanged(final Collection<TrayItem> changed) {
        final String[] keys = new String[changed.size()];

        int i = 0;
        for (final TrayItem item : changed) {
            keys[i++] = item.key();
        }

        this.mTunerDriver.setupTunerByPreferences(keys);
    }

    /**
     * Event listener
     */
    public class EventReaction extends BroadcastReceiver {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            if (intent == null || intent.getAction() == null) {
                return;
            }

            final String action = intent.getAction();

            switch (action) {
                case C.Event.INSTALLED: {
                    mRadioController.launch();
                    break;
                }

                case C.Event.LAUNCHED: {
                    mRadioController.enable();
                    break;
                }

                case C.Event.LAUNCH_FAILED: {
                    kill();
                    break;
                }

                case C.Event.ENABLED: {
                    mAudioService.startAudio();
                    final int frequency = mStorage.getInt(C.PrefKey.LAST_FREQUENCY, C.PrefDefaultValue.LAST_FREQUENCY);
                    mRadioController.setFrequency(frequency);
                    updateNotification();
                    break;
                }

                case C.Event.DISABLED: {
                    mAudioService.stopAudio();
                    break;
                }

                case C.Event.FREQUENCY_SET: {
                    final int frequency = intent.getIntExtra(C.Key.FREQUENCY, -1);
                    mStorage.put(C.PrefKey.LAST_FREQUENCY, frequency);
                    updateNotification();
                    break;
                }

                case C.Event.UPDATE_PS:
                case C.Event.UPDATE_RT: {
                    updateNotification();
                    break;
                }

                case C.Event.UPDATE_PTY:
                case C.Event.UPDATE_PI: {
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
                        .setAction(C.Command.NOTIFICATION_SEEK)
                        .putExtra(C.Key.SEEK_HW_DIRECTION, -1),
                0
        );

        final PendingIntent pendingSeekUp = PendingIntent.getService(
                this,
                4,
                new Intent(this, FMService.class)
                        .setAction(C.Command.NOTIFICATION_SEEK)
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

    public Notification updateNotification(final RadioState state) {
        if (mNBuilder == null || mNeedRecreateNotification) {
            mNBuilder = createNotificationBuilder();
            mNeedRecreateNotification = false;
        }

        final int frequency = state.getFrequency();
        final int rssi = state.getRssi();

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
        final String rdsPs = state.getPs();

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
        final String rdsRt = state.getRt();

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
        final Notification notification = updateNotification(mState);

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
        final File file = new File(path);
        final String filename = file.getName();

        if (needNotification) {
            final NotificationCompat.Builder n = new NotificationCompat.Builder(this, CHANNEL_RECORD_ID)
                    .setSmallIcon(R.drawable.ic_radio)
                    .setContentTitle(getString(R.string.app_name))
                    .setContentText(getString(R.string.notification_recorded, getTimeStringBySeconds(duration), size / 1024f / 1024f, filename))
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);

            mNotificationManager.notify(NOTIFICATION_RECORD_ID + size, n.build());
        }

        Toast.makeText(this, getString(
                R.string.toast_record_ended,
                filename,
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
            if (mTunerDriver instanceof IFMEventPoller) {
                ((IFMEventPoller) mTunerDriver).poll(this::get);
            }
        }

        private void get(final Bundle bundle) {
            if (last == null) {
                last = bundle;
                return;
            }

            sendIntEventIfExistsAndDiff(bundle, C.Key.RSSI, C.Event.UPDATE_RSSI);
            sendIntEventIfExistsAndDiff(bundle, C.Key.FREQUENCY, C.Event.FREQUENCY_SET);
            sendIntEventIfExistsAndDiff(bundle, C.Key.PTY, C.Event.UPDATE_PTY);
            sendStringEventIfExistsAndDiff(bundle, C.Key.PS, C.Event.UPDATE_PS);
            sendStringEventIfExistsAndDiff(bundle, C.Key.RT, C.Event.UPDATE_RT); // was skipped, why?

            last = bundle;
        }

        private void sendIntEventIfExistsAndDiff(final Bundle now, final String key, final String action) {
            if (last.containsKey(key) && now.containsKey(key) && last.getInt(key) != now.getInt(key)) {
                sendBroadcast(new Intent(action).putExtra(key, now.getInt(key)));
            }
        }

        private void sendStringEventIfExistsAndDiff(final Bundle now, final String key, final String action) {
            if (last.containsKey(key) && now.containsKey(key) && !Objects.equals(last.getString(key), now.get(key))) {
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
