package com.vlad805.fmradio.service;

import android.app.Notification;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
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
import com.vlad805.fmradio.helper.TunerDriverDetector;
import com.vlad805.fmradio.models.FavoriteStation;
import com.vlad805.fmradio.service.audio.AudioLifecycle;
import com.vlad805.fmradio.service.audio.AudioOutput;
import com.vlad805.fmradio.service.audio.AudioSelection;
import com.vlad805.fmradio.service.audio.AudioService;
import com.vlad805.fmradio.service.audio.LightAudioService;
import com.vlad805.fmradio.service.audio.QualcommRoutedAudio;
import com.vlad805.fmradio.service.audio.RoutingAudioService;
import com.vlad805.fmradio.service.audio.SamsungS610Audio;
import com.vlad805.fmradio.service.fm.FMEventCallback;
import com.vlad805.fmradio.service.fm.RadioStatePatch;
import com.vlad805.fmradio.service.fm.implementation.NativeTunerController;
import com.vlad805.fmradio.service.recording.IAudioRecordable;
import com.vlad805.fmradio.service.recording.RdsSnapshot;
import com.vlad805.fmradio.service.recording.RecordLameService;
import com.vlad805.fmradio.service.recording.RecordRawService;
import com.vlad805.fmradio.service.recording.RecordService;

import java.util.*;

@SuppressWarnings({"deprecation", "unused"})
public class FMService extends Service implements FMEventCallback {
    private static final String TAG = "FMS";
    public static final int NOTIFICATION_ID = FMNotificationManager.NOTIFICATION_ID;
    public static final int NOTIFICATION_RECORD_ID = FMNotificationManager.NOTIFICATION_RECORD_ID;

    private FMNotificationManager mNotifications;

    private RadioController mRadioController;
    private FavoriteController mFavoriteController;

    private Map<Integer, String> mFavoriteList;

    private NativeTunerController mTunerDriver;
    private TunerDriver mTunerDriverKind;
    private AudioService mAudioService;
    private AudioLifecycle mAudioLifecycle;

    private BroadcastReceiver mEventReaction;
    private BroadcastReceiver mTunerStateUpdater;

    private Storage mStorage;
    private final SharedPreferences.OnSharedPreferenceChangeListener mPreferenceChangeListener = this::onPreferenceChanged;
    private boolean mRecordingNow = false;
    private boolean mDestroyed = false;

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
        mStorage = Storage.get(this);
        mStorage.registerOnChangeListener(mPreferenceChangeListener);

        // Services
        mTunerDriverKind = TunerDriverDetector.getTunerDriver();
        mTunerDriver = mTunerDriverKind != TunerDriver.NONE
                ? new NativeTunerController(this, mTunerDriverKind)
                : null;

        // Broadcast receivers
        mEventReaction = new EventReaction();
        mTunerStateUpdater = new RadioStateUpdater(mState, (state, mode) -> {
            if (mAudioService instanceof IAudioRecordable) {
                ((IAudioRecordable) mAudioService).updateRds(createRdsSnapshot(state));
            }
        });

