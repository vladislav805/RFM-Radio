package com.vlad805.fmradio.service;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.vlad805.fmradio.BuildConfig;
import com.vlad805.fmradio.C;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.Storage;
import com.vlad805.fmradio.controller.EventListener;
import com.vlad805.fmradio.controller.FavoriteController;
import com.vlad805.fmradio.controller.RadioServiceController;
import com.vlad805.fmradio.controller.RadioState;
import com.vlad805.fmradio.controller.StateListener;
import com.vlad805.fmradio.controller.TunerStatus;
import com.vlad805.fmradio.enums.Direction;
import com.vlad805.fmradio.enums.PowerMode;
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
import com.vlad805.fmradio.service.recording.RecordingListener;
import com.vlad805.fmradio.service.recording.RecordingResult;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

public class FMService extends Service implements RadioServiceController, FMEventCallback {
    private static final String TAG = "FMS";
    private static final String ACTION_PREFIX = BuildConfig.APPLICATION_ID + ".action.";

    public static final String ACTION_START = ACTION_PREFIX + "START";
    public static final String ACTION_STOP = ACTION_PREFIX + "STOP";
    public static final String ACTION_RECORD_START = ACTION_PREFIX + "RECORD_START";
    public static final String ACTION_RECORD_STOP = ACTION_PREFIX + "RECORD_STOP";
    public static final String ACTION_NOTIFICATION_SEEK = ACTION_PREFIX + "NOTIFICATION_SEEK";

    public static final String EXTRA_SEEK_DIRECTION = "seek_direction";

    public static final int NOTIFICATION_ID = FMNotificationManager.NOTIFICATION_ID;
    public static final int NOTIFICATION_RECORD_ID = FMNotificationManager.NOTIFICATION_RECORD_ID;

    private static volatile boolean sTunerActive = false;

    public static boolean isTunerActive() {
        return sTunerActive;
    }

    public final class LocalBinder extends Binder {
        @NonNull
        public RadioServiceController getController() {
            return FMService.this;
        }
    }

    private final IBinder mBinder = new LocalBinder();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final Set<StateListener> mStateListeners = new CopyOnWriteArraySet<>();
    private final Set<EventListener> mEventListeners = new CopyOnWriteArraySet<>();

    private FMNotificationManager mNotifications;
    private FavoriteController mFavoriteController;

    private NativeTunerController mTunerDriver;
    private TunerDriver mTunerDriverKind;
    private AudioService mAudioService;
    private AudioLifecycle mAudioLifecycle;

    private Storage mStorage;
    private RadioState mState;
    private boolean mSessionActive = false;
    private boolean mDestroyed = false;
    private boolean mLastNotifiedRecording = false;

    private final SharedPreferences.OnSharedPreferenceChangeListener mPreferenceChangeListener = this::onPreferenceChanged;

    private final FavoriteController.OnFavoritesChangeListener mFavoritesListener =
            (currentListName, stations) -> runOnMainThread(() -> {
                if (mState != null && mState.getStatus() == TunerStatus.ENABLED) {
                    updateNotification();
                }
            });

    private final RecordingListener mRecordingListener = new RecordingListener() {
        @Override
        public void onRecordingStarted(
                final int initialDurationSeconds,
                final int sizeBytes,
                @NonNull final String displayPath
        ) {
            runOnMainThread(() -> {
                if (mDestroyed) {
                    return;
                }
                onInternalStateChanged(mState.applyRecordingStarted(initialDurationSeconds));
            });
        }

        @Override
        public void onRecordingProgress(
                final int durationSeconds,
                final int sizeBytes,
                @NonNull final String displayPath
        ) {
            runOnMainThread(() -> {
                if (mDestroyed) {
                    return;
                }
                onInternalStateChanged(mState.applyRecordingProgress(durationSeconds));
                mNotifications.showRecordingProgress(durationSeconds, sizeBytes);
            });
        }

        @Override
        public void onRecordingCompleted(@NonNull final RecordingResult result) {
            runOnMainThread(() -> {
                if (mDestroyed) {
                    return;
                }
                onInternalStateChanged(mState.applyRecordingStopped());
                mNotifications.cancelRecordingProgress();
                mNotifications.showRecordingResult(result, mStorage.isRecordingShowNotify());
                for (final EventListener listener : mEventListeners) {
                    listener.onRecordingFinished(result);
                }
            });
        }

        @Override
        public void onRecordingFailed(@NonNull final Throwable error) {
            runOnMainThread(() -> {
                if (mDestroyed) {
                    return;
                }
                onInternalStateChanged(mState.applyRecordingStopped());
                mNotifications.cancelRecordingProgress();
                Toast.makeText(FMService.this, R.string.record_save_failed, Toast.LENGTH_LONG).show();
                for (final EventListener listener : mEventListeners) {
                    listener.onRecordingFailed(error.getMessage());
                }
            });
        }
    };

