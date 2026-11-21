package com.vlad805.fmradio.service.audio;

import android.annotation.SuppressLint;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.util.Log;

import com.vlad805.fmradio.helper.Audio;

public final class QualcommRoutedAudio implements RoutedAudioPolicy {
    private static final String TAG = "QFm2Audio";

    static final int DEFAULT_DEVICE_OUT_FM = 0;
    static final int DEFAULT_DEVICE_OUT_SPEAKER = 0x2;
    static final int DEFAULT_DEVICE_OUT_WIRED_HEADSET = 0x4;

    interface ParameterSink {
        String getParameters(String keys);

        void setParameters(String keyValuePairs);
    }

    interface FrameworkRouteSink {
        void applyFrameworkRoute(AudioOutput output);
    }

    interface ForceUseSink {
        boolean isForceSpeakerNow();

        void setForceSpeaker(boolean enabled);
    }

    interface VolumeGainProvider {
        float resolveMusicStreamGain(int volumeDeviceType);
    }

    private final ParameterSink mParameterSink;
    private final FrameworkRouteSink mFrameworkRouteSink;
    private final ForceUseSink mForceUseSink;
    private final VolumeGainProvider mVolumeGainProvider;
    private final Runnable mRefreshAudioRoute;
    private final boolean mUseForceUse;
    private final int mDeviceOutFm;
    private final int mDeviceOutSpeaker;
    private final int mDeviceOutWiredHeadset;

    public QualcommRoutedAudio(
            final AudioManager audioManager,
            final boolean useForceUse,
            final Runnable refreshAudioRoute
    ) {
        this(
                createParameterSink(audioManager),
                createFrameworkRouteSink(audioManager),
                createForceUseSink(),
                createVolumeGainProvider(audioManager),
                refreshAudioRoute,
                useForceUse,
                resolveAudioSystemDevice("DEVICE_OUT_FM", DEFAULT_DEVICE_OUT_FM),
                resolveAudioSystemDevice("DEVICE_OUT_SPEAKER", DEFAULT_DEVICE_OUT_SPEAKER),
                resolveAudioSystemDevice("DEVICE_OUT_WIRED_HEADSET", DEFAULT_DEVICE_OUT_WIRED_HEADSET)
        );
    }

    QualcommRoutedAudio(
            final ParameterSink parameterSink,
            final FrameworkRouteSink frameworkRouteSink,
            final ForceUseSink forceUseSink,
            final VolumeGainProvider volumeGainProvider,
            final Runnable refreshAudioRoute,
            final boolean useForceUse,
            final int deviceOutFm,
            final int deviceOutSpeaker,
            final int deviceOutWiredHeadset
    ) {
        mParameterSink = parameterSink;
        mFrameworkRouteSink = frameworkRouteSink;
        mForceUseSink = forceUseSink;
        mVolumeGainProvider = volumeGainProvider;
        mRefreshAudioRoute = refreshAudioRoute != null ? refreshAudioRoute : () -> {};
        mUseForceUse = useForceUse;
        mDeviceOutFm = deviceOutFm;
        mDeviceOutSpeaker = deviceOutSpeaker;
        mDeviceOutWiredHeadset = deviceOutWiredHeadset;
    }

    @Override
    public void applyRoute(final boolean enabled, final AudioOutput output, final boolean updateVolume) {
        if (mParameterSink == null) {
            logError("applyRoute: AudioManager is null", null);
            return;
        }

        final boolean speaker = output == AudioOutput.SPEAKER;
        final int routeDevice = speaker ? mDeviceOutSpeaker : mDeviceOutWiredHeadset;
        final int volumeDeviceType = speaker
                ? AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                : AudioDeviceInfo.TYPE_WIRED_HEADPHONES;
        final int route = enabled ? (routeDevice | mDeviceOutFm) : routeDevice;
        logDebug("applyRoute: enable=" + enabled + " output=" + output
                + " routeDevice=" + routeDevice + " deviceOutFm=" + mDeviceOutFm + " route=" + route);
        mFrameworkRouteSink.applyFrameworkRoute(output);

        if (enabled) {
            final String fmStatus = mParameterSink.getParameters("fm_status");
            logDebug("applyRoute: fm_status=" + fmStatus);
            if (fmStatus != null && fmStatus.contains("1")) {
                // AudioManager routing parameters use AudioSystem.DEVICE_OUT_* masks, not AudioDeviceInfo.TYPE_* constants.
                final int resetRoute = mDeviceOutWiredHeadset | mDeviceOutFm;
                logDebug("applyRoute: FM hardwareLoopback already active, resetting via fm_routing=" + resetRoute);
                sendAudioParameter("fm_routing", resetRoute);
            }
            if (updateVolume) {
                applyVolume(volumeDeviceType);
            }
            sendAudioParameter("fm_mute", 0);
            sendAudioParameter("fm_routing", route);
            sendAudioParameter("handle_fm", route);
            return;
        }

        sendAudioParameter("fm_routing", route);
        sendAudioParameter("handle_fm", route);
    }

