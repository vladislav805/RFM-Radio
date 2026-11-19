package com.vlad805.fmradio.service.audio;

public final class AudioLifecycle {
    private final AudioSelection mSelection;
    private final Runnable mStartAudio;
    private final Runnable mStopAudio;
    private boolean mStarted = false;
    private boolean mStopped = false;

    public AudioLifecycle(
            final AudioSelection selection,
            final Runnable startAudio,
            final Runnable stopAudio
    ) {
        mSelection = selection;
        mStartAudio = startAudio;
        mStopAudio = stopAudio;
    }

    public void beforeEnable() {
        if (mStopped || mSelection.path() == AudioSelection.Path.NONE || !mSelection.startsBeforeTuner()) {
            return;
        }
        startOnce();
    }

    public void onEnabled() {
        if (mStopped || mSelection.path() == AudioSelection.Path.NONE) {
            return;
        }
        startOnce();
    }

    public void beforeDisable() {
        if (mStopped || mSelection.path() == AudioSelection.Path.NONE || !mSelection.startsBeforeTuner()) {
            return;
        }
        stopIfStarted();
    }

    public void stop() {
        mStopped = true;
        if (mSelection.path() == AudioSelection.Path.NONE) {
            return;
        }
        stopIfStarted();
    }

    private void startOnce() {
        if (mStarted) {
            return;
        }
        mStarted = true;
        mStartAudio.run();
    }

    private void stopIfStarted() {
        if (!mStarted) {
            return;
        }
        mStarted = false;
        mStopAudio.run();
    }
}
