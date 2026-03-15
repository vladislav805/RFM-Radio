package com.vlad805.fmradio.service.fm.implementation;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import com.vlad805.fmradio.BuildConfig;
import com.vlad805.fmradio.C;
import com.vlad805.fmradio.Storage;
import com.vlad805.fmradio.Utils;
import com.vlad805.fmradio.service.fm.DatagramServer;
import com.vlad805.fmradio.service.fm.IFMController;
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

public abstract class AbstractQualcommNativeController implements IFMController, IFMEventListener {
    public interface Callback<T> {
        void onResult(T result);

        default void onError(final Error e) {
        }
    }

    protected final LaunchBinaryConfig config;
    protected final Context context;

    private DatagramServer mServer;
    private FMEventCallback mEventCallback;
    private final Poll mCommandPoll;

    protected AbstractQualcommNativeController(final LaunchBinaryConfig config, final Context context) {
        this.config = config;
        this.context = context;
        mCommandPoll = new Poll(config);
    }

    @SuppressLint("SdCardPath")
    public final String getAppRootPath() {
        return "/data/data/" + BuildConfig.APPLICATION_ID + "/files/";
    }

    public final String getAppRootPath(final String path) {
        return getAppRootPath() + path;
    }

    @Override
    public void setEventListener(final FMEventCallback callback) {
        mEventCallback = callback;
    }

    public boolean isInstalled() {
        return isNativeBinaryInstalled();
    }

    public boolean isObsolete() {
        return isNativeBinaryObsolete();
    }

    protected void installImpl(final Callback<Void> callback) {
        installNativeBinary(callback);
    }

    public final void install() {
        fireEvent(C.Event.INSTALLING);
        installImpl(result -> fireEvent(C.Event.INSTALLED));
    }

    protected abstract void launchImpl(final Callback<Void> callback);

