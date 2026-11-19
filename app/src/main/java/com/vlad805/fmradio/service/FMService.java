package com.vlad805.fmradio.service;

import android.app.Notification;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;
import com.vlad805.fmradio.C;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.Storage;
import com.vlad805.fmradio.Utils;
import com.vlad805.fmradio.controller.FavoriteController;
import com.vlad805.fmradio.controller.RadioController;
import com.vlad805.fmradio.controller.RadioState;
import com.vlad805.fmradio.controller.RadioStateUpdater;
import com.vlad805.fmradio.enums.TunerDriver;
import com.vlad805.fmradio.helper.Audio;
import com.vlad805.fmradio.helper.TunerDriverDetector;
import com.vlad805.fmradio.models.FavoriteStation;
import com.vlad805.fmradio.service.audio.AudioService;
import com.vlad805.fmradio.service.audio.LightAudioService;
import com.vlad805.fmradio.service.audio.RoutingAudioService;
import com.vlad805.fmradio.service.fm.FMEventCallback;
import com.vlad805.fmradio.service.fm.IFMController;
import com.vlad805.fmradio.service.fm.IFMEventListener;
import com.vlad805.fmradio.service.fm.RadioStatePatch;
import com.vlad805.fmradio.service.fm.implementation.Empty;
import com.vlad805.fmradio.service.fm.implementation.QualcommNative;
import com.vlad805.fmradio.service.recording.IAudioRecordable;
import com.vlad805.fmradio.service.recording.RdsSnapshot;
import com.vlad805.fmradio.service.recording.RecordLameService;
import com.vlad805.fmradio.service.recording.RecordRawService;
import com.vlad805.fmradio.service.recording.RecordService;
import net.grandcentrix.tray.AppPreferences;
import net.grandcentrix.tray.core.OnTrayPreferenceChangeListener;
import net.grandcentrix.tray.core.TrayItem;

import java.util.*;

@SuppressWarnings({"deprecation", "unused"})
public class FMService extends Service implements FMEventCallback, OnTrayPreferenceChangeListener {
    private static final String TAG = "FMS";
    public static final int NOTIFICATION_ID = FMNotificationManager.NOTIFICATION_ID;
    public static final int NOTIFICATION_RECORD_ID = FMNotificationManager.NOTIFICATION_RECORD_ID;

    private FMNotificationManager mNotifications;

    private RadioController mRadioController;
    private FavoriteController mFavoriteController;

    private Map<Integer, String> mFavoriteList;

    private IFMController mTunerDriver;
    private TunerDriver mTunerDriverKind;
    private AudioService mAudioService;

    private BroadcastReceiver mEventReaction;
    private BroadcastReceiver mTunerStateUpdater;

    private AppPreferences mStorage;
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
        mNotifications = new FMNotificationManager(this);
        mNotifications.createChannels();

        // Preferences
        mStorage = Storage.getInstance(this);
        mStorage.registerOnTrayPreferenceChangeListener(this);

        // Services
        mTunerDriver = getTunerDriver();
        mAudioService = getAudioService();

        // Broadcast receivers
        mEventReaction = new EventReaction();
        mTunerStateUpdater = new RadioStateUpdater(mState, (state, mode) -> {
            if (mAudioService instanceof IAudioRecordable) {
                ((IAudioRecordable) mAudioService).updateRds(
                        new RdsSnapshot(
                                state.getFrequency(),
                                state.getPs(),
                                state.getRt(),
                                state.getPi(),
                                state.getRtPlus()
                        )
                );
            }
        });

        if (mTunerDriver instanceof IFMEventListener) {
            ((IFMEventListener) mTunerDriver).setEventListener(this);
        }

        reloadFavorite();

