package com.vlad805.fmradio.service.audio;

import android.os.Build;
import com.vlad805.fmradio.enums.TunerDriver;
import com.vlad805.fmradio.service.recording.PcmFormat;

public final class AudioSelection {
    public static final int API_ROUTED_MIN = 26;

    public enum Path {
        NONE,
        PCM_LOOPBACK,
        QUALCOMM_ROUTED
    }

    private final Path mPath;
    private final boolean mStartsBeforeTuner;

    private AudioSelection(final Path path, final boolean startsBeforeTuner) {
        mPath = path;
        mStartsBeforeTuner = startsBeforeTuner;
    }

    public static AudioSelection resolve(final TunerDriver driver) {
        return resolve(driver, Build.VERSION.SDK_INT);
    }

    public static AudioSelection resolve(final TunerDriver driver, final int sdkInt) {
        if (driver == null || driver == TunerDriver.NONE) {
            return new AudioSelection(Path.NONE, false);
        }

        final Path path = sdkInt >= API_ROUTED_MIN ? Path.QUALCOMM_ROUTED : Path.PCM_LOOPBACK;
        final boolean startsBeforeTuner = driver == TunerDriver.HAL_HELIUM;
        return new AudioSelection(path, startsBeforeTuner);
    }

    public Path path() {
        return mPath;
    }

    public boolean startsBeforeTuner() {
        return mStartsBeforeTuner;
    }

    public PcmFormat pcmFormat() {
        return mPath == Path.QUALCOMM_ROUTED
                ? PcmFormat.PCM16_STEREO_48000
                : PcmFormat.PCM16_STEREO_44100;
    }
}
