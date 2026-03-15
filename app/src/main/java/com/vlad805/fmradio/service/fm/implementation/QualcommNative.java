package com.vlad805.fmradio.service.fm.implementation;

import android.content.Context;
import android.os.Bundle;
import com.vlad805.fmradio.C;
import com.vlad805.fmradio.Utils;
import com.vlad805.fmradio.enums.TunerDriver;
import com.vlad805.fmradio.service.fm.communication.Request;

import java.io.File;

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
        Utils.shell(String.format("sh -c '%s 2>&1 | log -t RFM-QCOM' &", getBinaryPath()), true);
        toggleCommandPoll(true);
        startServerListener();
        Utils.sleep(300);
        sendCommand(new Request("init", 5000).onResponse(data -> callback.onResult(null)));
    }

    @Override
    protected boolean shouldApplyStartupPreferences() {
        return mDriverKind != TunerDriver.HAL;
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

    @Override
    protected void onServerEvent(final String event, final Bundle bundle) {
        if (mDriverKind == TunerDriver.HAL && C.Event.FREQUENCY_SET.equals(event)) {
            sendCommand(new Request("set_stereo 1"));
        }
    }

    public int getRecorderSampleRate() {
        // HAL - 48kHz
        // Legacy - 44.1kHz
        return mDriverKind == TunerDriver.HAL ? 48000 : 44100;
    }

    public void refreshAudioRoute() {
        if (mDriverKind != TunerDriver.HAL) {
            return;
        }

        sendCommand(new Request("slimbus 0", 2000));
        sendCommand(new Request("slimbus 1", 2000));
    }
}
