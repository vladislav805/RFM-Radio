package com.vlad805.fmradio.controller;

import androidx.annotation.NonNull;

/**
 * Listener for persistent tuner, audio routing, and recording state updates.
 * Immediately receives the current state snapshot with {@link RadioState#SET_INITIAL} upon registration.
 */
public interface StateListener {
    void onStateUpdated(@NonNull RadioState state, int changeMask);
}
