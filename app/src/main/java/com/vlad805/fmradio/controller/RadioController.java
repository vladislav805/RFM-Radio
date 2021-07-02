package com.vlad805.fmradio.controller;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import androidx.annotation.Nullable;
import com.vlad805.fmradio.C;
import com.vlad805.fmradio.enums.Direction;
import com.vlad805.fmradio.enums.PowerMode;
import com.vlad805.fmradio.service.FMService;

/**
 * vlad805 (c) 2020
 */
public class RadioController {
    private final Context mContext;
    private final TunerState mState;
    private BroadcastReceiver mTunerStateUpdater;

    public RadioController(final Context context) {
        mContext = context;
        mState = new TunerState();
    }

    public void requestForCurrentState(@Nullable final TunerStateUpdater.TunerStateListener callback) {
        getCurrentState(state -> {
            mState.setStatus(state.getStatus());
            mState.setFrequency(state.getFrequency());
            mState.setStereo(state.isStereo());
            mState.setPs(state.getPs());

            if (callback != null) {
                callback.onStateUpdated(mState, TunerStateUpdater.SET_STATUS | TunerStateUpdater.SET_FREQUENCY | TunerStateUpdater.SET_INITIAL);
            }
        });
    }

    public void registerForUpdates(TunerStateUpdater.TunerStateListener callback) {
        mTunerStateUpdater = new TunerStateUpdater(mState, callback);
        mContext.registerReceiver(mTunerStateUpdater, TunerStateUpdater.sFilter);
    }

    public void unregisterForUpdates() {
        if (mTunerStateUpdater != null) {
            mContext.unregisterReceiver(mTunerStateUpdater);
        }
    }

    public TunerState getState() {
        return mState;
    }

    private void send(final String action) {
        send(action, new Bundle());
    }

    private void send(final String action, final Bundle bundle) {
        mContext.startService(new Intent(mContext, FMService.class).setAction(action).putExtras(bundle));
    }

    public void setup() {
        send(C.Command.INSTALL);
    }

    public void launch() {
        send(C.Command.LAUNCH);
    }

    public void kill() {
        send(C.Command.KILL);
    }

    public void enable() {
        send(C.Command.ENABLE);
    }

    public void setFrequency(final int kHz) {
        final Bundle bundle = new Bundle();
        bundle.putInt(C.Key.FREQUENCY, kHz);
        send(C.Command.SET_FREQUENCY, bundle);
    }

    public void jump(final Direction direction) {
        final Bundle bundle = new Bundle();
        bundle.putInt(C.Key.JUMP_DIRECTION, direction.getValue());
        send(C.Command.JUMP, bundle);
    }

    public void hwSeek(final Direction direction) {
        final Bundle bundle = new Bundle();
        bundle.putInt(C.Key.SEEK_HW_DIRECTION, direction.getValue());
        send(C.Command.HW_SEEK, bundle);
    }

    public void setPowerMode(final PowerMode mode) {
        final Bundle bundle = new Bundle();
        bundle.putString(C.Key.POWER_MODE, mode.getValue());
        send(C.Command.POWER_MODE, bundle);
    }

    public void hwSearch() {
        send(C.Command.HW_SEARCH);
    }

    public void disable() {
        send(C.Command.DISABLE);
    }

    public void record(final boolean state) {
        send(state ? C.Command.RECORD_START : C.Command.RECORD_STOP);
    }

    public interface CurrentStateListener {
        void onCurrentStateReady(final TunerState state);
    }

    public void getCurrentState(final CurrentStateListener listener) {
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(final Context context, final Intent intent) {
                listener.onCurrentStateReady(intent.getParcelableExtra(C.Key.STATE));
                mContext.unregisterReceiver(this);
            }
        }, new IntentFilter(C.Event.CURRENT_STATE));

        send(C.Command.REQUEST_CURRENT_STATE);
    }
}
