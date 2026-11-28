package com.vlad805.fmradio.service.recording;

import com.vlad805.fmradio.service.fm.RecordError;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Keeps recent PCM samples and serializes pre-roll and live data into a recorder.
 */
public final class PcmRecorderSession {
    /** End marker for the writer queue. */
    private static final short[] FINISH = new short[0];

    /** Bounds pending PCM data if encoding is slower than capture. */
    private static final int WRITER_QUEUE_CAPACITY = 256;

    /** PCM sample rate in Hz. */
    private final int sampleRate;

    /** Number of interleaved PCM channels. */
    private final int channelCount;

    /** Circular buffer containing the most recent PCM samples. */
    private final short[] ring;

    /** Number of valid samples currently stored in {@link #ring}. */
    private int ringSize;

    /** Position at which the next sample is written into {@link #ring}. */
    private int ringWritePosition;

    /** Active asynchronous writer, or {@code null} when not recording. */
    private Writer writer;

    private long capturedSamples;
    private long recordingStartSample;
    private long lastSignalSample = Long.MIN_VALUE;
    private RdsSnapshot currentRds = RdsSnapshot.EMPTY;
    private final Deque<RdsChange> rdsHistory = new ArrayDeque<>();

    private static final class RdsChange {
        final long sample;
        final RdsSnapshot snapshot;

        RdsChange(final long sample, final RdsSnapshot snapshot) {
            this.sample = sample;
            this.snapshot = snapshot;
        }
    }

    /** Timestamps changes against captured PCM, independently of encoder progress. */
    public synchronized void updateRds(final RdsSnapshot snapshot) {
        if (snapshot.equals(currentRds)) {
            return;
        }
        if (snapshot.sameRdsCore(currentRds)) {
            final long oneSecondSamples = (long) sampleRate * channelCount;
            if (lastSignalSample != Long.MIN_VALUE && (capturedSamples - lastSignalSample) < oneSecondSamples) {
                return;
            }
        }
        currentRds = snapshot;
        lastSignalSample = capturedSamples;
        // Multiple events between PCM reads describe the same audio position.
        if (!rdsHistory.isEmpty() && rdsHistory.peekLast().sample == capturedSamples) {
            rdsHistory.removeLast();
        }
        rdsHistory.addLast(new RdsChange(capturedSamples, snapshot));
        pruneRdsHistory();
        if (writer != null) {
            writer.recorder.recordRds(toMillis(capturedSamples - recordingStartSample), snapshot);
        }
    }

    private long toMillis(final long samples) {
        return samples * 1000L / (sampleRate * (long) channelCount);
    }

    /** Keeps one baseline preceding the oldest retained PCM plus subsequent changes. */
    private void pruneRdsHistory() {
        final long oldestSample = capturedSamples - ringSize;
        while (rdsHistory.size() > 1) {
            final RdsChange first = rdsHistory.removeFirst();
            if (rdsHistory.peekFirst().sample > oldestSample) {
                rdsHistory.addFirst(first);
                break;
            }
        }
    }

    /**
     * Creates a PCM session.
     *
     * @param sampleRate PCM sample rate in Hz
     * @param channelCount Number of interleaved channels
     * @param preRollSeconds Number of seconds retained before recording starts
     */
    public PcmRecorderSession(final int sampleRate, final int channelCount, final int preRollSeconds) {
        this.sampleRate = sampleRate;
        this.channelCount = channelCount;
        ring = new short[Math.max(0, sampleRate * channelCount * preRollSeconds)];
    }

    /**
     * Adds captured PCM to the history and active recording.
     *
     * @param data PCM sample buffer
     * @param length Number of valid samples in the buffer
     */
    public synchronized void append(final short[] data, final int length) {
        final int safeLength = Math.max(0, Math.min(length, data.length));
        appendToRing(data, safeLength);
        capturedSamples += safeLength;
        pruneRdsHistory();

        if (writer != null && safeLength > 0) {
            writer.enqueue(Arrays.copyOf(data, safeLength));
        }
    }

    /**
     * Starts recording with a stable snapshot of the available history.
     *
     * @param recorder Destination recorder
     * @return Duration of the included history in milliseconds
     * @throws RecordError If the destination cannot be started
     */
    public synchronized int start(final IFMRecorder recorder) throws RecordError {
        if (writer != null) {
            throw new RecordError("Recording is already active");
        }

        final short[] snapshot = snapshot();
        final int preRollMillis = (int) ((snapshot.length * 1000L) / (sampleRate * channelCount));
        recorder.startRecord(preRollMillis);

        recordingStartSample = capturedSamples - snapshot.length;
        RdsSnapshot initial = RdsSnapshot.EMPTY;

        for (final RdsChange change : rdsHistory) {
            if (change.sample <= recordingStartSample) {
                initial = change.snapshot;
            }
        }

        recorder.recordRds(0, initial);

        for (final RdsChange change : rdsHistory) {
            if (change.sample > recordingStartSample) {
                recorder.recordRds(toMillis(change.sample - recordingStartSample), change.snapshot);
            }
        }

        final Writer newWriter = new Writer(recorder);
        if (snapshot.length > 0) {
            newWriter.enqueue(snapshot);
        }
        writer = newWriter;
        newWriter.start();
        return preRollMillis;
    }

