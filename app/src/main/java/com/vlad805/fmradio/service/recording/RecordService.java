package com.vlad805.fmradio.service.recording;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import com.vlad805.fmradio.service.fm.RecordError;

import java.io.*;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

/**
 * Abstract recorder
 * vlad805 (c) 2021
 */
public abstract class RecordService implements IFMRecorder {
    public enum State {
        IDLE,
        RECORDING,
        FINISHING,
        DONE
    }

    /**
     * Minimal delay for update
     */
    private static final int MIN_DELAY_PING = 750;

    private final Context mContext;

    /**
     * Current frequency
     */
    private final int mKHz;

    /**
     * Recording sample rate
     */
    private final int mSampleRate;

    @Nullable
    private final RecordingListener mListener;

    /**
     * State of recording
     */
    private State mState = State.IDLE;

    /**
     * Recordable file
     */
    private RecordingTarget mRecordingTarget = null;

    /**
     * Size of file in bytes
     */
    protected int mRecordLength = 0;

    /**
     * Writable stream
     */
    protected BufferedOutputStream mBufferOutStream = null;
    private FileOutputStream mFileOutStream = null;

    /**
     * Timestamp of start recording
     */
    private long mStarted;

    /**
     * Last broadcasting
     */
    private long mLast;

    /** Number of interleaved PCM samples accepted by the encoder. */
    private long mRecordedPcmSamples;

    /** Duration reported before the pre-roll snapshot reaches the encoder. */
    private int mInitialDurationMillis;
    private IOException mWriteError;
    private WaveformAccumulator mWaveform;
    private final List<RfmMetadataEvent> mMetadataEvents = new ArrayList<>();

    /**
     * Constructor
     * @param context Context
     * @param kHz Current frequency in kHz
     */
    public RecordService(
            final Context context,
            final int kHz,
            final int sampleRate,
            @Nullable final RecordingListener listener
    ) {
        mContext = context;
        mKHz = kHz;
        mSampleRate = sampleRate;
        mListener = listener;
    }

    /**
     * Calls when user click "Start recording"
     * @param initialDurationMillis Duration of included pre-roll PCM in milliseconds
     * @throws RecordError If something going wrong
     */
    @Override
    public final synchronized void startRecord(final int initialDurationMillis) throws RecordError {
        mStarted = System.currentTimeMillis() - Math.max(0, initialDurationMillis);
        mInitialDurationMillis = Math.max(0, initialDurationMillis);
        mRecordedPcmSamples = 0;
        mWaveform = new WaveformAccumulator(mSampleRate, 2);
        mMetadataEvents.clear();
        createFile();
        mState = State.RECORDING;
        if (mListener != null) {
            mListener.onRecordingStarted(getDurationSeconds(), mRecordLength, getDisplayPath());
        }
    }

    @Override
    public final synchronized void record(final short[] data, final int length) {
        if (mState == State.RECORDING) {
            try {
                mRecordLength += writePcm(data, length);
                mRecordedPcmSamples += length;

                if (mWaveform != null) {
                    mWaveform.addPcm(data, length);
                }
            } catch (IOException e) {
                mWriteError = e;
                mState = State.IDLE;
                return;
            }

            final long now = System.currentTimeMillis();
            if (now - mLast > MIN_DELAY_PING) {
                mLast = now;
                if (mListener != null) {
                    mListener.onRecordingProgress(getDurationSeconds(), mRecordLength, getDisplayPath());
                }
            }
        }
    }

    @Override
    public final synchronized void recordRds(final long positionMillis, final RdsSnapshot snapshot) {
        if (mState != State.RECORDING || snapshot == null) {
            return;
        }

        final long pos = Math.max(0L, positionMillis);
        if (!mMetadataEvents.isEmpty()) {
            final RfmMetadataEvent last = mMetadataEvents.get(mMetadataEvents.size() - 1);

            if (last.getSnapshot().equals(snapshot)) {
                return;
            }

            if (last.getTimeMs() == pos) {
                mMetadataEvents.set(mMetadataEvents.size() - 1, new RfmMetadataEvent(pos, snapshot));
                return;
            }
        }

        mMetadataEvents.add(new RfmMetadataEvent(pos, snapshot));
    }

    /**
     * Called on user click "Stop record"
     */
    @Override
    public synchronized void stopRecord() {
        finishRecording(null);
    }

    /**
     * Finalizes the recording after an unexpected capture failure (e.g. lost FM input route).
     * Saves any already-captured audio as a playable partial file instead of
     * discarding them, while still reporting the interruption so the UI does not treat it as
     * an uninterrupted success.
     */
    @Override
    public synchronized void failRecord(final Throwable error) {
        finishRecording(error != null ? error : new IOException("Recording interrupted"));
    }

