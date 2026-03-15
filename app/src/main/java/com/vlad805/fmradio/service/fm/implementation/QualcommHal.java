package com.vlad805.fmradio.service.fm.implementation;

import android.content.Context;
import android.os.Bundle;
import com.vlad805.fmradio.C;
import com.vlad805.fmradio.Utils;
import com.vlad805.fmradio.service.fm.communication.Request;

import java.io.File;

public class QualcommHal extends AbstractQualcommNativeController {
    private static final long DUPLICATE_TUNE_WINDOW_MS = 1500L;

    private static final String[] FM_HELIUM_PATHS = {
            "/vendor/lib64/fm_helium.so",
            "/vendor/lib/fm_helium.so",
            "/system/vendor/lib64/fm_helium.so",
            "/system/vendor/lib/fm_helium.so",
            "/system_ext/lib64/fm_helium.so",
            "/system_ext/lib/fm_helium.so",
            "/odm/lib64/fm_helium.so",
            "/odm/lib/fm_helium.so"
    };

    private int mLastRequestedFrequencyKhz = Integer.MIN_VALUE;
    private long mLastRequestedFrequencyAtMs = 0L;

    public QualcommHal(final Context context) {
        super(context);
    }

    @Override
    protected String getBinaryName() {
        return "fmbin-fm2-" + Utils.determineArch();
    }

    @Override
    protected String getBinaryVersionKey() {
        return C.PrefKey.BINARY_VERSION_FM2;
    }

    @Override
    protected void launchImpl(final Callback<Void> callback) {
        closeServerListener();
        killRunningBinary();
        Utils.sleep(150);
        Utils.shell(String.format("sh -c '%s 2>&1 | log -t RFM-FM2' &", getBinaryPath()), true);
        toggleCommandPoll(true);
        startServerListener();
        Utils.sleep(300);
        sendCommand(new Request("init", 5000).onResponse(data -> callback.onResult(null)));
    }

    @Override
    protected boolean shouldApplyStartupPreferences() {
        // Keep FM2 startup as close to stock FM2 as possible: enable, wait for enabled event,
        // then tune. Preference changes are still applied later via explicit preference updates.
        return false;
    }

    @Override
    protected void setFrequencyImpl(final int kHz, final Callback<Integer> callback) {
        final long now = System.currentTimeMillis();
        if (mLastRequestedFrequencyKhz == kHz && now - mLastRequestedFrequencyAtMs < DUPLICATE_TUNE_WINDOW_MS) {
            callback.onResult(kHz);
            return;
        }

        mLastRequestedFrequencyKhz = kHz;
        mLastRequestedFrequencyAtMs = now;
        sendCommand(new Request("setfreq " + kHz).onResponse(data -> callback.onResult(kHz)));
    }

    public void setSlimbusEnabled(final boolean enabled) {
        sendCommand(new Request("slimbus " + (enabled ? 1 : 0), 2000));
    }

    public void refreshAudioRoute() {
        sendCommand(new Request("slimbus 0", 2000));
        sendCommand(new Request("slimbus 1", 2000));
    }

    @Override
    protected void onServerEvent(final String event, final Bundle bundle) {
        if (C.Event.FREQUENCY_SET.equals(event)) {
            sendCommand(new Request("set_stereo 1"));
        }
    }

    public static boolean isAbleToWork() {
        for (final String path : FM_HELIUM_PATHS) {
            if (new File(path).exists()) {
                return true;
            }
        }

        return false;
    }
}