    private void runOnMainThread(@NonNull final Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            mMainHandler.post(action);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        mStorage = Storage.get(this);
        mState = new RadioState();
        mState.applyFrequency(mStorage.getLastFrequency());
        sTunerActive = false;

        mFavoriteController = FavoriteController.get(this);
        mFavoriteController.addOnChangeListener(mFavoritesListener);
        mNotifications = new FMNotificationManager(this);
        mNotifications.createChannels();

        mStorage.registerOnChangeListener(mPreferenceChangeListener);

        mTunerDriverKind = TunerDriverDetector.getTunerDriver();
        mTunerDriver = mTunerDriverKind != TunerDriver.NONE
                ? new NativeTunerController(this, mTunerDriverKind)
                : null;

        if (mTunerDriver != null) {
            mTunerDriver.setEventListener(this);
        }
    }

    @Override
    public IBinder onBind(final Intent intent) {
        return mBinder;
    }

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        if (intent == null || intent.getAction() == null) {
            if (!isTunerActive()) {
                stopSelf();
            }
            return START_NOT_STICKY;
        }

        final String action = intent.getAction();
        switch (action) {
            case ACTION_START: {
                // Promotion to Started service triggered by enable().
                break;
            }

            case ACTION_STOP: {
                disable();
                break;
            }

            case ACTION_RECORD_START: {
                startRecord();
                break;
            }

            case ACTION_RECORD_STOP: {
                stopRecord();
                break;
            }

            case ACTION_NOTIFICATION_SEEK: {
                final int direction = intent.getIntExtra(EXTRA_SEEK_DIRECTION, 1);
                if (mStorage.isNotificationSeekByFavorites()) {
                    navigateThroughFavorite(direction);
                } else {
                    hwSeek(direction >= 0 ? Direction.UP : Direction.DOWN);
                }
                break;
            }

            default: {
                Log.w(TAG, "Ignoring unknown service action: " + action);
                break;
            }
        }

