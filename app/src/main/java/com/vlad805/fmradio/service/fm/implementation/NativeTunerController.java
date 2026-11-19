package com.vlad805.fmradio.service.fm.implementation;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import com.vlad805.fmradio.BuildConfig;
import com.vlad805.fmradio.C;
import com.vlad805.fmradio.Storage;
import com.vlad805.fmradio.Utils;
import com.vlad805.fmradio.enums.TunerDriver;
import com.vlad805.fmradio.helper.TunerDriverDetector;
import com.vlad805.fmradio.preferences.BandUtils;
import com.vlad805.fmradio.service.FMService;
import com.vlad805.fmradio.service.fm.FMEventCallback;
import com.vlad805.fmradio.service.fm.RadioStatePatch;
import com.vlad805.fmradio.service.fm.communication.NativeProcessSession;
import com.vlad805.fmradio.service.fm.communication.Request;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class NativeTunerController {
    private static final String TAG = "RFM-QCOM";
    @SuppressLint("SdCardPath")
    private static final String APP_ROOT_PATH = "/data/data/" + BuildConfig.APPLICATION_ID + "/files/";
    private static final long DUPLICATE_TUNE_WINDOW_MS = 1500L;

    private interface Callback<T> {
        void onResult(T result);

        default void onError(final Error e) {
        }
    }

    private static final class RootAccessDeniedError extends Error {
        RootAccessDeniedError(final String message) {
            super(message);
        }
    }

    private final Context mContext;
    private final Object mSessionLock = new Object();
    private FMEventCallback mEventCallback;
    private NativeProcessSession mNativeSession;
    private volatile String mNativeBackend;
    private volatile TunerDriver mDriverKind;
    private int mLastRequestedFrequencyKhz = Integer.MIN_VALUE;
    private long mLastRequestedFrequencyAtMs = 0L;

    public NativeTunerController(final Context context, final TunerDriver detectedDriver) {
        mContext = context;
        mDriverKind = detectedDriver;
    }

    public TunerDriver getDriverKind() {
        return mDriverKind;
    }

    public int getRecorderSampleRate() {
        // HAL Helium - 48kHz
        // V4L2 Qcom - 44.1kHz
        return mDriverKind == TunerDriver.HAL_HELIUM ? 48000 : 44100;
    }

    public void refreshAudioRoute() {
        if (mDriverKind == TunerDriver.HAL_HELIUM) {
            sendCommand(new Request("slimbus 0", 2000));
            sendCommand(new Request("slimbus 1", 2000));
        }
    }

    public void setEventListener(final FMEventCallback callback) {
        mEventCallback = callback;
    }

    public boolean isInstalled() {
        final File binary = new File(getBinaryPath());
        return binary.isFile() && binary.canExecute();
    }

    public boolean isObsolete() {
        final int binaryVersion = Storage.getInstance(mContext).getInt(getBinaryVersionKey(), 0);
        return BuildConfig.DEBUG || binaryVersion < BuildConfig.VERSION_CODE;
    }

    public void install() {
        fireEvent(C.Event.INSTALLING);
        installNativeBinary(new Callback<>() {
            @Override
            public void onResult(final Void result) {
                fireEvent(C.Event.INSTALLED);
            }

            @Override
            public void onError(final Error error) {
                Log.e(TAG, "Failed to install native binary", error);
                fireEvent(C.Event.LAUNCH_FAILED);
            }
        });
    }

    public void prepareBinary() {
        fireEvent(C.Event.PREPARING);
        final File binary = new File(getBinaryPath());
        if (binary.isFile() && !isObsolete()) {
            if (ensureNativeBinaryExecutable(binary)) {
                fireEvent(C.Event.INSTALLED);
            } else {
                Log.e(TAG, "Failed to restore native binary permissions: " + binary);
                fireEvent(C.Event.LAUNCH_FAILED);
            }
            return;
        }

        install();
    }

    public void launch() {
        fireEvent(C.Event.LAUNCHING);
        launchImpl(new Callback<>() {
            @Override
            public void onResult(final Void result) {
                fireEvent(C.Event.LAUNCHED);
            }

            @Override
            public void onError(final Error e) {
                Log.e(TAG, "Failed to launch native tuner", e);
                fireEvent(e instanceof RootAccessDeniedError
                        ? C.Event.ROOT_ACCESS_DENIED
                        : C.Event.LAUNCH_FAILED);
            }
        });
    }

    public void kill() {
        killImpl(result -> {
            fireEvent(C.Event.DISABLED);
            fireEvent(C.Event.KILLED);
        });
    }

    public void enable() {
        fireEvent(C.Event.ENABLING);
        enableImpl(new Callback<>() {
            @Override
            public void onResult(final Void result) {
                fireEvent(C.Event.ENABLED);
            }

            @Override
            public void onError(final Error error) {
                fireError(error.getMessage());
                fireEvent(C.Event.DISABLED);
            }
        });
    }

    public void disable() {
        fireEvent(C.Event.DISABLING);
        sendCommand(new Request("disable", 5000)
                .onResponse(result -> fireEvent(C.Event.DISABLED))
                .onError(error -> Log.e(TAG, "Failed to disable native tuner", error)));
    }

    public void setFrequency(final int kHz) {
        if (mDriverKind == TunerDriver.HAL_HELIUM) {
            final long now = System.currentTimeMillis();
            if (mLastRequestedFrequencyKhz == kHz && now - mLastRequestedFrequencyAtMs < DUPLICATE_TUNE_WINDOW_MS) {
                emitFrequencySet(kHz);
                return;
            }

            mLastRequestedFrequencyKhz = kHz;
            mLastRequestedFrequencyAtMs = now;
        }

        sendCommand(new Request("setfreq " + kHz).onResponse(data -> emitFrequencySet(kHz)));
    }

    private void emitFrequencySet(final int kHz) {
        final Bundle bundle = new Bundle();
        bundle.putInt(C.Key.FREQUENCY, kHz);
        fireEvent(C.Event.FREQUENCY_SET, bundle);
    }

    public void jump(final int direction) {
        sendCommand(new Request("jump " + direction, 1500).onResponse(data -> {
            final int result = Utils.parseInt(data);
            if (result < 0) {
                fireError("Error while jumping");
                return;
            }
            fireFrequencyEvent(C.Event.JUMP_COMPLETE, result);
        }));
    }

    public void hwSeek(final int direction) {
        sendCommand(new Request("seekhw " + direction, 4000).onResponse(data -> {
            if (data == null || !data.startsWith("ok")) {
                fireError("Error while hwSeek: " + data);
            }
        }));
    }

    public void hwSearch() {
        sendCommand(new Request("searchhw", 60000));
    }

    public void cancelHwSearch() {
        sendCommand(new Request("search_cancel"));
    }

    public void setPowerMode(final String mode) {
        sendCommand(new Request("power_mode " + mode));
    }

    public void applyPreference(final String key, final String value) {
        switch (key) {
            case C.PrefKey.TUNER_REGION:
                sendCommand(new Request("set_region " + TunerCommandFormat.regionName(Utils.parseInt(value))));
                break;
            case C.PrefKey.TUNER_SPACING:
                sendCommand(new Request("set_spacing " + TunerCommandFormat.spacingKhz(Utils.parseInt(value))));
                break;
            case C.PrefKey.TUNER_STEREO:
                sendCommand(new Request("set_stereo " + value));
                break;
            case C.PrefKey.TUNER_SOFT_MUTE:
                sendCommand(new Request("set_soft_mute " + value));
                break;
            case C.PrefKey.TUNER_NOTCH_FILTER:
                sendCommand(new Request("set_notch_filter " + value));
                break;
            case C.PrefKey.TUNER_ANTENNA:
                sendCommand(new Request("set_antenna " + value).onResponse(str -> {
                    if (str.startsWith("ERR_UNV_ANT")) {
                        fireEvent(C.Event.ERROR_INVALID_ANTENNA);
                    }
                }));
                break;
            case C.PrefKey.TUNER_POWER_MODE:
                sendCommand(new Request("power_mode " + value));
                break;
            case C.PrefKey.RDS_AUTO_AF:
                sendCommand(new Request("auto_af " + value));
                break;
        }
    }

    public void setupTunerByPreferences(final String[] changed) {
        for (final String key : changed) {
            String value = null;
            switch (key) {
                case C.PrefKey.TUNER_REGION: {
                    final int band = Storage.getPrefInt(mContext, key, C.PrefDefaultValue.TUNER_REGION);
                    value = String.valueOf(band);
                    break;
                }
                case C.PrefKey.TUNER_STEREO: {
                    final boolean stereo = Storage.getPrefBoolean(mContext, key, C.PrefDefaultValue.TUNER_STEREO);
                    value = stereo ? "1" : "0";
                    break;
                }
                case C.PrefKey.TUNER_SOFT_MUTE: {
                    final boolean enabled = Storage.getPrefBoolean(mContext, key, C.PrefDefaultValue.TUNER_SOFT_MUTE);
                    value = enabled ? "1" : "0";
                    break;
                }
                case C.PrefKey.TUNER_NOTCH_FILTER: {
                    final int mode = Storage.getPrefInt(mContext, key, C.PrefDefaultValue.TUNER_NOTCH_FILTER);
                    value = String.valueOf(mode);
                    break;
                }
                case C.PrefKey.TUNER_SPACING: {
                    final int spacing = Storage.getPrefInt(mContext, key, C.PrefDefaultValue.TUNER_SPACING);
                    applyPreference(key, String.valueOf(spacing));
                    final boolean stereo = Storage.getPrefBoolean(mContext, C.PrefKey.TUNER_STEREO, C.PrefDefaultValue.TUNER_STEREO);
                    applyPreference(C.PrefKey.TUNER_STEREO, stereo ? "1" : "0");
                    break;
                }
                case C.PrefKey.TUNER_ANTENNA: {
                    final int region = Storage.getPrefInt(mContext, key, C.PrefDefaultValue.TUNER_ANTENNA);
                    value = String.valueOf(region);
                    break;
                }
                case C.PrefKey.TUNER_POWER_MODE: {
                    final boolean isLowPower = Storage.getPrefBoolean(mContext, key, C.PrefDefaultValue.TUNER_POWER_MODE);
                    value = isLowPower ? "low" : "normal";
                    break;
                }
                case C.PrefKey.RDS_AUTO_AF: {
                    final boolean enabled = Storage.getPrefBoolean(mContext, key, C.PrefDefaultValue.RDS_AUTO_AF);
                    value = enabled ? "1" : "0";
                    break;
                }
            }

            if (value != null) {
                applyPreference(key, value);
            }
        }
    }

    private void launchImpl(final Callback<Void> callback) {
        new Thread(() -> {
            if (Utils.runAsRoot("true") != 0) {
                failLaunch(callback, new RootAccessDeniedError("Root access was not granted"));
                return;
            }

            killRunningBinary();
            Utils.sleep(150);
            try {
                startNativeProcess(5000L);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                failLaunch(callback, "Native tuner launch was interrupted", e);
                return;
            } catch (final IOException e) {
                failLaunch(callback, "Failed to launch native tuner process", e);
                return;
            }

            // Prefer the root helper's reported backend when available: SELinux may hide
            // /sys/class/video4linux/radio0/name from the unprivileged app process.
            final TunerDriver resolvedDriver = TunerDriverDetector.resolveNativeBackend(mDriverKind, mNativeBackend);
            if (
                    mNativeBackend != null &&
                    !mNativeBackend.trim().isEmpty() &&
                    !"none".equalsIgnoreCase(mNativeBackend.trim()) &&
                    TunerDriverDetector.fromNativeBackend(mNativeBackend) == null
            ) {
                Log.w(TAG, "Unknown native backend token '" + mNativeBackend + "', keeping " + mDriverKind);
            }

            mDriverKind = resolvedDriver;

            if (mDriverKind == TunerDriver.NONE) {
                failLaunch(callback, "Native tuner reported unsupported backend 'none'", null);
                return;
            }

            // Legacy initialization can spend six seconds waiting for fm_dl,
            // followed by device stabilization delays and the native response delay.
            sendCommand(new Request("init", 10000)
                    .onResponse(data -> {
                        if (isOkResponse(data)) {
                            callback.onResult(null);
                        } else {
                            failLaunch(callback, "Failed to initialize tuner: " + data, null);
                        }
                    })
                    .onError(error -> failLaunch(callback, "Native tuner did not respond to init", error)));
        }, "NativeTunerLaunch").start();
    }

    private void failLaunch(
            final Callback<Void> callback,
            final String message,
            final Throwable cause
    ) {
        failLaunch(callback, new Error(message, cause));
    }

    private void failLaunch(
            final Callback<Void> callback,
            final Error error
    ) {
        killImpl(result -> callback.onError(error));
    }

    private void enableImpl(final Callback<Void> callback) {
        final int region = Storage.getPrefInt(mContext, C.PrefKey.TUNER_REGION, C.PrefDefaultValue.TUNER_REGION);
        final int frequency = getStartupFrequency(region);
        final int spacing = Storage.getPrefInt(mContext, C.PrefKey.TUNER_SPACING, C.PrefDefaultValue.TUNER_SPACING);
        final boolean stereo = Storage.getPrefBoolean(mContext, C.PrefKey.TUNER_STEREO, C.PrefDefaultValue.TUNER_STEREO);
        final int antenna = Storage.getPrefInt(mContext, C.PrefKey.TUNER_ANTENNA, C.PrefDefaultValue.TUNER_ANTENNA);
        final boolean autoAf = Storage.getPrefBoolean(mContext, C.PrefKey.RDS_AUTO_AF, C.PrefDefaultValue.RDS_AUTO_AF);
        final boolean softMute = Storage.getPrefBoolean(mContext, C.PrefKey.TUNER_SOFT_MUTE, C.PrefDefaultValue.TUNER_SOFT_MUTE);
        final int notchFilter = Storage.getPrefInt(mContext, C.PrefKey.TUNER_NOTCH_FILTER, C.PrefDefaultValue.TUNER_NOTCH_FILTER);

        final String command = TunerCommandFormat.enable(
                frequency,
                region,
                spacing,
                stereo,
                softMute,
                antenna,
                autoAf
        );
        sendCommand(new Request(command, 5000).onResponse(result -> {
            if (!isOkResponse(result)) {
                callback.onError(new Error("Failed to enable tuner: " + result));
                return;
            }
            if (notchFilter != C.PrefDefaultValue.TUNER_NOTCH_FILTER) {
                applyPreference(C.PrefKey.TUNER_NOTCH_FILTER, String.valueOf(notchFilter));
            }
            callback.onResult(null);
        }).onError(error -> callback.onError(new Error("Failed to enable tuner", error))));
    }

    private void killImpl(final Callback<Void> callback) {
        final NativeProcessSession session;
        synchronized (mSessionLock) {
            session = mNativeSession;
        }

        if (session == null) {
            callback.onResult(null);
            return;
        }

        new Thread(() -> {
            final boolean stopped = session.stop(5000L);
            synchronized (mSessionLock) {
                if (mNativeSession == session) {
                    mNativeSession = null;
                }
            }
            if (!stopped) {
                killRunningBinary();
            }
            callback.onResult(null);
        }, "NativeTunerStop").start();
    }

    private void sendCommand(final Request request) {
        final NativeProcessSession session;
        synchronized (mSessionLock) {
            session = mNativeSession;
        }
        if (session == null) {
            request.fireError(new IOException("Native process is not running"));
            return;
        }
        session.send(request);
    }

    private void startNativeProcess(final long timeoutMs) throws IOException, InterruptedException {
        final NativeProcessSession session = new NativeProcessSession(
                getBinaryPath(),
                new FMEventCallback() {
                    @Override
                    public void onEvent(final String event) {
                        if (mEventCallback != null) {
                            mEventCallback.onEvent(event);
                        }
                    }

                    @Override
                    public void onStatePatch(final RadioStatePatch patch) {
                        if (mEventCallback != null) {
                            mEventCallback.onStatePatch(patch);
                        }
                    }

                    @Override
                    public void onSearchDone(final int[] stations) {
                        if (mEventCallback != null) {
                            mEventCallback.onSearchDone(stations);
                        }
                    }
                },
                this::handleUnexpectedNativeExit
        );

        synchronized (mSessionLock) {
            if (mNativeSession != null) {
                throw new IOException("Native process session is already active");
            }
            mNativeSession = session;
        }

        try {
            session.start(timeoutMs);
            mNativeBackend = session.getNativeBackend();
        } catch (final IOException | InterruptedException e) {
            synchronized (mSessionLock) {
                if (mNativeSession == session) {
                    mNativeSession = null;
                }
            }
            throw e;
        }
    }

    private void handleUnexpectedNativeExit(final int exitCode) {
        synchronized (mSessionLock) {
            mNativeSession = null;
        }
        fireError("Native tuner process exited with code " + exitCode);
        fireEvent(C.Event.DISABLED);
        mContext.stopService(new Intent(mContext, FMService.class));
    }

    private void fireEvent(final String event, final Bundle bundle) {
        Utils.sendAppBroadcast(mContext, new Intent(event).putExtras(bundle));
    }

    private void fireEvent(final String event) {
        Utils.sendAppBroadcast(mContext, new Intent(event));
    }

    private void fireError(final String message) {
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

    private String getBinaryName() {
        return "fmbin-" + Utils.determineArch();
    }

    private String getBinaryVersionKey() {
        return C.PrefKey.BINARY_VERSION_NATIVE;
    }

    private String getBinaryPath() {
        return APP_ROOT_PATH + getBinaryName();
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private void installNativeBinary(final Callback<Void> callback) {
        final File dir = new File(APP_ROOT_PATH);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        try {
            if (!copyBinary()) {
                callback.onError(new Error("Error while copy binary"));
                return;
            }
        } catch (final FileNotFoundException e) {
            Utils.runAsRoot("killall " + getBinaryName());
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

        if (!ensureNativeBinaryExecutable(new File(getBinaryPath()))) {
            callback.onError(new Error("Failed to make native binary executable"));
            return;
        }

        Storage.getInstance(mContext).put(getBinaryVersionKey(), BuildConfig.VERSION_CODE);
        callback.onResult(null);
    }

    private boolean ensureNativeBinaryExecutable(final File binary) {
        try {
            //noinspection OctalInteger
            Os.chmod(binary.getAbsolutePath(), 0755);
            return binary.isFile() && binary.canExecute();
        } catch (final ErrnoException | SecurityException e) {
            Log.e(TAG, "Failed to chmod native binary: " + binary, e);
            return false;
        }
    }

    private void killRunningBinary() {
        Utils.runAsRoot(String.format("killall %1$s 1>/dev/null 2>/dev/null", getBinaryName()));
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean copyBinary() throws FileNotFoundException {
        return copyAsset(getBinaryName(), getBinaryPath());
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private boolean copyAsset(final String fromAssetPath, final String toPath) throws FileNotFoundException {
        try {
            final InputStream in = mContext.getAssets().open(fromAssetPath);
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

    private static boolean isOkResponse(final String response) {
        return response != null && response.startsWith("ok");
    }

    private int getStartupFrequency(final int region) {
        final int savedFrequency = Storage.getInstance(mContext).getInt(
                C.PrefKey.LAST_FREQUENCY,
                C.PrefDefaultValue.LAST_FREQUENCY
        );
        final BandUtils.BandLimit band = BandUtils.getBandLimit(region);

        return Math.max(band.lower, Math.min(savedFrequency, band.upper));
    }
}
