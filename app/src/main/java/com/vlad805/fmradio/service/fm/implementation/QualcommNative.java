package com.vlad805.fmradio.service.fm.implementation;

import android.content.Context;
import com.vlad805.fmradio.C;
import com.vlad805.fmradio.Storage;
import com.vlad805.fmradio.Utils;
import com.vlad805.fmradio.enums.TunerDriver;
import com.vlad805.fmradio.preferences.BandUtils;
import com.vlad805.fmradio.service.fm.communication.Request;

import java.io.File;
import java.util.Locale;

public class QualcommNative extends AbstractQualcommNativeController {
    private static final long DUPLICATE_TUNE_WINDOW_MS = 1500L;

    private final TunerDriver mDriverKind;
    private int mLastRequestedFrequencyKhz = Integer.MIN_VALUE;
    private long mLastRequestedFrequencyAtMs = 0L;

    public QualcommNative(final Context context, final TunerDriver driverKind) {
        super(context);
        mDriverKind = driverKind;
    }

    @Override
    protected String getBinaryName() {
        return "fmbin-" + Utils.determineArch();
    }

    @Override
    protected String getBinaryVersionKey() {
        return C.PrefKey.BINARY_VERSION_NATIVE;
    }

    @Override
    protected void launchImpl(final Callback<Void> callback) {
        closeServerListener();
        killRunningBinary();
        Utils.sleep(150);
        Utils.shell(String.format("sh -c '%s 2>&1 | while IFS= read -r line; do log -t RFM-QCOM \"$line\"; done' &", getBinaryPath()), true);
        toggleCommandPoll(true);
        startServerListener();
        Utils.sleep(300);
        sendCommand(new Request("init", 5000).onResponse(data -> {
            if (isOkResponse(data)) {
                callback.onResult(null);
            } else {
                callback.onError(new Error("Failed to initialize tuner: " + data));
            }
        }));
    }

    @Override
    protected void enableImpl(final Callback<Void> callback) {
        final int region = Storage.getPrefInt(context, C.PrefKey.TUNER_REGION, C.PrefDefaultValue.TUNER_REGION);
        final int frequency = getStartupFrequency(region);
        final int spacing = Storage.getPrefInt(context, C.PrefKey.TUNER_SPACING, C.PrefDefaultValue.TUNER_SPACING);
        final boolean stereo = Storage.getPrefBoolean(context, C.PrefKey.TUNER_STEREO, C.PrefDefaultValue.TUNER_STEREO);
        final int antenna = Storage.getPrefInt(context, C.PrefKey.TUNER_ANTENNA, C.PrefDefaultValue.TUNER_ANTENNA);
        final boolean autoAf = Storage.getPrefBoolean(context, C.PrefKey.RDS_AUTO_AF, C.PrefDefaultValue.RDS_AUTO_AF);

        final String command = String.format(
                Locale.US,
                "enable freq=%d region=%s spacing=%d stereo=%d antenna=%d af=%d",
                frequency,
                regionName(region),
                spacingKhz(spacing),
                stereo ? 1 : 0,
                antenna,
                autoAf ? 1 : 0
        );
        sendCommand(new Request(command, 5000).onResponse(result -> {
            if (!isOkResponse(result)) {
                callback.onError(new Error("Failed to enable tuner: " + result));
                return;
            }
            callback.onResult(null);
        }));
    }

    @Override
    protected boolean shouldApplyStartupPreferences() {
        return false;
    }

    @Override
    protected void applyPreferenceImpl(final String key, final String value) {
        switch (key) {
            case C.PrefKey.TUNER_REGION:
                sendCommand(new Request("set_region " + regionName(Utils.parseInt(value))));
                break;
            case C.PrefKey.TUNER_SPACING:
                sendCommand(new Request("set_spacing " + spacingKhz(Utils.parseInt(value))));
                break;
            default:
                super.applyPreferenceImpl(key, value);
                break;
        }
    }

    @Override
    protected void onApplyAntennaPreference(final String value) {
        sendCommand(new Request("set_antenna " + value).onResponse(str -> {
            if (str.startsWith("ERR_UNV_ANT")) {
                fireEvent(C.Event.ERROR_INVALID_ANTENNA);
            }
        }));
    }

    @Override
    protected void setFrequencyImpl(final int kHz, final Callback<Integer> callback) {
        if (mDriverKind == TunerDriver.HAL) {
            final long now = System.currentTimeMillis();
            if (mLastRequestedFrequencyKhz == kHz && now - mLastRequestedFrequencyAtMs < DUPLICATE_TUNE_WINDOW_MS) {
                callback.onResult(kHz);
                return;
            }

            mLastRequestedFrequencyKhz = kHz;
            mLastRequestedFrequencyAtMs = now;
        }

        sendCommand(new Request("setfreq " + kHz).onResponse(data -> callback.onResult(kHz)));
    }

    public int getRecorderSampleRate() {
        // HAL - 48kHz
        // Legacy - 44.1kHz
        return mDriverKind == TunerDriver.HAL ? 48000 : 44100;
    }

    public void refreshAudioRoute() {
        if (mDriverKind == TunerDriver.HAL) {
            sendCommand(new Request("slimbus 0", 2000));
            sendCommand(new Request("slimbus 1", 2000));
        }
    }

    private static String regionName(final int region) {
        switch (region) {
            case 2:
                return "jp";
            case 3:
                return "jp_wide";
            case 4:
                return "us";
            case 1:
            default:
                return "eu";
        }
    }

    private static int spacingKhz(final int spacing) {
        switch (spacing) {
            case 1:
                return 50;
            case 3:
                return 200;
            case 2:
            default:
                return 100;
        }
    }

    private static boolean isOkResponse(final String response) {
        return response != null && response.startsWith("ok");
    }

    private int getStartupFrequency(final int region) {
        final int savedFrequency = Storage.getInstance(context).getInt(
                C.PrefKey.LAST_FREQUENCY,
                C.PrefDefaultValue.LAST_FREQUENCY
        );
        final BandUtils.BandLimit band = BandUtils.getBandLimit(region);

        return Math.max(band.lower, Math.min(savedFrequency, band.upper));
    }
}