        return START_NOT_STICKY;
    }

    // --- RadioServiceController ---

    @NonNull
    @Override
    public RadioState getState() {
        return mState;
    }

    @Override
    public void enable() {
        final TunerStatus status = mState.getStatus();
        if (
                status == TunerStatus.INSTALLING ||
                status == TunerStatus.INSTALLED ||
                status == TunerStatus.LAUNCHING ||
                status == TunerStatus.LAUNCHED ||
                status == TunerStatus.ENABLING ||
                status == TunerStatus.ENABLED
        ) {
            return;
        }

        if (mTunerDriver == null) {
            mSessionActive = false;
            onInternalStateChanged(mState.applyStatus(TunerStatus.FATAL_ERROR));
            stopSelf();
            return;
        }

        mSessionActive = true;
        startService(new Intent(this, FMService.class).setAction(ACTION_START));
        mTunerDriver.prepareBinary();
    }

    @Override
    public void disable() {
        stopPlaybackSession(true);
    }

    @Override
    public void setFrequency(final int kHz) {
        if (mTunerDriver != null && mState.getStatus() == TunerStatus.ENABLED) {
            mTunerDriver.setFrequency(kHz);
        }
    }

    @Override
    public void jump(@NonNull final Direction direction) {
        if (mTunerDriver != null && mState.getStatus() == TunerStatus.ENABLED) {
            mTunerDriver.jump(direction.getValue());
        }
    }

    @Override
    public void hwSeek(@NonNull final Direction direction) {
        if (mTunerDriver != null && mState.getStatus() == TunerStatus.ENABLED) {
            mTunerDriver.hwSeek(direction.getValue());
        }
    }

    @Override
    public void hwSearch() {
        if (mTunerDriver != null && mState.getStatus() == TunerStatus.ENABLED) {
            mTunerDriver.hwSearch();
        }
    }

    @Override
    public void cancelHwSearch() {
        if (mTunerDriver != null && mState.getStatus() == TunerStatus.ENABLED) {
            mTunerDriver.cancelHwSearch();
        }
    }

    @Override
    public void setPowerMode(@NonNull final PowerMode mode) {
        if (mTunerDriver != null && mState.getStatus() == TunerStatus.ENABLED) {
            mTunerDriver.setPowerMode(mode.getValue());
        }
    }

    @Override
    public void toggleSpeaker() {
        if (mAudioService == null || mState.getStatus() != TunerStatus.ENABLED) {
            return;
        }
        final AudioOutput nextOutput = mAudioService.getOutput() == AudioOutput.SPEAKER
                ? AudioOutput.HEADPHONES
                : AudioOutput.SPEAKER;
        mAudioService.setOutput(nextOutput);
        onInternalStateChanged(mState.applyForceSpeaker(nextOutput == AudioOutput.SPEAKER));
    }

    @Override
    public void startRecord() {
        if (mState.getStatus() != TunerStatus.ENABLED) {
            return;
        }
        if (mAudioService instanceof IAudioRecordable) {
            try {
                final RecordService recorder = getPreferredRecorder();
                if (recorder == null) {
                    Toast.makeText(this, R.string.service_record_unsupported, Toast.LENGTH_LONG).show();
                    return;
                }
                ((IAudioRecordable) mAudioService).startRecord(recorder);
            } catch (final Throwable e) {
                Log.e(TAG, "Failed to start recording", e);
                final String message = e.getMessage() != null ? e.getMessage() : "Recording start failed";
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            }
        } else {
            Toast.makeText(this, R.string.service_record_unsupported, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void stopRecord() {
        if (mAudioService instanceof IAudioRecordable) {
            ((IAudioRecordable) mAudioService).stopRecord();
        } else {
            Toast.makeText(this, R.string.service_record_unsupported, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void addStateListener(@NonNull final StateListener listener) {
        mStateListeners.add(listener);
        listener.onStateUpdated(mState, RadioState.INITIAL_SNAPSHOT_MASK);
    }

    @Override
    public void removeStateListener(@NonNull final StateListener listener) {
        mStateListeners.remove(listener);
    }

    @Override
    public void addEventListener(@NonNull final EventListener listener) {
        mEventListeners.add(listener);
    }

    @Override
    public void removeEventListener(@NonNull final EventListener listener) {
        mEventListeners.remove(listener);
    }

    // --- Session & service teardown ---

    /**
     * Tears down the active FM playback/recording session and terminates {@code fmbin}
     * while keeping service-level listeners alive for bound UI clients.
     */
    private void stopPlaybackSession(final boolean gracefulTunerDisable) {
        final TunerStatus previousStatus = mState.getStatus();
        final boolean wasActive = mSessionActive
                || previousStatus != TunerStatus.IDLE
                || mAudioService != null
                || mAudioLifecycle != null;

        mSessionActive = false;

        if (mState.isRecording() && mAudioService instanceof IAudioRecordable) {
            ((IAudioRecordable) mAudioService).stopRecord();
        }
        mNotifications.cancelRecordingProgress();

        if (mAudioLifecycle != null) {
            if (gracefulTunerDisable) {
                mAudioLifecycle.beforeDisable();
            }
            mAudioLifecycle.stop();
            mAudioLifecycle = null;
        } else if (mAudioService != null) {
            mAudioService.stopAudio();
        }
        mAudioService = null;

        if (mTunerDriver != null && wasActive) {
            if (gracefulTunerDisable
                    && (previousStatus == TunerStatus.ENABLED || previousStatus == TunerStatus.ENABLING)) {
                mTunerDriver.disable();
            } else {
                mTunerDriver.kill();
            }
        }

        int mode = 0;
        if (mState.isRecording()) {
            mode |= mState.applyRecordingStopped();
        }
        if (mState.isForceSpeaker()) {
            mode |= mState.applyForceSpeaker(false);
        }
        if (mState.getStatus() != TunerStatus.IDLE) {
            mode |= mState.applyStatus(TunerStatus.IDLE);
        }

        mLastNotifiedRecording = false;
        if (mode != 0) {
            onInternalStateChanged(mode);
        } else {
            sTunerActive = false;
        }

        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        mDestroyed = true;

        if (mSessionActive || mState.getStatus() != TunerStatus.IDLE || mAudioService != null || mAudioLifecycle != null) {
            stopPlaybackSession(false);
        } else if (mTunerDriver != null) {
            mTunerDriver.kill();
        }

        if (mStorage != null) {
            mStorage.unregisterOnChangeListener(mPreferenceChangeListener);
        }

        if (mFavoriteController != null) {
            mFavoriteController.removeOnChangeListener(mFavoritesListener);
        }

        mStateListeners.clear();
        mEventListeners.clear();
        sTunerActive = false;

        super.onDestroy();
    }

    // --- FMEventCallback (marshaled to main looper) ---

    @Override
    public void onStatusChanged(@NonNull final TunerStatus status) {
        runOnMainThread(() -> {
            if (mDestroyed) {
                return;
            }
            if (!mSessionActive && status != TunerStatus.IDLE) {
                return;
            }

            switch (status) {
                case INSTALLING:
                case LAUNCHING:
                case ENABLING:
                case DISABLING: {
                    onInternalStateChanged(mState.applyStatus(status));
                    break;
                }

                case INSTALLED: {
                    onInternalStateChanged(mState.applyStatus(TunerStatus.INSTALLED));
                    if (mTunerDriver != null) {
                        mTunerDriver.launch();
                    }
                    break;
                }

                case LAUNCHED: {
                    onInternalStateChanged(mState.applyStatus(TunerStatus.LAUNCHED));
                    if (mTunerDriver != null) {
                        mTunerDriverKind = mTunerDriver.getDriverKind();
                    }
                    attachAudioService(mTunerDriverKind);
                    if (mAudioLifecycle != null) {
                        mAudioLifecycle.beforeEnable();
                    }
                    if (mTunerDriver != null) {
                        mTunerDriver.enable();
                    }
                    break;
                }

                case ENABLED: {
                    if (mAudioLifecycle != null) {
                        mAudioLifecycle.onEnabled();
                    }
                    onInternalStateChanged(mState.applyStatus(TunerStatus.ENABLED));
                    break;
                }

                case ROOT_ACCESS_DENIED:
                case FATAL_ERROR: {
                    mSessionActive = false;
                    if (mAudioLifecycle != null) {
                        mAudioLifecycle.stop();
                        mAudioLifecycle = null;
                    } else if (mAudioService != null) {
                        mAudioService.stopAudio();
                    }
                    mAudioService = null;
                    if (mTunerDriver != null) {
                        mTunerDriver.kill();
                    }
                    onInternalStateChanged(mState.applyStatus(status));
                    stopForeground(true);
                    stopSelf();
                    break;
                }

                case IDLE: {
                    stopPlaybackSession(false);
                    break;
                }
            }
        });
    }

    @Override
    public void onFrequencyChanged(final int frequencyKhz) {
        runOnMainThread(() -> {
            if (mDestroyed || !mSessionActive || frequencyKhz <= 0) {
                return;
            }
            onInternalStateChanged(mState.applyFrequency(frequencyKhz));
        });
    }

    @Override
    public void onStatePatch(@NonNull final RadioStatePatch patch) {
        runOnMainThread(() -> {
            if (mDestroyed || !mSessionActive) {
                return;
            }
            onInternalStateChanged(mState.applyPatch(patch));
        });
    }

    @Override
    public void onSearchProgress(final int count) {
        runOnMainThread(() -> {
            if (mDestroyed) {
                return;
            }
            for (final EventListener listener : mEventListeners) {
                listener.onSearchProgress(count);
            }
        });
    }

    @Override
    public void onSearchDone(@NonNull final int[] stations) {
        runOnMainThread(() -> {
            if (mDestroyed) {
                return;
            }
            for (final EventListener listener : mEventListeners) {
                listener.onSearchDone(stations);
            }
        });
    }

    @Override
    public void onSearchFailed() {
        runOnMainThread(() -> {
            if (mDestroyed) {
                return;
            }
            for (final EventListener listener : mEventListeners) {
                listener.onSearchFailed();
            }
        });
    }

    @Override
    public void onInvalidAntennaError() {
        runOnMainThread(() -> {
            if (mDestroyed) {
                return;
            }
            for (final EventListener listener : mEventListeners) {
                listener.onInvalidAntennaError();
            }
        });
    }

    @Override
    public void onError(@NonNull final String message) {
        runOnMainThread(() -> {
            if (mDestroyed) {
                return;
            }
            for (final EventListener listener : mEventListeners) {
                listener.onTunerError(message);
            }
        });
    }

    @Override
    public void onUnexpectedExit(final int exitCode) {
        runOnMainThread(() -> {
            if (mDestroyed) {
                return;
            }
            final String message = "Native tuner process exited with code " + exitCode;
            for (final EventListener listener : mEventListeners) {
                listener.onTunerError(message);
            }
            stopPlaybackSession(false);
        });
    }

    // --- Centralized state side effects ---

    private void onInternalStateChanged(final int mode) {
        if (mode == 0) {
            return;
        }

        sTunerActive = mState.getStatus() != TunerStatus.IDLE;

        if ((mode & RadioState.SET_FREQUENCY) != 0 && mState.getFrequency() > 0) {
            mStorage.setLastFrequency(mState.getFrequency());
        }

        if (
                (mode & (RadioState.SET_FREQUENCY | RadioState.SET_PS | RadioState.SET_RT
                        | RadioState.SET_PI | RadioState.SET_RT_PLUS)) != 0
                && mAudioService instanceof IAudioRecordable
        ) {
            ((IAudioRecordable) mAudioService).updateRds(createRdsSnapshot(mState));
        }

        if (mState.getStatus() == TunerStatus.ENABLED) {
            final boolean recordingToggled =
                    (mode & RadioState.SET_RECORDING) != 0 && mLastNotifiedRecording != mState.isRecording();
            if (
                    recordingToggled ||
                    (mode & (RadioState.SET_STATUS | RadioState.SET_FREQUENCY
                            | RadioState.SET_PS | RadioState.SET_RT)) != 0
            ) {
                updateNotification();
            }
        }

        for (final StateListener listener : mStateListeners) {
            listener.onStateUpdated(mState, mode);
        }
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
            audioService.setPreRollEnabled(mStorage.isRecordingPreRollEnabled());

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
        final int mode = mStorage.getRecordingFormat();
        final int kHz = mState.getFrequency() > 0 ? mState.getFrequency() : mStorage.getLastFrequency();

        final int sampleRate = mAudioService.getPcmFormat().sampleRateHz();

        switch (mode) {
            case C.RecordFormat.WAV: {
                return new RecordRawService(this, kHz, sampleRate, mRecordingListener);
            }

            case C.RecordFormat.MP3_192: {
                return new RecordLameService(this, kHz, sampleRate, 192, mRecordingListener);
            }

            case C.RecordFormat.MP3_128: {
                return new RecordLameService(this, kHz, sampleRate, 128, mRecordingListener);
            }
        }

        return null;
    }

    private void navigateThroughFavorite(final int direction) {
        final List<FavoriteStation> stations = mFavoriteController.getStationsInCurrentList();
        final int currentFrequency = mState.getFrequency() > 0
                ? mState.getFrequency()
                : mStorage.getLastFrequency();
        int currentPosition = -1;

        for (int i = 0; i < stations.size(); i++) {
            final FavoriteStation station = stations.get(i);
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

        setFrequency(stations.get(currentPosition).getFrequency());
    }

    private void onPreferenceChanged(final SharedPreferences prefs, final String key) {
        if (key == null) {
            return;
        }

        if (Storage.Key.RECORDING_SAVE_PAST.equals(key)) {
            if (mAudioService != null) {
                mAudioService.setPreRollEnabled(mStorage.isRecordingPreRollEnabled());
            }
            return;
        }

        if (Storage.Key.NOTIFICATION_SHOW_RDS.equals(key)) {
            if (mState != null && mState.getStatus() == TunerStatus.ENABLED) {
                updateNotification();
            }
            return;
        }

        if (mTunerDriver != null
                && mState != null
                && mState.getStatus() == TunerStatus.ENABLED
                && NativeTunerController.isTunerPreferenceKey(key)) {
            mTunerDriver.setupTunerByPreferences(new String[]{key});
        }
    }

    /**
     * Update foreground playback notification.
     */
    private void updateNotification() {
        final int frequency = mState.getFrequency();
        final boolean showRds = mStorage.isNotificationShowRds();
        final boolean recording = mState.isRecording();
        mLastNotifiedRecording = recording;
        final Notification notification = mNotifications.buildPlayback(
                frequency,
                mState.getPs(),
                mState.getRt(),
                mFavoriteController.getFavoriteTitle(frequency),
                showRds,
                recording
        );

        startForeground(NOTIFICATION_ID, notification);
    }
}
