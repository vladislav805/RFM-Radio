package com.vlad805.fmradio.controller;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import com.vlad805.fmradio.C;

/**
 * vlad805 (c) 2021
 */
public class TunerStateUpdater extends BroadcastReceiver {
    public static final int SET_STATUS = 1;
    public static final int SET_FREQUENCY = 1 << 1;
    public static final int SET_PS = 1 << 2;
    public static final int SET_RT = 1 << 3;
    public static final int SET_RSSI = 1 << 4;
    public static final int SET_PTY = 1 << 5;
    public static final int SET_PI = 1 << 6;
    public static final int SET_STEREO = 1 << 7;
    public static final int SET_INITIAL = 1 << 31;

    public interface TunerStateListener {
        void onStateUpdated(final TunerState state, final int mode);
    }

    private final TunerState mState;

    private final TunerStateListener mCallback;

    public TunerStateUpdater(final TunerState state) {
        this(state, null);
    }

    public TunerStateUpdater(final TunerState state, final TunerStateListener callback) {
        mState = state;

        mCallback = callback;
    }

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }

        int mode = 0;

        final String action = intent.getAction();

        switch (action) {
            case C.Event.INSTALLING: {
                mState.setStatus(TunerStatus.INSTALLING);
                mode = SET_STATUS;
                break;
            }

            case C.Event.INSTALLED: {
                mState.setStatus(TunerStatus.INSTALLED);
                mState.setLastAction(action);
                mode = SET_STATUS;
                break;
            }

            case C.Event.LAUNCHING: {
                mState.setStatus(TunerStatus.LAUNCHING);
                mode = SET_STATUS;
                break;
            }

            case C.Event.LAUNCHED: {
                mState.setStatus(TunerStatus.LAUNCHED);
                mState.setLastAction(action);
                mode = SET_STATUS;
                break;
            }

            case C.Event.LAUNCH_FAILED: {
                mState.setStatus(TunerStatus.FATAL_ERROR);
                mState.setLastAction(action);
                mode = SET_STATUS;
                break;
            }

            case C.Event.ENABLING: {
                mState.setStatus(TunerStatus.ENABLING);
                mode = SET_STATUS;
                break;
            }

            case C.Event.ENABLED: {
                mState.setStatus(TunerStatus.ENABLED);
                mState.setLastAction(action);
                mode = SET_STATUS;
                break;
            }

            case C.Event.DISABLING: {
                mState.setStatus(TunerStatus.DISABLING);
                mode = SET_STATUS;
                break;
            }

            case C.Event.DISABLED: {
                mState.setStatus(TunerStatus.IDLE);
                mState.setLastAction(action);
                mode = SET_STATUS;
                break;
            }

            case C.Event.FREQUENCY_SET: {
                mState.setFrequency(intent.getIntExtra(C.Key.FREQUENCY, -1));
                mode = SET_FREQUENCY;
                break;
            }

            case C.Event.UPDATE_PS: {
                mState.setPs(intent.getStringExtra(C.Key.PS));
                mode = SET_PS;
                break;
            }

            case C.Event.UPDATE_RT: {
                mState.setRt(intent.getStringExtra(C.Key.RT));
                mode = SET_RT;
                break;
            }

            case C.Event.UPDATE_RSSI: {
                mState.setRssi(intent.getIntExtra(C.Key.RSSI, 0));
                mode = SET_RSSI;
                break;
            }

            case C.Event.UPDATE_PTY: {
                mState.setPty(intent.getIntExtra(C.Key.PTY, 0));
                mode = SET_PTY;
                break;
            }

            case C.Event.UPDATE_PI: {
                mState.setPi(intent.getIntExtra(C.Key.PI, 0));
                mode = SET_PI;
                break;
            }

            case C.Event.UPDATE_STEREO: {
                mState.setStereo(intent.getBooleanExtra(C.Key.STEREO_MODE, false));
                mode = SET_STEREO;
                break;
            }
        }

        if (mCallback != null && mode > 0) {
            mCallback.onStateUpdated(mState, mode);
        }
    }

    public static final IntentFilter sFilter;

    static {
        final String[] events = {
                C.Event.INSTALLING,
                C.Event.INSTALLED,
                C.Event.LAUNCHING,
                C.Event.LAUNCHED,
                C.Event.ENABLING,
                C.Event.ENABLED,

                C.Event.FREQUENCY_SET,
                C.Event.UPDATE_PS,
                C.Event.UPDATE_RT,
                C.Event.UPDATE_PTY,
                C.Event.UPDATE_RSSI,
                C.Event.UPDATE_STEREO,
                C.Event.HW_SEARCH_DONE,
                C.Event.JUMP_COMPLETE,
                C.Event.HW_SEEK_COMPLETE,

                C.Event.RECORD_STARTED,
                C.Event.RECORD_TIME_UPDATE,
                C.Event.RECORD_ENDED,

                C.Event.DISABLING,
                C.Event.DISABLED,
                C.Event.KILLED,
        };

        sFilter = new IntentFilter();

        for (final String event : events) {
            sFilter.addAction(event);
        }
    }
}