    private void finishRecording(@Nullable final Throwable interruptionError) {
        if (mState == State.FINISHING || mState == State.DONE) {
            return;
        }

        if (mRecordingTarget == null) {
            return;
        }

        // Reject further PCM data before flushing and closing the encoder.
        mState = State.FINISHING;

        try {
            onFinishRecording();
        } catch (IOException e) {
            mWriteError = e;
        }

        try {
            if (mBufferOutStream != null) {
                mBufferOutStream.close();
                mBufferOutStream = null;
            }
        } catch (final IOException e) {
            mWriteError = e;
        }

        try {
            if (mFileOutStream != null) {
                mFileOutStream.close();
                mFileOutStream = null;
            }
        } catch (final IOException e) {
            mWriteError = e;
        }

        final RecordingTarget target = mRecordingTarget;
        if (mWriteError != null || (interruptionError != null && mRecordedPcmSamples == 0)) {
            target.abort();
            if (mListener != null) {
                mListener.onRecordingFailed(mWriteError != null ? mWriteError : interruptionError);
            }
        } else {
            try {
                target.closeQuietly();
                embedRecordedMetadata(target);
                mRecordLength = (int) Math.min(Integer.MAX_VALUE, target.getLength());
                target.commit();
                final RecordingResult result = buildResult(target);
                if (mListener != null) {
                    if (interruptionError != null) {
                        mListener.onRecordingInterrupted(result, interruptionError);
                    } else {
                        mListener.onRecordingCompleted(result);
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
                target.abort();
                if (mListener != null) {
                    mListener.onRecordingFailed(e);
                }
            }
        }
        target.closeQuietly();
        mWaveform = null;
        mMetadataEvents.clear();
        mRecordingTarget = null;
        mState = State.DONE;
    }

    private void embedRecordedMetadata(final RecordingTarget target) {
        try {
            final byte[] peaks = mWaveform != null
                    ? mWaveform.finish(RfmMetadata.DEFAULT_WAVEFORM_BINS)
                    : new byte[0];
            final RfmMetadata metadata = new RfmMetadata(peaks, mMetadataEvents);
            final byte[] rfmBytes = RfmMetadataCodec.encode(metadata);

            embedMetadata(target, rfmBytes);
        } catch (final IOException | RuntimeException error) {
            Log.e("RFM-Recording", "Cannot embed RFM1 metadata", error);
        }
    }

    /**
     * Embeds encoded {@code RFM1} metadata into the finalized temporary file before publication.
     * Subclasses override this to write container-specific metadata.
     */
    protected void embedMetadata(final RecordingTarget target, final byte[] rfmBytes) throws IOException {
    }

    private RecordingResult buildResult(final RecordingTarget target) {
        final String recordingUri = target.getPublishedUri() != null
                ? target.getPublishedUri().toString()
                : null;
        final String recordingFilePath = recordingUri == null
                ? target.getPublishedFilePath()
                : null;

        return new RecordingResult(
                mRecordLength,
                getDurationSeconds(),
                target.getDisplayPath(),
                target.getDisplayName(),
                recordingUri,
                recordingFilePath
        );
    }

    /**
     * Returns state
     * @return State
     */
    public synchronized State getState() {
        return mState;
    }

    /**
     * Returns duration represented by the accepted PCM samples.
     * Uses the initial pre-roll duration while its queued samples are still being encoded.
     *
     * @return Duration in seconds
     */
    public synchronized int getDurationSeconds() {
        final long recordedMillis = (mRecordedPcmSamples * 1000L) / (mSampleRate * 2L);
        return (int) (Math.max(mInitialDurationMillis, recordedMillis) / 1000L);
    }

    /**
     * Create file and initialize streams
     */
    private void createFile() throws RecordError {
        RecordingTarget target = null;

        try {
            target = RecordingStorage.create(mContext, mKHz, getExtension(), getMimeType());
            mFileOutStream = target.openOutputStream();
            mBufferOutStream = new BufferedOutputStream(mFileOutStream, 131072);
            mRecordingTarget = target;

            onFileCreated();
        } catch (RecordError e) {
            if (target != null) {
                target.abort();
            }
            mRecordingTarget = null;
            throw e;
        } catch (IllegalStateException e) {
            if (target != null) {
                target.abort();
            }
            mRecordingTarget = null;
            throw new RecordError(e.getMessage());
        } catch (IOException e) {
            if (target != null) {
                target.abort();
            }
            mRecordingTarget = null;
            throw new RecordError("Cannot create recording file");
        }
    }

    protected final int getSampleRate() {
        return mSampleRate;
    }

    protected final int getFrequencyKhz() {
        return mKHz;
    }

    protected final long getStartedAtMillis() {
        return mStarted;
    }

    protected final FileChannel getRecordingChannel() {
        return mFileOutStream != null ? mFileOutStream.getChannel() : null;
    }

    public final String getDisplayPath() {
        return mRecordingTarget != null ? mRecordingTarget.getDisplayPath() : "";
    }

    public final String getDisplayName() {
        return mRecordingTarget != null ? mRecordingTarget.getDisplayName() : "";
    }

    public final int getCurrentSizeBytes() {
        return mRecordLength;
    }

    /**
     * Return file extension
     * @return Extension without ".": for example "mp3" or "wav".
     */
    protected abstract String getExtension();

    protected abstract String getMimeType();

    /**
     * Calls when file created and opened streams for write.
     */
    protected abstract void onFileCreated() throws IOException;

    /** Writes interleaved stereo PCM and returns the number of output bytes written. */
    protected abstract int writePcm(short[] data, int length) throws IOException;

    /**
     * Calls when recording is finished.
     */
    protected abstract void onFinishRecording() throws IOException;
}
