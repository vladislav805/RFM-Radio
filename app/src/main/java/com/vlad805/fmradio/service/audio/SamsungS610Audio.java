package com.vlad805.fmradio.service.audio;

import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.VolumeProvider;
import android.os.Build;
import android.util.Log;

public final class SamsungS610Audio implements RoutedAudioPolicy {
    private static final String TAG = "SamsungS610Audio";

    public interface HardwareVolumeListener {
        void onVolumeChanged(int volume0To15);
    }

    private final AudioManager mAudioManager;
    private final HardwareVolumeListener mHardwareVolumeListener;
    private volatile int mLastVolume = -1;
    private volatile VolumeProvider mRecordingVolumeProvider;

    public SamsungS610Audio(
            final AudioManager audioManager,
            final HardwareVolumeListener hardwareVolumeListener
    ) {
        mAudioManager = audioManager;
        mHardwareVolumeListener = hardwareVolumeListener;
    }

    public int getLastVolume() {
        return mLastVolume;
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
            mLastVolume = -1;
        }
        applyFrameworkRoute(enabled && output == AudioOutput.SPEAKER);
        if (enabled) {
            if (updateVolume) {
                applyVolume();
            }
            sendAudioParameter("g_fmradio_mute", "true");
            sendAudioParameter("g_fmradio_mute", "false");
            sendAudioParameter("g_fmradio_enable", "true");
            return;
        }
        sendAudioParameter("g_fmradio_mute", "true");
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
            if (scaled == mLastVolume) {
                return;
            }
            mLastVolume = scaled;
            Log.d(TAG, "applyVolume(exynos): index=" + volumeIndex + "/" + maxVolume + " scaled=" + scaled);
            if (mHardwareVolumeListener != null) {
                mHardwareVolumeListener.onVolumeChanged(scaled);
            }
        } catch (Throwable t) {
            Log.e(TAG, "applyVolume failed", t);
        }
    }

    private void applyFrameworkRoute(final boolean speakerEnabled) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (speakerEnabled) {
                    for (final AudioDeviceInfo device : mAudioManager.getAvailableCommunicationDevices()) {
                        if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                            mAudioManager.setCommunicationDevice(device);
                            return;
                        }
                    }
                } else {
                    mAudioManager.clearCommunicationDevice();
                }
            }
            mAudioManager.setSpeakerphoneOn(speakerEnabled);
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
