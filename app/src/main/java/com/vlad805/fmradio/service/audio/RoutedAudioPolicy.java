package com.vlad805.fmradio.service.audio;

public interface RoutedAudioPolicy {
    void applyRoute(boolean enabled, AudioOutput output, boolean updateVolume);

    void onMusicVolumeChanged(AudioOutput output);

    AudioOutput getOutput(AudioOutput requestedOutput);

    void afterOutputChanged(AudioOutput output);
}
