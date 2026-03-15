package com.vlad805.fmradio.service.fm.implementation;

import android.content.Context;
import com.vlad805.fmradio.BuildConfig;
import com.vlad805.fmradio.C;
import com.vlad805.fmradio.Storage;
import com.vlad805.fmradio.Utils;
import com.vlad805.fmradio.enums.MuteState;
import com.vlad805.fmradio.service.fm.DatagramServer;
import com.vlad805.fmradio.service.fm.FMEventCallback;
import com.vlad805.fmradio.service.fm.IFMEventListener;
import com.vlad805.fmradio.service.fm.LaunchBinaryConfig;
import com.vlad805.fmradio.service.fm.communication.Poll;
import com.vlad805.fmradio.service.fm.communication.Request;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class QualcommHal extends AbstractFMController implements IFMEventListener {
    private static final LaunchBinaryConfig CONFIG = new LaunchBinaryConfig(2112, 2113);
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

    private DatagramServer mServer;
    private FMEventCallback mEventCallback;
    private final Poll mCommandPoll;
    private int mLastRequestedFrequencyKhz = Integer.MIN_VALUE;
    private long mLastRequestedFrequencyAtMs = 0L;

    public QualcommHal(final Context context) {
        super(CONFIG, context);
        mCommandPoll = new Poll(CONFIG);
    }

    @Override
    public void setEventListener(final FMEventCallback callback) {
        mEventCallback = callback;
    }

    private String getBinaryName() {
        return "fmbin-fm2-" + Utils.determineArch();
    }

    private String getBinaryPath() {
        return getAppRootPath(getBinaryName());
    }

    @Override
    public boolean isInstalled() {
        return new File(getBinaryPath()).exists();
    }

    @Override
    public boolean isObsolete() {
        final int binaryVersion = Storage.getInstance(context).getInt(C.PrefKey.BINARY_VERSION_FM2, 0);
        return BuildConfig.DEBUG || binaryVersion < BuildConfig.VERSION_CODE;
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    @Override
    protected void installImpl(final Callback<Void> callback) {
        File dir = new File(getAppRootPath());
        if (!dir.exists()) {
            dir.mkdirs();
        }

        try {
            if (!copyBinary()) {
                callback.onError(new Error("Error while copy binary"));
                return;
            }
        } catch (final FileNotFoundException e) {
            Utils.shell("killall " + getBinaryName(), true);
            try {
                if (!copyBinary()) {
                    callback.onError(new Error("Error while copy binary after kill busy binary"));
                    return;
                }
            } catch (final FileNotFoundException e2) {
                callback.onError(new Error("Error while copy binary after kill busy binary"));
                return;
            }
        }

        Storage.getInstance(context).put(C.PrefKey.BINARY_VERSION_FM2, BuildConfig.VERSION_CODE);
        Utils.shell("chmod 777 " + getBinaryPath() + " 1>/dev/null 2>/dev/null", true);
        callback.onResult(null);
    }

    @Override
    protected void launchImpl(final Callback<Void> callback) {
        if (mServer != null) {
            mServer.closeServer();
            mServer = null;
        }

        Utils.shell(String.format("killall %1$s 1>/dev/null 2>/dev/null &", getBinaryName()), true);
        Utils.sleep(150);
        Utils.shell(String.format("sh -c '%s 2>&1 | log -t RFM-FM2' &", getBinaryPath()), true);
        mCommandPoll.toggle(true);
        startServerListener();
        Utils.sleep(300);
        sendCommand(new Request("init", 5000).onResponse(data -> callback.onResult(null)));
    }

    @Override
    protected void applyPreferenceImpl(final String key, final String value) {
        switch (key) {
            case C.PrefKey.RDS_ENABLE:
                sendCommand(new Request("rds_toggle " + value));
                break;
            case C.PrefKey.TUNER_SPACING:
                sendCommand(new Request("set_spacing " + value));
                break;
            case C.PrefKey.TUNER_REGION:
                sendCommand(new Request("set_region " + value));
                break;
            case C.PrefKey.TUNER_STEREO:
                sendCommand(new Request("set_stereo " + value));
                break;
            case C.PrefKey.TUNER_ANTENNA:
                sendCommand(new Request("set_antenna " + value));
                break;
            case C.PrefKey.TUNER_POWER_MODE:
                sendCommand(new Request("power_mode " + value));
                break;
            case C.PrefKey.RDS_AUTO_AF:
                sendCommand(new Request("auto_af " + value));
                break;
        }
    }

    @Override
    protected void killImpl(final Callback<Void> callback) {
        if (mServer != null) {
            mServer.closeServer();
        }

        Utils.shell(String.format("killall %1$s 1>/dev/null 2>/dev/null &", getBinaryName()), true);
        callback.onResult(null);
    }

    @Override
    protected void enableImpl(final Callback<Void> callback) {
        sendCommand(new Request("enable", 5000).onResponse(result -> callback.onResult(null)));
    }

    @Override
    protected boolean shouldApplyStartupPreferences() {
        // Keep FM2 startup as close to stock FM2 as possible: enable, wait for enabled event,
        // then tune. Preference changes are still applied later via explicit preference updates.
        return false;
    }

    @Override
    protected void disableImpl(final Callback<Void> callback) {
        sendCommand(new Request("disable", 5000).onResponse(result -> callback.onResult(null)));
        mCommandPoll.toggle(false);
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

    @Override
    protected void jumpImpl(final int direction, final Callback<Integer> callback) {
        sendCommand(new Request("jump " + direction, 1500).onResponse(data -> callback.onResult(Utils.parseInt(data))));
    }

    @Override
    protected void hwSeekImpl(final int direction, final Callback<Integer> callback) {
        sendCommand(new Request("seekhw " + direction, 4000).onResponse(data -> callback.onResult(Utils.parseInt(data))));
    }

    @Override
    protected void setPowerModeImpl(final String mode) {
        sendCommand(new Request("power_mode " + mode));
    }

    public void setSlimbusEnabled(final boolean enabled) {
        sendCommand(new Request("slimbus " + (enabled ? 1 : 0), 2000));
    }

    public void refreshAudioRoute() {
        sendCommand(new Request("slimbus 0", 2000));
        sendCommand(new Request("slimbus 1", 2000));
    }

    @Override
    public void setMute(final MuteState state, final Callback<Void> callback) {
    }

    @Override
    public void hwSearchImpl() {
        sendCommand(new Request("searchhw", 60000));
    }

    private boolean copyAsset(Context context, String fromAssetPath, String toPath) throws FileNotFoundException {
        try {
            InputStream in = context.getAssets().open(fromAssetPath);
            final File targetPath = new File(toPath);
            if (targetPath.exists()) {
                targetPath.delete();
            }

            targetPath.createNewFile();
            OutputStream out = new FileOutputStream(toPath);
            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }

            in.close();
            out.flush();
            out.close();
            return true;
        } catch (IOException e) {
            if (e instanceof FileNotFoundException) {
                throw (FileNotFoundException) e;
            }
            e.printStackTrace();
            return false;
        }
    }

    private boolean copyBinary() throws FileNotFoundException {
        return copyAsset(context, getBinaryName(), getBinaryPath());
    }

    private void startServerListener() {
        try {
            mServer = new DatagramServer(config.serverPort);
            mServer.setCallback((event, bundle) -> {
                if (C.Event.FREQUENCY_SET.equals(event)) {
                    sendCommand(new Request("set_stereo 1"));
                }
                if (mEventCallback != null) {
                    mEventCallback.onEvent(event, bundle);
                }
            });
            mServer.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void sendCommand(final Request request) {
        mCommandPoll.send(request);
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