        if (mTunerDriver != null) {
            mTunerDriver.setEventListener(this);
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

        final String action = intent.getAction();
        if (C.Command.REQUEST_CURRENT_STATE.equals(action)) {
            Utils.sendAppBroadcast(this, new Intent(C.Event.CURRENT_STATE).putExtra(C.Key.STATE, mState));
            return START_NOT_STICKY;
        }

        if (C.Command.KILL.equals(action)) {
            if (mState.isRecording() && mAudioService instanceof IAudioRecordable) {
                ((IAudioRecordable) mAudioService).stopRecord();
            }

            stopService(new Intent(this, FMService.class));
            Utils.sendAppBroadcast(this, new Intent(C.Event.KILLED));
            return START_NOT_STICKY;
        }

        if (mTunerDriver == null) {
            onEvent(C.Event.LAUNCH_FAILED);
            stopSelf();
            return START_NOT_STICKY;
        }

        switch (action) {
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
                if (mAudioLifecycle != null) {
                    mAudioLifecycle.beforeEnable();
                }
                mTunerDriver.enable();
                break;
            }

            // Tuner stop and mute command.
            // When complete, will fire event DISABLED.
            case C.Command.DISABLE: {
                if (mAudioLifecycle != null) {
                    mAudioLifecycle.beforeDisable();
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
                final boolean byFavorite = Storage.get(this).isNotificationSeekByFavorites();

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

            case C.Command.SPEAKER_STATE: {
                if (mAudioService == null) {
                    break;
                }
                final AudioOutput nextOutput = mAudioService.getOutput() == AudioOutput.SPEAKER
                        ? AudioOutput.HEADPHONES
                        : AudioOutput.SPEAKER;
                mAudioService.setOutput(nextOutput);
                Utils.sendAppBroadcast(
                        this,
                        new Intent(C.Event.CHANGE_SPEAKER_MODE)
                                .putExtra(C.Key.IS_SPEAKER, nextOutput == AudioOutput.SPEAKER)
                );
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
        mDestroyed = true;
        if (mAudioLifecycle != null) {
            mAudioLifecycle.stop();
        } else if (mAudioService != null) {
            mAudioService.stopAudio();
        }

        if (mTunerDriver != null) {
            mTunerDriver.kill();
        }

        if (mEventReaction != null) {
            unregisterReceiver(mEventReaction);
            mEventReaction = null;
        }

        if (mTunerStateUpdater != null) {
            unregisterReceiver(mTunerStateUpdater);
            mTunerStateUpdater = null;
        }

        if (mStorage != null) {
            mStorage.unregisterOnChangeListener(mPreferenceChangeListener);
            mStorage = null;
        }

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
     * Selects and attaches the audio service once after native launch resolves the backend.
     */
    private void attachAudioService(final TunerDriver resolvedDriver) {
        if (mAudioService != null || mAudioLifecycle != null) {
            return;
        }

        final AudioSelection selection = AudioSelection.resolve(resolvedDriver);
        final boolean isHelium = resolvedDriver == TunerDriver.HAL_HELIUM;
        final Runnable refreshAudioRoute = isHelium && mTunerDriver != null
                ? mTunerDriver::refreshAudioRoute
                : () -> {};
        final AudioService audioService;

        switch (selection.path()) {
            case QUALCOMM_ROUTED: {
                final AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                final QualcommRoutedAudio policy = new QualcommRoutedAudio(
                        audioManager,
                        !isHelium,
                        refreshAudioRoute
                );
                audioService = new RoutingAudioService(this, policy);
                break;
            }

            case SAMSUNG_ROUTED: {
                final AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                final SamsungS610Audio policy = new SamsungS610Audio(
                        audioManager,
                        (volume, completion) -> {
                            if (mTunerDriver != null) {
                                mTunerDriver.setVolume(volume, completion);
                            } else if (completion != null) {
                                completion.onFailed();
                            }
                        },
                        (usage, config) -> {
                            if (mTunerDriver != null) {
                                mTunerDriver.setForceUse(usage, config);
                            }
                        }
                );
                audioService = new RoutingAudioService(this, policy);
                break;
            }

            case PCM_LOOPBACK: {
                audioService = new LightAudioService(this, !isHelium, refreshAudioRoute);
                break;
            }

            case NONE:
            default: {
                audioService = null;
                break;
            }
        }

        mAudioService = audioService;

        if (audioService != null) {
            audioService.setPreRollEnabled(Storage.get(this).isRecordingPreRollEnabled());

            if (audioService instanceof IAudioRecordable) {
                ((IAudioRecordable) audioService).updateRds(createRdsSnapshot(mState));
            }

            mAudioLifecycle = new AudioLifecycle(selection, audioService::startAudio, audioService::stopAudio);
        } else {
            mAudioLifecycle = new AudioLifecycle(selection, () -> {}, () -> {});
        }
    }

    private static RdsSnapshot createRdsSnapshot(final RadioState state) {
        return new RdsSnapshot(
                state.getFrequency(),
                state.getPs(),
                state.getRt(),
                state.getPi(),
                state.getRtPlus()
        );
    }

    /**
     * Returns preferred by user audio recorder (WAV / MP3)
     * @return Recorder
     */
    private RecordService getPreferredRecorder() {
        final Storage storage = Storage.get(this);
        final int mode = storage.getRecordingFormat();
        final int kHz = storage.getLastFrequency();

        final int sampleRate = mAudioService.getPcmFormat().sampleRateHz();

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
     * Called by NativeTunerController via FMEventCallback
     * @param event Action
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
    public void onSearchProgress(final int count) {
        Utils.sendAppBroadcast(this, new Intent(C.Event.HW_SEARCH_PROGRESS).putExtra(C.Key.COUNT, count));
    }

    @Override
    public void onSearchDone(final int[] stations) {
        Utils.sendAppBroadcast(this, new Intent(C.Event.HW_SEARCH_DONE).putExtra(C.Key.STATION_LIST, stations));
    }

    private void navigateThroughFavorite(final int direction) {
        final List<FavoriteStation> stations = mFavoriteController.getStationsInCurrentList();
        final int currentFrequency = Storage.get(this).getLastFrequency();
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

        if (mTunerDriver != null) {
            mTunerDriver.setFrequency(stations.get(currentPosition).getFrequency());
        }
    }

    private void onPreferenceChanged(final SharedPreferences prefs, final String key) {
        if (key == null) {
            return;
        }

        if (C.PrefKey.RECORDING_SAVE_PAST.equals(key)) {
            if (mAudioService != null) {
                mAudioService.setPreRollEnabled(Storage.get(this).isRecordingPreRollEnabled());
            }
            return;
        }

        if (mTunerDriver != null && NativeTunerController.isTunerPreferenceKey(key)) {
            mTunerDriver.setupTunerByPreferences(new String[]{key});
        }
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
                    if (mDestroyed) {
                        break;
                    }
                    if (mTunerDriver != null) {
                        mTunerDriverKind = mTunerDriver.getDriverKind();
                    }
                    attachAudioService(mTunerDriverKind);
                    mRadioController.enable();
                    break;
                }

                case C.Event.LAUNCH_FAILED:
                case C.Event.ROOT_ACCESS_DENIED: {
                    stopSelf();
                    break;
                }

                case C.Event.ENABLED: {
                    if (mDestroyed) {
                        break;
                    }
                    if (mAudioLifecycle != null) {
                        mAudioLifecycle.onEnabled();
                    }
                    updateNotification();
                    break;
                }

                case C.Event.DISABLED: {
                    if (mAudioLifecycle != null) {
                        mAudioLifecycle.stop();
                    } else if (mAudioService != null) {
                        mAudioService.stopAudio();
                    }
                    break;
                }

                case C.Event.FREQUENCY_SET: {
                    final int frequency = intent.getIntExtra(C.Key.FREQUENCY, -1);
                    Storage.get(FMService.this).setLastFrequency(frequency);
                    updateNotification();
                    break;
                }

                case C.Event.STATE_CHANGED: {
                    if (intent.hasExtra(C.Key.FREQUENCY)) {
                        Storage.get(FMService.this).setLastFrequency(intent.getIntExtra(C.Key.FREQUENCY, -1));
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
                        final boolean showSavedNotification = Storage.get(FMService.this).isRecordingShowNotify();
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

    /**
     * Update notification
     */
    private void updateNotification() {
        final int frequency = mState.getFrequency();
        final boolean showRds = Storage.get(this).isNotificationShowRds();
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
