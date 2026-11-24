package com.vlad805.fmradio.controller;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.vlad805.fmradio.Storage;
import com.vlad805.fmradio.enums.Direction;
import com.vlad805.fmradio.enums.PowerMode;
import com.vlad805.fmradio.service.FMService;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * Activity-scoped wrapper around the local {@link FMService} binding.
 * Queues commands issued before {@link ServiceConnection#onServiceConnected} completes
 * and delivers the initial {@link RadioState} snapshot synchronously upon connection.
 */
public final class RadioClient {
    interface ServiceBinder {
        boolean bind(@NonNull ServiceConnection connection);

        void unbind(@NonNull ServiceConnection connection);
    }

    @FunctionalInterface
    private interface Command {
        void execute(@NonNull RadioServiceController controller);
    }

    private final ServiceBinder mServiceBinder;
    @Nullable
    private final StateListener mStateListener;
    @Nullable
    private final EventListener mEventListener;
    private final Queue<Command> mPendingCommands = new ArrayDeque<>();
    private final RadioState mFallbackState = new RadioState();
    @Nullable
    private final Storage mStorage;

    private RadioServiceController mController;
    private boolean mBound;

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(final ComponentName name, final IBinder service) {
            if (service instanceof FMService.LocalBinder) {
                onConnected(((FMService.LocalBinder) service).getController());
            }
        }

        @Override
        public void onServiceDisconnected(final ComponentName name) {
            onDisconnected();
        }
    };

    public RadioClient(
            @NonNull final Context context,
            @Nullable final StateListener stateListener,
            @Nullable final EventListener eventListener
    ) {
        final Context appContext = context.getApplicationContext();
        mStorage = Storage.get(appContext);
        mFallbackState.applyFrequency(mStorage.getLastFrequency());
        mStateListener = stateListener;
        mEventListener = eventListener;
        mServiceBinder = new ServiceBinder() {
            @Override
            public boolean bind(@NonNull final ServiceConnection connection) {
                final Intent intent = new Intent(appContext, FMService.class);
                return appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            }

            @Override
            public void unbind(@NonNull final ServiceConnection connection) {
                appContext.unbindService(connection);
            }
        };
    }

    RadioClient(
            @NonNull final ServiceBinder serviceBinder,
            final int initialFallbackFrequencyKhz,
            @Nullable final StateListener stateListener,
            @Nullable final EventListener eventListener
    ) {
        mServiceBinder = serviceBinder;
        mStorage = null;
        if (initialFallbackFrequencyKhz > 0) {
            mFallbackState.applyFrequency(initialFallbackFrequencyKhz);
        }
        mStateListener = stateListener;
        mEventListener = eventListener;
    }

    public void connect() {
        if (mBound) {
            return;
        }
        if (mStorage != null) {
            mFallbackState.applyFrequency(mStorage.getLastFrequency());
        }
        mBound = mServiceBinder.bind(mConnection);
    }

    public void disconnect() {
        mPendingCommands.clear();
        if (mController != null) {
            if (mStateListener != null) {
                mController.removeStateListener(mStateListener);
            }
            if (mEventListener != null) {
                mController.removeEventListener(mEventListener);
            }
            mController = null;
        }
        if (mBound) {
            try {
                mServiceBinder.unbind(mConnection);
            } catch (final IllegalArgumentException ignored) {
            }
            mBound = false;
        }
    }

    public boolean isConnected() {
        return mController != null;
    }

    @NonNull
    public RadioState getState() {
        return mController != null ? mController.getState() : mFallbackState;
    }

    public void enable() {
        executeOrEnqueue(RadioServiceController::enable);
    }

    public void disable() {
        executeOrEnqueue(RadioServiceController::disable);
    }

    public void setFrequency(final int kHz) {
        executeOrEnqueue(controller -> controller.setFrequency(kHz));
    }

    public void jump(@NonNull final Direction direction) {
        executeOrEnqueue(controller -> controller.jump(direction));
    }

    public void hwSeek(@NonNull final Direction direction) {
        executeOrEnqueue(controller -> controller.hwSeek(direction));
    }

    public void hwSearch() {
        executeOrEnqueue(RadioServiceController::hwSearch);
    }

    public void cancelHwSearch() {
        executeOrEnqueue(RadioServiceController::cancelHwSearch);
    }

    public void setPowerMode(@NonNull final PowerMode mode) {
        executeOrEnqueue(controller -> controller.setPowerMode(mode));
    }

    public void toggleSpeaker() {
        executeOrEnqueue(RadioServiceController::toggleSpeaker);
    }

    public void startRecord() {
        executeOrEnqueue(RadioServiceController::startRecord);
    }

    public void stopRecord() {
        executeOrEnqueue(RadioServiceController::stopRecord);
    }

    void onConnected(@NonNull final RadioServiceController controller) {
        if (!mBound) {
            return;
        }
        mController = controller;
        if (mEventListener != null) {
            controller.addEventListener(mEventListener);
        }
        if (mStateListener != null) {
            controller.addStateListener(mStateListener);
        }
        while (!mPendingCommands.isEmpty() && mController == controller) {
            final Command command = mPendingCommands.poll();
            if (command != null) {
                command.execute(controller);
            }
        }
    }

    void onDisconnected() {
        mController = null;
    }

    private void executeOrEnqueue(@NonNull final Command command) {
        if (mController != null) {
            command.execute(mController);
        } else if (mBound) {
            mPendingCommands.offer(command);
        }
    }
}
