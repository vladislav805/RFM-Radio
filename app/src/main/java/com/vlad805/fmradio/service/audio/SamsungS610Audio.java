package com.vlad805.fmradio.service.audio;

import android.media.AudioManager;
import android.media.VolumeProvider;
import android.util.Log;

import com.vlad805.fmradio.helper.Audio;

public final class SamsungS610Audio implements RoutedAudioPolicy {
    private static final String TAG = "SamsungS610Audio";

    public interface HardwareVolumeController {
        interface Completion {
            void onApplied(int volume0To15);
            void onFailed();
        }

        void setVolume(int volume0To15, Completion completion);
    }

    public interface ForceUseFallback {
        void setForceUse(int usage, int config);
    }

    private final AudioManager mAudioManager;
    private final HardwareVolumeController mHardwareVolumeController;
    private final ForceUseFallback mForceUseFallback;
    private final Object mVolumeLock = new Object();
    private int mRequestedVolume = -1;
    private volatile int mAppliedVolume = -1;
    private int mPendingGeneration = 0;
    private volatile VolumeProvider mRecordingVolumeProvider;

    public SamsungS610Audio(
            final AudioManager audioManager,
            final HardwareVolumeController hardwareVolumeController
    ) {
        this(audioManager, hardwareVolumeController, null);
    }

    public SamsungS610Audio(
            final AudioManager audioManager,
            final HardwareVolumeController hardwareVolumeController,
            final ForceUseFallback forceUseFallback
    ) {
        mAudioManager = audioManager;
        mHardwareVolumeController = hardwareVolumeController;
        mForceUseFallback = forceUseFallback;
    }

    public int getAppliedVolume() {
        return mAppliedVolume;
    }

    public void setRecordingVolumeProvider(final VolumeProvider provider) {
        mRecordingVolumeProvider = provider;
    }

    @Override
    public void applyRoute(final boolean enabled, final AudioOutput output, final boolean updateVolume) {
        if (mAudioManager == null) {
            return;
        }
        if (!enabled) {
            resetVolumeState();
        }
        sendAudioParameter("g_fmradio_mute", "true");
        applyFrameworkRoute(enabled && output == AudioOutput.SPEAKER);
        if (enabled) {
            if (updateVolume) {
                applyVolume();
            }
            sendAudioParameter("g_fmradio_mute", "false");
            sendAudioParameter("g_fmradio_enable", "true");
            return;
        }
        sendAudioParameter("g_fmradio_enable", "false");
    }

    @Override
    public void onMusicVolumeChanged(final AudioOutput output) {
        if (mAudioManager == null) {
            return;
        }
        applyVolume();
    }

    @Override
    public AudioOutput getOutput(final AudioOutput requestedOutput) {
        return requestedOutput;
    }

    @Override
    public void afterOutputChanged(final AudioOutput output) {
    }

    private void applyVolume() {
        try {
            final int volumeIndex = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            final VolumeProvider volumeProvider = mRecordingVolumeProvider;
            if (volumeProvider != null) {
                volumeProvider.setCurrentVolume(volumeIndex);
            }
            final int maxVolume = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            final int scaled = maxVolume > 0
                    ? Math.max(0, Math.min(15, Math.round(volumeIndex * 15f / maxVolume)))
                    : 0;
            Log.d(TAG, "applyVolume(exynos): index=" + volumeIndex + "/" + maxVolume + " scaled=" + scaled);
            applyVolumeIndex(scaled);
        } catch (Throwable t) {
            Log.e(TAG, "applyVolume failed", t);
        }
    }

    void resetVolumeState() {
        synchronized (mVolumeLock) {
            mRequestedVolume = -1;
            mAppliedVolume = -1;
            mPendingGeneration++;
        }
    }

    void applyVolumeIndex(final int scaled) {
        final int clamped = Math.max(0, Math.min(15, scaled));
        final int generation;
        synchronized (mVolumeLock) {
            if (clamped == mRequestedVolume) {
                return;
            }
            mRequestedVolume = clamped;
            generation = ++mPendingGeneration;
        }
        if (mHardwareVolumeController == null) {
            synchronized (mVolumeLock) {
                if (generation == mPendingGeneration) {
                    mRequestedVolume = mAppliedVolume;
                }
            }
            return;
        }
        mHardwareVolumeController.setVolume(clamped, new HardwareVolumeController.Completion() {
            @Override
            public void onApplied(final int volume0To15) {
                synchronized (mVolumeLock) {
                    // Note: during a full-band scan native responds "ok" immediately and applies
                    // desired_volume_ on scan completion while hardware mute is active.
                    if (generation == mPendingGeneration) {
                        mAppliedVolume = Math.max(0, Math.min(15, volume0To15));
                    }
                }
            }

            @Override
            public void onFailed() {
                synchronized (mVolumeLock) {
                    if (generation == mPendingGeneration) {
                        mRequestedVolume = mAppliedVolume;
                    }
                }
            }
        });
    }

    /**
     * Samsung's FM audio HAL routes analog FM playback through AudioPolicyManager
     * under usage FOR_FM (8), not FOR_COMMUNICATION (setSpeakerphoneOn) or FOR_MEDIA (1).
     * Direct AudioSystem.setForceUse() requires MODIFY_AUDIO_ROUTING on Android 11+,
     * so when it returns an error we fall back to the root fmbin process.
     */
    private void applyFrameworkRoute(final boolean speakerEnabled) {
        try {
            final int usage = Audio.resolveSamsungFmForceUsage();
            final int config = speakerEnabled ? Audio.FORCE_SPEAKER : Audio.FORCE_NONE;
            if (Audio.invokeSetForceUseDirect(usage, config) != 0 && mForceUseFallback != null) {
                mForceUseFallback.setForceUse(usage, config);
            }
        } catch (Throwable t) {
            Log.w(TAG, "applyFrameworkRoute failed for speaker=" + speakerEnabled, t);
        }
    }

    private void sendAudioParameter(final String key, final String value) {
        final String pair = key + "=" + value;
        try {
            Log.d(TAG, "setParameters: " + pair);
            mAudioManager.setParameters(pair);
        } catch (Throwable t) {
            Log.e(TAG, "setParameters failed: " + pair, t);
        }
    }
}