    @Override
    public void onMusicVolumeChanged(final AudioOutput output) {
        if (mParameterSink == null) {
            return;
        }
        final int device = output == AudioOutput.SPEAKER
                ? AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                : AudioDeviceInfo.TYPE_WIRED_HEADPHONES;
        logDebug("onVolumeChanged: refreshing fm_volume for device=" + device);
        applyVolume(device);
    }

    @Override
    public AudioOutput getOutput(final AudioOutput requestedOutput) {
        if (mUseForceUse) {
            return mForceUseSink.isForceSpeakerNow() ? AudioOutput.SPEAKER : AudioOutput.HEADPHONES;
        }
        return requestedOutput;
    }

    @Override
    public void afterOutputChanged(final AudioOutput output) {
        mRefreshAudioRoute.run();
        if (mUseForceUse) {
            mForceUseSink.setForceSpeaker(output == AudioOutput.SPEAKER);
        }
    }

    private void applyVolume(final int device) {
        try {
            final float volume = mVolumeGainProvider.resolveMusicStreamGain(device);
            sendAudioParameter("fm_volume", String.valueOf(volume));
        } catch (Throwable t) {
            logError("applyVolume failed", t);
        }
    }

    private void sendAudioParameter(final String key, final int value) {
        sendAudioParameter(key, String.valueOf(value));
    }

    private void sendAudioParameter(final String key, final String value) {
        final String pair = key + "=" + value;
        try {
            logDebug("setParameters: " + pair);
            mParameterSink.setParameters(pair);
        } catch (Throwable t) {
            logError("setParameters failed: " + pair, t);
        }
    }

    private static void logDebug(final String message) {
        try {
            Log.d(TAG, message);
        } catch (RuntimeException ignored) {
            // Host JVM unit tests run against unmocked android.util.Log stubs.
        }
    }

    private static void logError(final String message, final Throwable error) {
        try {
            if (error != null) {
                Log.e(TAG, message, error);
            } else {
                Log.e(TAG, message);
            }
        } catch (RuntimeException ignored) {
            // Host JVM unit tests run against unmocked android.util.Log stubs.
        }
    }

    private static ParameterSink createParameterSink(final AudioManager audioManager) {
        if (audioManager == null) {
            return null;
        }
        return new ParameterSink() {
            @Override
            public String getParameters(final String keys) {
                return audioManager.getParameters(keys);
            }

            @Override
            public void setParameters(final String keyValuePairs) {
                audioManager.setParameters(keyValuePairs);
            }
        };
    }

    private static FrameworkRouteSink createFrameworkRouteSink(final AudioManager audioManager) {
        return output -> {
            if (audioManager == null) {
                return;
            }
            final boolean speakerEnabled = output == AudioOutput.SPEAKER;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (speakerEnabled) {
                        for (final AudioDeviceInfo device : audioManager.getAvailableCommunicationDevices()) {
                            if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                                audioManager.setCommunicationDevice(device);
                                return;
                            }
                        }
                    } else {
                        audioManager.clearCommunicationDevice();
                    }
                }
                audioManager.setSpeakerphoneOn(speakerEnabled);
            } catch (Throwable t) {
                Log.w(TAG, "applyFrameworkRoute failed for output=" + output, t);
            }
        };
    }

    private static ForceUseSink createForceUseSink() {
        return new ForceUseSink() {
            @Override
            public boolean isForceSpeakerNow() {
                return Audio.isForceSpeakerNow();
            }

            @Override
            public void setForceSpeaker(final boolean enabled) {
                Audio.toggleThroughSpeaker(enabled);
            }
        };
    }

    private static VolumeGainProvider createVolumeGainProvider(final AudioManager audioManager) {
        return device -> {
            if (audioManager == null) {
                return 0f;
            }
            final int volumeIndex = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            final float volume = resolveMusicStreamGain(audioManager, volumeIndex, device);
            Log.d(TAG, "applyVolume: index=" + volumeIndex + " volume=" + volume);
            return volume;
        };
    }

    private static float resolveMusicStreamGain(
            final AudioManager audioManager,
            final int volumeIndex,
            final int device
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            final float decibels = audioManager.getStreamVolumeDb(AudioManager.STREAM_MUSIC, volumeIndex, device);
            final float volume = (float) Math.exp(decibels * 0.115129f);
            Log.d(TAG, "resolveMusicStreamGain: dB=" + decibels);
            return volume;
        }

        final int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        if (maxVolume <= 0) {
            return 0f;
        }

        return Math.max(0f, Math.min(1f, volumeIndex / (float) maxVolume));
    }

    @SuppressLint("PrivateApi")
    private static int resolveAudioSystemDevice(final String fieldName, final int fallbackValue) {
        try {
            final Class<?> audioSystemClass = Class.forName("android.media.AudioSystem");
            return audioSystemClass.getField(fieldName).getInt(null);
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
            Log.e(TAG, "resolveAudioSystemDevice failed for " + fieldName + ", fallback=" + fallbackValue, e);
            return fallbackValue;
        }
    }
}
