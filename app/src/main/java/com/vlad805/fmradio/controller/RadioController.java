package com.vlad805.fmradio.controller;

// Add this import at the top
import androidx.core.content.ContextCompat;

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
    private final RadioState mState;
    private BroadcastReceiver mTunerStateUpdater;

    public RadioController(final Context context) {
        mContext = context;
        mState = new RadioState();
    }

    public void requestForCurrentState(@Nullable final RadioStateUpdater.TunerStateListener callback) {
        getCurrentState(state -> {
            if (state != null) { // Add null check for safety
                mState.setStatus(state.getStatus());
                mState.setFrequency(state.getFrequency());
                mState.setStereo(state.isStereo());
                mState.setPs(state.getPs());
                mState.setRecording(state.isRecording());
                mState.setRecordingStarted(state.getRecordingStarted());
                mState.setForceSpeaker(state.isForceSpeaker()); // Make sure speaker state is included
                mState.setPi(state.getPi());
                mState.setPty(state.getPty());
                mState.setRt(state.getRt());
                mState.setRssi(state.getRssi());

                if (callback != null) {
                    final int mode =
                            RadioStateUpdater.SET_STATUS |
                                    RadioStateUpdater.SET_FREQUENCY |
                                    RadioStateUpdater.SET_INITIAL | // Include initial flag
                                    RadioStateUpdater.SET_RECORDING |
                                    RadioStateUpdater.SET_SPEAKER | // Include speaker
                                    RadioStateUpdater.SET_PS |      // Include RDS fields
                                    RadioStateUpdater.SET_RT |
                                    RadioStateUpdater.SET_PI |
                                    RadioStateUpdater.SET_PTY |
                                    RadioStateUpdater.SET_RSSI |
                                    RadioStateUpdater.SET_STEREO;

                    callback.onStateUpdated(mState, mode);
                }
            }
        });
    }

    public void registerForUpdates(RadioStateUpdater.TunerStateListener callback) {
        mTunerStateUpdater = new RadioStateUpdater(mState, callback);
        // Original line that caused the crash:
        // mContext.registerReceiver(mTunerStateUpdater, RadioStateUpdater.sFilter);

        // --- FIX ---
        // Use ContextCompat version and specify NOT_EXPORTED
        ContextCompat.registerReceiver(
                mContext,
                mTunerStateUpdater,
                RadioStateUpdater.sFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED // Specify the receiver is not exported
        );
        // -----------
    }

    public void unregisterForUpdates() {
        if (mTunerStateUpdater != null) {
            try { // Add try-catch as unregistering an already unregistered receiver can crash
                mContext.unregisterReceiver(mTunerStateUpdater);
                mTunerStateUpdater = null; // Set to null after successful unregister
            } catch (IllegalArgumentException e) {
                // Receiver was likely already unregistered, ignore
                mTunerStateUpdater = null; // Still set to null
            }
        }
    }

    public RadioState getState() {
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
        void onCurrentStateReady(final RadioState state);
    }

    public void getCurrentState(final CurrentStateListener listener) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(final Context context, final Intent intent) {
                // Check if the intent and extra are valid
                if (intent != null && C.Event.CURRENT_STATE.equals(intent.getAction())) {
                     RadioState receivedState = intent.getParcelableExtra(C.Key.STATE);
                     if (receivedState != null) {
                         listener.onCurrentStateReady(receivedState);
                     } else {
                         // Handle case where state is null (optional, maybe provide default?)
                         listener.onCurrentStateReady(new RadioState()); // Or handle error
                     }
                }
                // Always try to unregister to prevent leaks
                try {
                    mContext.unregisterReceiver(this);
                } catch (IllegalArgumentException e) {
                    // Ignore if already unregistered
                }
            }
        };

        // --- FIX for registerReceiver ---
        // Use ContextCompat and specify NOT_EXPORTED
         IntentFilter filter = new IntentFilter(C.Event.CURRENT_STATE);
         ContextCompat.registerReceiver(mContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        // --------------------------------

        send(C.Command.REQUEST_CURRENT_STATE);
    }
}