package com.vlad805.fmradio.service.recording;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import com.vlad805.fmradio.Storage;
import com.vlad805.fmradio.service.fm.RecordError;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;

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
    private boolean mSubtitlesRequested;
    private File mSubtitleFile;
    private RdsSubtitleWriter mSubtitles;
    private RecordingTarget mPublishedSubtitles;

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
        // Keep the setting stable for the entire recording, even if preferences change.
        mSubtitlesRequested = Storage.get(mContext).isRecordingSaveSrt();
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
                byte[] bufferEncoded = new byte[(int) (7200 + data.length * 2 * 1.75)];
                int bytesEncoded = onReceivedData(data, length, bufferEncoded);
                mBufferOutStream.write(bufferEncoded, 0, bytesEncoded);
                mRecordLength += bytesEncoded;
                mRecordedPcmSamples += length;
            } catch (IOException e) {
                e.printStackTrace();
                mState = State.IDLE;
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
        if (mState != State.RECORDING || mSubtitles == null) {
            return;
        }
        try {
            mSubtitles.update(positionMillis, snapshot);
        } catch (IOException error) {
            Log.e("RFM-Recording", "Cannot write RDS subtitles", error);
            discardSubtitles();
        }
    }

    private void discardSubtitles() {
        if (mSubtitles != null) {
            try {
                mSubtitles.finish(0);
            } catch (IOException ignored) {
            }
            mSubtitles = null;
        }
        if (mSubtitleFile != null) {
            //noinspection ResultOfMethodCallIgnored
            mSubtitleFile.delete();
            mSubtitleFile = null;
        }
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
     * Saves any already-captured audio and subtitles as a playable partial file instead of
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

        onFinishRecording();
        if (mSubtitles != null) {
            try {
                mSubtitles.finish(mRecordedPcmSamples * 1000L / (mSampleRate * 2L));
                mSubtitles = null;
            } catch (IOException error) {
                Log.e("RFM-Recording", "Cannot finish RDS subtitles", error);
                discardSubtitles();
            }
        }

        try {
            if (mBufferOutStream != null) {
                mBufferOutStream.close();
                mBufferOutStream = null;
            }
        } catch (final IOException e) {
            e.printStackTrace();
        }

        try {
            if (mFileOutStream != null) {
                mFileOutStream.close();
                mFileOutStream = null;
            }
        } catch (final IOException e) {
            e.printStackTrace();
        }

        final RecordingTarget target = mRecordingTarget;
        if (interruptionError != null && mRecordedPcmSamples == 0) {
            target.abort();
            if (mListener != null) {
                mListener.onRecordingFailed(interruptionError);
            }
        } else {
            try {
                target.commit();
                publishSubtitles(target);
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
        discardSubtitles();
        mRecordingTarget = null;
        mState = State.DONE;
    }

    /** A sidecar failure must never discard an otherwise valid audio recording. */
    private void publishSubtitles(final RecordingTarget audio) {
        if (mSubtitleFile == null) {
            return;
        }
        RecordingTarget subtitles = null;
        try {
            subtitles = audio.createSubtitleTarget(mSubtitleFile);
            subtitles.commit();
            mPublishedSubtitles = subtitles;
        } catch (IOException | RuntimeException error) {
            Log.e("RFM-Recording", "Cannot publish RDS subtitles", error);
            if (subtitles != null) {
                subtitles.abort();
            }
        } finally {
            if (subtitles != null) {
                subtitles.closeQuietly();
            }
        }
    }

    private RecordingResult buildResult(final RecordingTarget target) {
        final String recordingUri = target.getPublishedUri() != null
                ? target.getPublishedUri().toString()
                : null;
        final String recordingFilePath = recordingUri == null
                ? target.getPublishedFilePath()
                : null;

        String subtitleUri = null;
        String subtitleFilePath = null;
        if (mPublishedSubtitles != null) {
            if (mPublishedSubtitles.getPublishedUri() != null) {
                subtitleUri = mPublishedSubtitles.getPublishedUri().toString();
            } else {
                subtitleFilePath = mPublishedSubtitles.getPublishedFilePath();
            }
        }

        return new RecordingResult(
                mRecordLength,
                getDurationSeconds(),
                target.getDisplayPath(),
                target.getDisplayName(),
                recordingUri,
                recordingFilePath,
                mSubtitlesRequested,
                subtitleUri,
                subtitleFilePath
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
            if (!mSubtitlesRequested) {
                return;
            }
            try {
                mSubtitleFile = File.createTempFile("rfm-rds-", ".srt", new File(mContext.getCacheDir(), "recordings"));
                mSubtitles = new RdsSubtitleWriter(
                        new BufferedWriter(
                                new OutputStreamWriter(
                                        new FileOutputStream(mSubtitleFile),
                                        StandardCharsets.UTF_8
                                )
                        )
                );
            } catch (IOException | SecurityException error) {
                Log.e("RFM-Recording", "Cannot create RDS subtitles", error);
                discardSubtitles();
            }
        } catch (RecordError e) {
            discardSubtitles();
            if (target != null) {
                target.abort();
            }
            mRecordingTarget = null;
            throw e;
        } catch (IllegalStateException e) {
            discardSubtitles();
            if (target != null) {
                target.abort();
            }
            mRecordingTarget = null;
            throw new RecordError(e.getMessage());
        } catch (IOException e) {
            discardSubtitles();
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

    /**
     * Calls when AudioRecord receive new packet of data
     * @param data New data
     * @param length Length of data
     * @param encoded Pointer to byte array of encoded data (output)
     * @return Length of payload encoded array
     * @throws IOException If happens error
     */
    protected abstract int onReceivedData(final short[] data, final int length, final byte[] encoded) throws IOException;

    /**
     * Calls when recording is finished.
     */
    protected abstract void onFinishRecording();
}
