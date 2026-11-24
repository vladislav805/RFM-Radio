package com.vlad805.fmradio.service.audio;

import android.os.Build;
import com.vlad805.fmradio.enums.TunerDriver;
import com.vlad805.fmradio.service.recording.PcmFormat;

public final class AudioSelection {
    public static final int API_ROUTED_MIN = 21;

    public enum Path {
        NONE,
        PCM_LOOPBACK,
        QUALCOMM_ROUTED,
        SAMSUNG_ROUTED
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

        final Path path;
        if (sdkInt >= API_ROUTED_MIN) {
            path = driver == TunerDriver.V4L2_EXYNOS ? Path.SAMSUNG_ROUTED : Path.QUALCOMM_ROUTED;
        } else {
            path = Path.PCM_LOOPBACK;
        }
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
        return (mPath == Path.QUALCOMM_ROUTED || mPath == Path.SAMSUNG_ROUTED)
                ? PcmFormat.PCM16_STEREO_48000
                : PcmFormat.PCM16_STEREO_44100;
    }
}
