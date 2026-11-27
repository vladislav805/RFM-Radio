package com.vlad805.fmradio.service.recording;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.vlad805.fmradio.service.fm.RecordError;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class PcmRecorderSessionTest {
    @Test
    public void writesWrappedPreRollBeforeLivePcm() throws Throwable {
        final PcmRecorderSession session = new PcmRecorderSession(10, 2, 1);
        final FakeRecorder recorder = new FakeRecorder();

        session.append(sequence(0, 12), 12);
        session.append(sequence(12, 18), 18);
        assertEquals(1000, session.start(recorder));
        session.append(sequence(30, 4), 4);
        session.stop();

        assertEquals(1000, recorder.initialDurationMillis);
        assertArrayEquals(sequence(10, 24), recorder.samples());
        assertEquals(1, recorder.stopCount());
    }

    @Test
    public void reportsDurationOfAvailablePartialHistory() throws Throwable {
        final PcmRecorderSession session = new PcmRecorderSession(10, 2, 5);
        final FakeRecorder recorder = new FakeRecorder();

        session.append(sequence(0, 10), 10);
        assertEquals(500, session.start(recorder));
        session.stop();

        assertEquals(500, recorder.initialDurationMillis);
        assertArrayEquals(sequence(0, 10), recorder.samples());
    }

    @Test
    public void writesOnlyLivePcmWhenPreRollIsDisabled() throws Throwable {
        final PcmRecorderSession session = new PcmRecorderSession(10, 2, 0);
        final FakeRecorder recorder = new FakeRecorder();

        session.append(sequence(0, 10), 10);
        assertEquals(0, session.start(recorder));
        session.append(sequence(10, 5), 5);
        session.stop();

        assertEquals(0, recorder.initialDurationMillis);
        assertArrayEquals(sequence(10, 5), recorder.samples());
    }

    @Test
    public void clearsHistoryBetweenPlaybackSessions() throws Throwable {
        final PcmRecorderSession session = new PcmRecorderSession(10, 2, 1);
        final FakeRecorder recorder = new FakeRecorder();

        session.append(sequence(0, 20), 20);
        session.clearHistory();
        session.append(sequence(20, 6), 6);
        assertEquals(300, session.start(recorder));
        session.stop();

        assertArrayEquals(sequence(20, 6), recorder.samples());
    }

    @Test
    public void failedRecorderStartDoesNotLeaveActiveSession() {
        final PcmRecorderSession session = new PcmRecorderSession(10, 2, 1);
        final IFMRecorder recorder = new FakeRecorder() {
            @Override
            public void startRecord(final int initialDurationMillis) throws RecordError {
                throw new RecordError("start failed");
            }
        };

        try {
            session.start(recorder);
            fail("Expected RecordError");
        } catch (RecordError expected) {
            assertFalse(session.isRecording());
        }
    }

    @Test
    public void stopWaitsUntilRecorderStartCompletes() throws Exception {
        final PcmRecorderSession session = new PcmRecorderSession(10, 2, 1);
        final BlockingRecorder recorder = new BlockingRecorder();
        final Thread startThread = new Thread(() -> {
            try {
                session.start(recorder);
            } catch (RecordError error) {
                throw new AssertionError(error);
            }
        });

        startThread.start();
        assertTrue(recorder.startEntered.await(1, TimeUnit.SECONDS));

        final Thread stopThread = new Thread(session::stop);
        stopThread.start();
        Thread.sleep(50);
        assertTrue(stopThread.isAlive());

        recorder.allowStart.countDown();
        startThread.join(1000);
        stopThread.join(1000);

        assertFalse(startThread.isAlive());
        assertFalse(stopThread.isAlive());
        assertEquals(1, recorder.stopCount());
    }

    @Test
    public void replaysRdsHistoryRelativeToWrappedPreRollAndLivePcm() throws Throwable {
        final PcmRecorderSession session = new PcmRecorderSession(10, 2, 1);
        final FakeRecorder recorder = new FakeRecorder();
        final RdsSnapshot first = new RdsSnapshot(99800, "FIRST", "", "1234");
        final RdsSnapshot second = new RdsSnapshot(99900, "SECOND", "", "5678");
        session.updateRds(first);
        session.append(new short[20], 20);
        session.updateRds(second);
        session.append(new short[10], 10);
        session.start(recorder); // Retains samples [10, 30), second station begins at sample 20.
        session.append(new short[10], 10);
        session.updateRds(first);
        session.stop();

        assertEquals(java.util.Arrays.asList(0L, 500L, 1500L), recorder.rdsTimes);
        assertEquals(java.util.Arrays.asList(first, second, first), recorder.rdsStates);
    }

    @Test
    public void keepsRdsBaselineAfterHistoryHasExpired() throws Throwable {
        final PcmRecorderSession session = new PcmRecorderSession(10, 2, 1);
        final FakeRecorder recorder = new FakeRecorder();
        final RdsSnapshot state = new RdsSnapshot(99800, "RADIO", "", "");
        session.updateRds(state);
        session.append(new short[100], 100);
        session.start(recorder);
        session.stop();

        assertEquals(java.util.Arrays.asList(0L), recorder.rdsTimes);
        assertEquals(java.util.Arrays.asList(state), recorder.rdsStates);
    }

    @Test
    public void startsRdsAtZeroWithoutPreRollAndSkipsDuplicateStates() throws Throwable {
        final PcmRecorderSession session = new PcmRecorderSession(10, 2, 0);
        final FakeRecorder recorder = new FakeRecorder();
        final RdsSnapshot state = new RdsSnapshot(99800, "RADIO", "", "");
        session.append(new short[100], 100);
        session.updateRds(state);
        session.start(recorder);
        session.updateRds(state);
        session.append(new short[10], 10);
        session.updateRds(RdsSnapshot.EMPTY);
        session.stop();

        assertEquals(java.util.Arrays.asList(0L, 500L), recorder.rdsTimes);
        assertEquals(java.util.Arrays.asList(state, RdsSnapshot.EMPTY), recorder.rdsStates);
    }

    @Test
    public void clearsRdsHistoryWithPlaybackHistory() throws Throwable {
        final PcmRecorderSession session = new PcmRecorderSession(10, 2, 1);
        final FakeRecorder recorder = new FakeRecorder();
        session.updateRds(new RdsSnapshot(99800, "OLD", "", ""));
        session.append(new short[20], 20);
        session.clearHistory();
        session.start(recorder);
        session.stop();

        assertEquals(java.util.Arrays.asList(RdsSnapshot.EMPTY), recorder.rdsStates);
    }

    @Test
    public void throttlesSignalOnlyUpdatesToOnePerSecondWhileEmittingRdsImmediately() throws Throwable {
        final PcmRecorderSession session = new PcmRecorderSession(10, 2, 0);
        final FakeRecorder recorder = new FakeRecorder();
        final RdsSnapshot base = new RdsSnapshot(100000, "RADIO", "", "1234",
                com.vlad805.fmradio.models.RtPlusInfo.EMPTY, "DE", 10, false, true, -80, 15);
        final RdsSnapshot jitterAt300ms = new RdsSnapshot(100000, "RADIO", "", "1234",
                com.vlad805.fmradio.models.RtPlusInfo.EMPTY, "DE", 10, false, true, -78, 16);
        final RdsSnapshot rdsChangeAt500ms = new RdsSnapshot(100000, "RADIO", "New RT", "1234",
                com.vlad805.fmradio.models.RtPlusInfo.EMPTY, "DE", 10, false, true, -78, 16);
        final RdsSnapshot signalAt1600ms = new RdsSnapshot(100000, "RADIO", "New RT", "1234",
                com.vlad805.fmradio.models.RtPlusInfo.EMPTY, "DE", 10, false, true, -70, 22);

        session.updateRds(base);
        session.start(recorder);
        session.append(new short[6], 6); // 300 ms
        session.updateRds(jitterAt300ms); // throttled (< 1000 ms since last)
        session.append(new short[4], 4); // 500 ms
        session.updateRds(rdsChangeAt500ms); // core RDS changed -> immediate
        session.append(new short[22], 22); // 1600 ms (1100 ms since 500 ms)
        session.updateRds(signalAt1600ms); // >= 1000 ms -> recorded
        session.stop();

        assertEquals(java.util.Arrays.asList(0L, 500L, 1600L), recorder.rdsTimes);
        assertEquals(java.util.Arrays.asList(base, rdsChangeAt500ms, signalAt1600ms), recorder.rdsStates);
    }

    @Test
    public void computesHalfSecondDurationForStereo44100And48000Formats() throws Throwable {
        for (final PcmFormat format : new PcmFormat[]{
                PcmFormat.PCM16_STEREO_44100,
                PcmFormat.PCM16_STEREO_48000
        }) {
            final PcmRecorderSession session = new PcmRecorderSession(
                    format.sampleRateHz(),
                    format.channelCount(),
                    1
            );
            final FakeRecorder recorder = new FakeRecorder();
            final int halfSecondInterleavedSamples = format.sampleRateHz();
            session.append(new short[halfSecondInterleavedSamples], halfSecondInterleavedSamples);

            assertEquals(500, session.start(recorder));
            session.stop();
            assertEquals(500, recorder.initialDurationMillis);
            assertEquals(halfSecondInterleavedSamples, recorder.samples().length);
        }
    }

    @Test
    public void stopAsyncReturnsWithoutBlockingAndPublishesOnce() throws Throwable {
        final PcmRecorderSession session = new PcmRecorderSession(10, 2, 0);
        final CountDownLatch stopEntered = new CountDownLatch(1);
        final CountDownLatch allowStopFinish = new CountDownLatch(1);
        final CountDownLatch stopCompleted = new CountDownLatch(1);
        final FakeRecorder recorder = new FakeRecorder() {
            @Override
            public void stopRecord() {
                stopEntered.countDown();
                try {
                    allowStopFinish.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                super.stopRecord();
                stopCompleted.countDown();
            }
        };

        session.start(recorder);
        session.append(sequence(1, 6), 6);

        // Must return immediately even while stopRecord() is blocked on slow I/O.
        session.stopAsync();
        assertFalse(session.isRecording());
        assertTrue(stopEntered.await(1, TimeUnit.SECONDS));

        // Repeated stopAsync() calls must be no-ops and never double-publish.
        session.stopAsync();
        assertEquals(0, recorder.stopCount());

        allowStopFinish.countDown();
        assertTrue(stopCompleted.await(1, TimeUnit.SECONDS));
        assertEquals(1, recorder.stopCount());
        assertArrayEquals(sequence(1, 6), recorder.samples());
    }

    @Test
    public void failAsyncAbortsActiveRecordingOnceAndRejectsFurtherPcm() throws Throwable {
        final PcmRecorderSession session = new PcmRecorderSession(10, 2, 0);
        final CountDownLatch failedLatch = new CountDownLatch(1);
        final List<Throwable> failures = new ArrayList<>();
        final FakeRecorder recorder = new FakeRecorder() {
            @Override
            public void failRecord(final Throwable error) {
                failures.add(error);
                failedLatch.countDown();
            }
        };

        session.start(recorder);
        session.append(sequence(1, 4), 4);
        final RuntimeException cause = new RuntimeException("FM route lost");
        session.failAsync(cause);

        assertFalse(session.isRecording());
        assertTrue(failedLatch.await(1, TimeUnit.SECONDS));

        // Further PCM and manual stop after failure must not publish or fail again.
        session.append(sequence(10, 4), 4);
        session.stopAsync();
        session.failAsync(new RuntimeException("duplicate"));

        assertEquals(1, failures.size());
        assertEquals(cause, failures.get(0));
        assertEquals(0, recorder.stopCount());
        assertArrayEquals(sequence(1, 4), recorder.samples());
    }

    private static short[] sequence(final int start, final int length) {
        final short[] result = new short[length];
        for (int i = 0; i < length; i++) {
            result[i] = (short) (start + i);
        }
        return result;
    }

    private static class FakeRecorder implements IFMRecorder {
        private final List<Short> received = new ArrayList<>();
        private int initialDurationMillis;
        private int stopCount;
        private final List<Long> rdsTimes = new ArrayList<>();
        private final List<RdsSnapshot> rdsStates = new ArrayList<>();

        @Override
        public void startRecord(final int initialDurationMillis) throws RecordError {
            this.initialDurationMillis = initialDurationMillis;
        }

        @Override
        public synchronized void record(final short[] data, final int length) {
            for (int i = 0; i < length; i++) {
                received.add(data[i]);
            }
        }

        @Override
        public void recordRds(final long positionMillis, final RdsSnapshot snapshot) {
            rdsTimes.add(positionMillis);
            rdsStates.add(snapshot);
        }

        @Override
        public void stopRecord() {
            stopCount++;
        }

        protected int stopCount() {
            return stopCount;
        }

        private synchronized short[] samples() {
            final short[] result = new short[received.size()];
            for (int i = 0; i < received.size(); i++) {
                result[i] = received.get(i);
            }
            return result;
        }
    }

    private static final class BlockingRecorder extends FakeRecorder {
        private final CountDownLatch startEntered = new CountDownLatch(1);
        private final CountDownLatch allowStart = new CountDownLatch(1);

        @Override
        public void startRecord(final int initialDurationMillis) {
            startEntered.countDown();
            boolean interrupted = false;
            while (true) {
                try {
                    allowStart.await();
                    break;
                } catch (InterruptedException error) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