        Utils.registerAppReceiver(this, mTunerStateUpdater, RadioStateUpdater.sFilter);
        Utils.registerAppReceiver(this, mEventReaction, RadioStateUpdater.sFilter);
    }

    /**
     * Handling commands sent by the RadioController
     * Upon completion of the command, a specific event is triggered
     * @param intent Intent with command and arguments (in extras)
     */
    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        if (intent == null || intent.getAction() == null) {
            stopSelf();
            return START_NOT_STICKY;
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
                if (isHalDriver()) {
                    mAudioService.startAudio();
                }
                mTunerDriver.enable();
                break;
            }

            // Tuner stop and mute command.
            // When complete, will fire event DISABLED.
            case C.Command.DISABLE: {
                if (isHalDriver()) {
                    mAudioService.stopAudio();
                }
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
                    } catch (Throwable e) {
                        Log.e("FMS", "Failed to start recording", e);
                        final String message = e.getMessage() != null ? e.getMessage() : "Recording start failed";
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
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
                    return START_NOT_STICKY;
                }

                navigateThroughFavorite(direction);
                break;
            }

            case C.Command.HW_SEARCH: {
                mTunerDriver.hwSearch();
                break;
            }

            case C.Command.HW_SEARCH_CANCEL: {
                mTunerDriver.cancelHwSearch();
                break;
            }

            case C.Command.KILL: {
                if (mState.isRecording() && mAudioService instanceof IAudioRecordable) {
                    ((IAudioRecordable) mAudioService).stopRecord();
                }

                stopService(new Intent(this, FMService.class));
                Utils.sendAppBroadcast(this, new Intent(C.Event.KILLED));
                break;
            }

            case C.Command.REQUEST_CURRENT_STATE: {
                Utils.sendAppBroadcast(this, new Intent(C.Event.CURRENT_STATE).putExtra(C.Key.STATE, mState));
                break;
            }

            case C.Command.SPEAKER_STATE: {
                final boolean isSpeaker = isHalDriver()
                        ? mState.isForceSpeaker()
                        : Audio.isForceSpeakerNow();
                mAudioService.setSpeakerEnabled(!isSpeaker);
                Utils.sendAppBroadcast(this, new Intent(C.Event.CHANGE_SPEAKER_MODE).putExtra(C.Key.IS_SPEAKER, !isSpeaker));
                if (mTunerDriver instanceof QualcommNative) {
                    ((QualcommNative) mTunerDriver).refreshAudioRoute();
                }
                if (!isHalDriver()) {
                    Audio.toggleThroughSpeaker(!isSpeaker);
                }
                break;
            }
        }

        return START_NOT_STICKY;
    }

    /**
     * Stop FM
     * Kill the tuner:
     *   - the audio recording service;
     *   - the radio controller;
     *   - unregister the event receivers;
     *   - stop the background operation of the service
     */
    private void kill() {
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
     * Returns audio service
     * @return FMAudioService instance
     */
    private AudioService getAudioService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Android 8+
            return new RoutingAudioService(this);
        }

        // Android 7-
        return new LightAudioService(this);
    }

    /**
     * Returns tuner driver
     * @return Tuner driver
     */
    private IFMController getTunerDriver() {
        mTunerDriverKind = TunerDriverDetector.getTunerDriver();

        switch (mTunerDriverKind) {
            case HAL_HELIUM:
            case V4L2_QCOM: {
                return new QualcommNative(this, mTunerDriverKind);
            }

            case NONE:
            default: {
                return new Empty(this);
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

        final int sampleRate = mTunerDriver instanceof QualcommNative
                ? ((QualcommNative) mTunerDriver).getRecorderSampleRate()
                : 44100;

        switch (mode) {
            case C.RecordFormat.WAV: {
                return new RecordRawService(this, kHz, sampleRate);
            }

            case C.RecordFormat.MP3_192: {
                return new RecordLameService(this, kHz, sampleRate, 192);
            }

            case C.RecordFormat.MP3_128: {
                return new RecordLameService(this, kHz, sampleRate, 128);
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
    public void onEvent(final String event) {
        Utils.sendAppBroadcast(this, new Intent(event));
    }

    @Override
    public void onStatePatch(final RadioStatePatch patch) {
        Utils.sendAppBroadcast(this, patch.toIntent(C.Event.STATE_CHANGED));
    }

    @Override
    public void onSearchDone(final int[] stations) {
        Utils.sendAppBroadcast(this, new Intent(C.Event.HW_SEARCH_DONE).putExtra(C.Key.STATION_LIST, stations));
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
            if (C.PrefKey.RECORDING_SAVE_PAST.equals(item.key())) {
                final boolean enabled = Storage.getPrefBoolean(
                        this,
                        C.PrefKey.RECORDING_SAVE_PAST,
                        C.PrefDefaultValue.RECORDING_SAVE_PAST
                );
                mAudioService.setPreRollEnabled(enabled);
            }
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
                    if (mTunerDriver instanceof QualcommNative) {
                        mTunerDriverKind = ((QualcommNative) mTunerDriver).getDriverKind();
                    }
                    mRadioController.enable();
                    break;
                }

                case C.Event.LAUNCH_FAILED:
                case C.Event.ROOT_ACCESS_DENIED: {
                    stopSelf();
                    break;
                }

                case C.Event.ENABLED: {
                    if (!isHalDriver()) {
                        mAudioService.startAudio();
                    }
                    if (!(mTunerDriver instanceof QualcommNative)) {
                        final int frequency = mStorage.getInt(C.PrefKey.LAST_FREQUENCY, C.PrefDefaultValue.LAST_FREQUENCY);
                        mRadioController.setFrequency(frequency);
                    }
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

                case C.Event.STATE_CHANGED: {
                    if (intent.hasExtra(C.Key.FREQUENCY)) {
                        mStorage.put(C.PrefKey.LAST_FREQUENCY, intent.getIntExtra(C.Key.FREQUENCY, -1));
                    }
                    if (intent.hasExtra(C.Key.FREQUENCY) || intent.hasExtra(C.Key.PS) || intent.hasExtra(C.Key.RT)) {
                        updateNotification();
                    }
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
                    updateNotification();
                    break;
                }

                case C.Event.RECORD_TIME_UPDATE: {
                    mNotifications.showRecordingProgress(
                            intent.getIntExtra(C.Key.DURATION, 0),
                            intent.getIntExtra(C.Key.SIZE, 0)
                    );
                    break;
                }

                case C.Event.RECORD_ENDED: {
                    mRecordingNow = false;
                    updateNotification();
                    mNotifications.cancelRecordingProgress();

                    if (intent.getExtras() != null) {
                        final boolean showSavedNotification = Storage.getPrefBoolean(
                                FMService.this,
                                C.PrefKey.RECORDING_SHOW_NOTIFY,
                                C.PrefDefaultValue.RECORDING_SHOW_NOTIFY
                        );
                        mNotifications.showRecordingResult(intent.getExtras(), showSavedNotification);
                    }
                    break;
                }

                case C.Event.RECORD_FAILED: {
                    mRecordingNow = false;
                    updateNotification();
                    mNotifications.cancelRecordingProgress();
                    Toast.makeText(FMService.this, R.string.record_save_failed, Toast.LENGTH_LONG).show();
                    break;
                }
            }
        }
    }

    private boolean isHalDriver() {
        if (mTunerDriver instanceof QualcommNative) {
            return ((QualcommNative) mTunerDriver).getDriverKind() == TunerDriver.HAL_HELIUM;
        }
        return mTunerDriverKind == TunerDriver.HAL_HELIUM;
    }

    /**
     * Update notification
     */
    private void updateNotification() {
        final int frequency = mState.getFrequency();
        final boolean showRds = Storage.getPrefBoolean(
                this,
                C.PrefKey.NOTIFICATION_SHOW_RDS,
                C.PrefDefaultValue.NOTIFICATION_SHOW_RDS
        );
        final Notification notification = mNotifications.buildPlayback(
                frequency,
                mState.getPs(),
                mState.getRt(),
                mFavoriteList.get(frequency),
                showRds,
                mRecordingNow
        );

        startForeground(NOTIFICATION_ID, notification);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
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