    public final void launch() {
        fireEvent(C.Event.LAUNCHING);
        launchImpl(new Callback<>() {
            @Override
            public void onResult(final Void result) {
                fireEvent(C.Event.LAUNCHED);
            }

            @Override
            public void onError(final Error e) {
                fireEvent(C.Event.LAUNCH_FAILED);
            }
        });
    }

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
                onApplyAntennaPreference(value);
                break;
            case C.PrefKey.TUNER_POWER_MODE:
                sendCommand(new Request("power_mode " + value));
                break;
            case C.PrefKey.RDS_AUTO_AF:
                sendCommand(new Request("auto_af " + value));
                break;
        }
    }

    public final void applyPreference(final String key, final String value) {
        applyPreferenceImpl(key, value);
    }

    public final void prepareBinary() {
        fireEvent(C.Event.PREPARING);
        if (isInstalled() && !isObsolete()) {
            fireEvent(C.Event.INSTALLED);
            return;
        }

        install();
    }

    protected void killImpl(final Callback<Void> callback) {
        closeServerListener();
        callback.onResult(null);
    }

    public final void kill() {
        killImpl(result -> {
            fireEvent(C.Event.DISABLED);
            fireEvent(C.Event.KILLED);
        });
    }

    protected void enableImpl(final Callback<Void> callback) {
        sendCommand(new Request("enable", 5000).onResponse(result -> callback.onResult(null)));
    }

    protected boolean shouldApplyStartupPreferences() {
        return true;
    }

    public final void enable() {
        fireEvent(C.Event.ENABLING);
        enableImpl(result -> {
            maybeApplyStartupPreferences();
            fireEvent(C.Event.ENABLED);
        });
    }

    private void maybeApplyStartupPreferences() {
        if (!shouldApplyStartupPreferences()) {
            return;
        }

        setupTunerByPreferences(new String[]{
                C.PrefKey.RDS_ENABLE,
                C.PrefKey.TUNER_REGION,
                C.PrefKey.TUNER_SPACING,
                C.PrefKey.TUNER_STEREO,
                C.PrefKey.TUNER_ANTENNA,
        });
    }

    public void setupTunerByPreferences(final String[] changed) {
        for (final String key : changed) {
            String value = null;
            switch (key) {
                case C.PrefKey.RDS_ENABLE: {
                    final boolean isRdsEnabled = Storage.getPrefBoolean(context, key, true);
                    value = isRdsEnabled ? "1" : "0";
                    break;
                }
                case C.PrefKey.TUNER_REGION: {
                    final int band = Storage.getPrefInt(context, key, C.PrefDefaultValue.TUNER_REGION);
                    value = String.valueOf(band);
                    break;
                }
                case C.PrefKey.TUNER_STEREO: {
                    final boolean stereo = Storage.getPrefBoolean(context, key, C.PrefDefaultValue.TUNER_STEREO);
                    value = stereo ? "1" : "0";
                    break;
                }
                case C.PrefKey.TUNER_SPACING: {
                    final int spacing = Storage.getPrefInt(context, key, C.PrefDefaultValue.TUNER_SPACING);
                    applyPreference(key, String.valueOf(spacing));
                    final boolean stereo = Storage.getPrefBoolean(context, C.PrefKey.TUNER_STEREO, C.PrefDefaultValue.TUNER_STEREO);
                    applyPreference(C.PrefKey.TUNER_STEREO, stereo ? "1" : "0");
                    break;
                }
                case C.PrefKey.TUNER_ANTENNA: {
                    final int region = Storage.getPrefInt(context, key, C.PrefDefaultValue.TUNER_ANTENNA);
                    value = String.valueOf(region);
                    break;
                }
                case C.PrefKey.TUNER_POWER_MODE: {
                    final boolean isLowPower = Storage.getPrefBoolean(context, key, C.PrefDefaultValue.TUNER_POWER_MODE);
                    value = isLowPower ? "low" : "normal";
                    break;
                }
                case C.PrefKey.RDS_AUTO_AF: {
                    final boolean enabled = Storage.getPrefBoolean(context, key, C.PrefDefaultValue.RDS_AUTO_AF);
                    value = enabled ? "1" : "0";
                    break;
                }
            }

            if (value != null) {
                applyPreference(key, value);
            }
        }
    }

    protected void disableImpl(final Callback<Void> callback) {
        sendCommand(new Request("disable", 5000).onResponse(result -> callback.onResult(null)));
        mCommandPoll.toggle(false);
    }

    public final void disable() {
        fireEvent(C.Event.DISABLING);
        disableImpl(result -> fireEvent(C.Event.DISABLED));
    }

    protected abstract void setFrequencyImpl(final int kHz, final Callback<Integer> callback);

    public final void setFrequency(final int kHz) {
        setFrequencyImpl(kHz, result -> {
            final Bundle bundle = new Bundle();
            bundle.putInt(C.Key.FREQUENCY, kHz);
            fireEvent(C.Event.FREQUENCY_SET, bundle);
        });
    }

    protected void jumpImpl(final int direction, final Callback<Integer> callback) {
        sendCommand(new Request("jump " + direction, 1500).onResponse(data -> callback.onResult(Utils.parseInt(data))));
    }

    public final void jump(final int direction) {
        jumpImpl(direction, result -> {
            if (result < 0) {
                fireError("Error while jumping");
                return;
            }
            fireFrequencyEvent(C.Event.JUMP_COMPLETE, result);
        });
    }

    protected void hwSeekImpl(final int direction, final Callback<Integer> callback) {
        sendCommand(new Request("seekhw " + direction, 4000).onResponse(data -> callback.onResult(Utils.parseInt(data))));
    }

    public void hwSeek(final int direction) {
        hwSeekImpl(direction, result -> {
            if (result < 0) {
                fireError("Error while hwSeek");
                return;
            }
            fireFrequencyEvent(C.Event.HW_SEEK_COMPLETE, result);
        });
    }

    protected void hwSearchImpl() {
        sendCommand(new Request("searchhw", 60000));
    }

    public void hwSearch() {
        hwSearchImpl();
    }

    protected void setPowerModeImpl(final String mode) {
        sendCommand(new Request("power_mode " + mode));
    }

    public void setPowerMode(final String mode) {
        setPowerModeImpl(mode);
    }

    protected void fireEvent(final String event, final Bundle bundle) {
        context.sendBroadcast(new Intent(event).putExtras(bundle));
    }

    protected void fireEvent(final String event) {
        context.sendBroadcast(new Intent(event));
    }

    protected void fireError(final String message) {
        final Bundle bundle = new Bundle();
        bundle.putString(Intent.EXTRA_TEXT, message);
        fireEvent(C.Event.ERROR_OCCURRED, bundle);
    }

    private void fireFrequencyEvent(final String event, final int frequency) {
        final Bundle bundle = new Bundle();
        if (frequency > 0) {
            bundle.putInt(C.Key.FREQUENCY, frequency);
        }
        fireEvent(event, bundle);
    }

    protected abstract String getBinaryName();

    protected abstract String getBinaryVersionKey();

    protected final String getBinaryPath() {
        return getAppRootPath(getBinaryName());
    }

    protected void onApplyAntennaPreference(final String value) {
        sendCommand(new Request("set_antenna " + value));
    }

    protected final void toggleCommandPoll(final boolean enabled) {
        mCommandPoll.toggle(enabled);
    }

    protected final void sendCommand(final Request request) {
        mCommandPoll.send(request);
    }

    protected final void closeServerListener() {
        if (mServer != null) {
            mServer.closeServer();
            mServer = null;
        }
    }

    protected final void startServerListener() {
        try {
            closeServerListener();
            mServer = new DatagramServer(config.serverPort);
            mServer.setCallback((event, bundle) -> {
                onServerEvent(event, bundle);
                if (mEventCallback != null) {
                    mEventCallback.onEvent(event, bundle);
                }
            });
            mServer.start();
        } catch (final IOException e) {
            e.printStackTrace();
        }
    }

    protected void onServerEvent(final String event, final Bundle bundle) {
    }

    protected final boolean isNativeBinaryInstalled() {
        return new File(getBinaryPath()).exists();
    }

    protected final boolean isNativeBinaryObsolete() {
        final int binaryVersion = Storage.getInstance(context).getInt(getBinaryVersionKey(), 0);
        return BuildConfig.DEBUG || binaryVersion < BuildConfig.VERSION_CODE;
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    protected final void installNativeBinary(final Callback<Void> callback) {
        final File dir = new File(getAppRootPath());
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

        Storage.getInstance(context).put(getBinaryVersionKey(), BuildConfig.VERSION_CODE);
        Utils.shell("chmod 777 " + getBinaryPath() + " 1>/dev/null 2>/dev/null", true);
        callback.onResult(null);
    }

    protected final void killRunningBinary() {
        Utils.shell(String.format("killall %1$s 1>/dev/null 2>/dev/null &", getBinaryName()), true);
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean copyBinary() throws FileNotFoundException {
        return copyAsset(getBinaryName(), getBinaryPath());
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private boolean copyAsset(final String fromAssetPath, final String toPath) throws FileNotFoundException {
        try {
            final InputStream in = context.getAssets().open(fromAssetPath);
            final File targetPath = new File(toPath);

            if (targetPath.exists()) {
                targetPath.delete();
            }

            targetPath.createNewFile();
            final OutputStream out = new FileOutputStream(toPath);

            final byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }

            in.close();
            out.flush();
            out.close();
            return true;
        } catch (final IOException e) {
            if (e instanceof FileNotFoundException) {
                throw (FileNotFoundException) e;
            }
            e.printStackTrace();
            return false;
        }
    }
}
