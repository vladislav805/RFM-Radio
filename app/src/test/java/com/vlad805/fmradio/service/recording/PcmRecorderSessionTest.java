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