    /** Stops the writer and waits until all queued PCM has been encoded and finalized. */
    public void stop() {
        final Writer activeWriter = detachWriter();
        if (activeWriter == null) {
            return;
        }

        activeWriter.finish();
        activeWriter.join();
    }

    /**
     * Detaches the active recorder and requests asynchronous drain and finalization
     * on the writer thread without blocking the caller.
     */
    public void stopAsync() {
        final Writer activeWriter = detachWriter();
        if (activeWriter == null) {
            return;
        }

        activeWriter.finish();
    }

    /**
     * Detaches the active recorder and aborts it asynchronously on the writer thread
     * after an unexpected capture failure.
     */
    public void failAsync(final Throwable error) {
        final Writer activeWriter = detachWriter();
        if (activeWriter == null) {
            return;
        }

        activeWriter.fail(error);
    }

    private synchronized Writer detachWriter() {
        final Writer activeWriter = writer;
        writer = null;
        return activeWriter;
    }

    /** @return Whether a destination recorder is currently active. */
    public synchronized boolean isRecording() {
        return writer != null;
    }

    /**
     * Discards PCM retained from the current playback session.
     * Active recording data is never removed.
     */
    public synchronized void clearHistory() {
        if (writer != null) {
            return;
        }
        ringSize = 0;
        ringWritePosition = 0;
        capturedSamples = 0;
        lastSignalSample = Long.MIN_VALUE;
        currentRds = RdsSnapshot.EMPTY;
        rdsHistory.clear();
    }

    /** Appends samples to the circular history buffer. */
    private void appendToRing(final short[] data, final int length) {
        if (ring.length == 0 || length == 0) {
            return;
        }

        if (length >= ring.length) {
            System.arraycopy(data, length - ring.length, ring, 0, ring.length);
            ringSize = ring.length;
            ringWritePosition = 0;
            return;
        }

        final int firstPart = Math.min(length, ring.length - ringWritePosition);
        System.arraycopy(data, 0, ring, ringWritePosition, firstPart);
        System.arraycopy(data, firstPart, ring, 0, length - firstPart);
        ringWritePosition = (ringWritePosition + length) % ring.length;
        ringSize = Math.min(ring.length, ringSize + length);
    }

    /** @return Chronologically ordered copy of the current PCM history. */
    private short[] snapshot() {
        final short[] result = new short[ringSize];
        if (ringSize == 0) {
            return result;
        }

        final int start = (ringWritePosition - ringSize + ring.length) % ring.length;
        final int firstPart = Math.min(ringSize, ring.length - start);
        System.arraycopy(ring, start, result, 0, firstPart);
        System.arraycopy(ring, 0, result, firstPart, ringSize - firstPart);
        return result;
    }

    /** Writes queued PCM on a dedicated thread to avoid blocking audio capture. */
    private static final class Writer implements Runnable {
        /** Destination receiving ordered PCM chunks. */
        private final IFMRecorder recorder;

        /** Bounded queue providing backpressure instead of unbounded memory growth. */
        private final ArrayBlockingQueue<short[]> queue = new ArrayBlockingQueue<>(WRITER_QUEUE_CAPACITY);

        /** Worker that serializes all calls to the destination recorder. */
        private final Thread thread = new Thread(this, "FmPcmWriter");

        /** Set once stop has been requested so finish() never blocks on a full queue. */
        private volatile boolean finishRequested;

        /** Non-null when the recording was aborted by a capture failure. */
        private volatile Throwable failure;

        private Writer(final IFMRecorder recorder) {
            this.recorder = recorder;
        }

        /** Adds one immutable PCM chunk, waiting for queue capacity when required. */
        private void enqueue(final short[] data) {
            boolean interrupted = false;
            while (true) {
                try {
                    queue.put(data);
                    break;
                } catch (InterruptedException error) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        /** Starts the writer thread. */
        private void start() {
            thread.start();
        }

        /** Requests termination after all previously submitted PCM without blocking the caller. */
        private void finish() {
            finishRequested = true;
            queue.offer(FINISH);
        }

        /**
         * Requests finish due to capture failure without blocking the caller.
         * Remaining valid PCM already queued before the failure is drained, then {@link IFMRecorder#failRecord}
         * is invoked so the partial recording can be finalized and reported as interrupted.
         */
        private void fail(final Throwable error) {
            failure = error != null ? error : new RuntimeException("Recording failed");
            finishRequested = true;
            queue.offer(FINISH);
        }

        /** Waits until all queued PCM has been written and finalized. */
        private void join() {
            boolean interrupted = false;
            while (thread.isAlive()) {
                try {
                    thread.join();
                } catch (InterruptedException error) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void run() {
            try {
                while (true) {
                    short[] data = queue.poll();
                    if (data == null) {
                        if (finishRequested) {
                            break;
                        }
                        data = queue.take();
                    }
                    if (data == FINISH) {
                        break;
                    }
                    recorder.record(data, data.length);
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } finally {
                if (failure != null) {
                    recorder.failRecord(failure);
                } else {
                    recorder.stopRecord();
                }
            }
        }
    }
}
