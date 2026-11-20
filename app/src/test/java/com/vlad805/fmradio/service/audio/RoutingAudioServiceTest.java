package com.vlad805.fmradio.service.audio;

import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioRecord;
import com.vlad805.fmradio.service.recording.PcmFormat;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class RoutingAudioServiceTest {
    @Test
    public void leavesFullVolumeAndMutedBuffersUntouched() {
        final short[] atMax = {1000, -1000, 0};
        RoutingAudioService.compensateExynosVolume(atMax, atMax.length, 15);
        assertArrayEquals(new short[]{1000, -1000, 0}, atMax);

        final short[] atMute = {500, -500};
        RoutingAudioService.compensateExynosVolume(atMute, atMute.length, 0);
        assertArrayEquals(new short[]{500, -500}, atMute);

        final short[] atUnknown = {500, -500};
        RoutingAudioService.compensateExynosVolume(atUnknown, atUnknown.length, -1);
        assertArrayEquals(new short[]{500, -500}, atUnknown);
    }

    @Test
    public void restoresAttenuatedSamplesToFullScaleAndClampsOverflow() {
        // Step 13 has chip gain 1024 (~ -6 dB vs 2047 full scale, factor ~1.999x).
        final short[] buf = {1024, -1024, 20000, -20000};
        RoutingAudioService.compensateExynosVolume(buf, buf.length, 13);
        assertEquals(2047, buf[0]);
        assertEquals(-2047, buf[1]);
        assertEquals(Short.MAX_VALUE, buf[2]);
        assertEquals(Short.MIN_VALUE, buf[3]);
    }

    @Test
    public void capsCompensationAt64xForLowestNonZeroSteps() {
        // Step 1 has chip gain 16 (2047 / 16 = 127.9x, capped to 64x).
        final short[] buf = {100, -100};
        RoutingAudioService.compensateExynosVolume(buf, buf.length, 1);
        assertEquals(6400, buf[0]);
        assertEquals(-6400, buf[1]);
    }

    @Test
    public void adjustsExynosStreamVolumeOnlyBySingleStepAndIgnoresUnmuteConstants() {
        assertEquals(8, RoutingAudioService.computeAdjustedStreamVolume(7, 15, AudioManager.ADJUST_RAISE));
        assertEquals(6, RoutingAudioService.computeAdjustedStreamVolume(7, 15, AudioManager.ADJUST_LOWER));
        assertEquals(15, RoutingAudioService.computeAdjustedStreamVolume(15, 15, AudioManager.ADJUST_RAISE));
        assertEquals(0, RoutingAudioService.computeAdjustedStreamVolume(0, 15, AudioManager.ADJUST_LOWER));

        // ADJUST_UNMUTE (100) and ADJUST_TOGGLE_MUTE (101) must not jump volume to max (15).
        assertEquals(4, RoutingAudioService.computeAdjustedStreamVolume(4, 15, AudioManager.ADJUST_UNMUTE));
        assertEquals(4, RoutingAudioService.computeAdjustedStreamVolume(4, 15, AudioManager.ADJUST_TOGGLE_MUTE));
        assertEquals(0, RoutingAudioService.computeAdjustedStreamVolume(4, 15, AudioManager.ADJUST_MUTE));
    }

    private static final class FakeCaptureCandidate implements RoutingAudioService.CaptureCandidate {
        boolean initialized = true;
        boolean formatMatches = true;
        boolean preferredAccepted = true;
        int recordingState = AudioRecord.RECORDSTATE_RECORDING;
        Integer[] routedSequence = {AudioDeviceInfo.TYPE_FM_TUNER};
        int routedIndex = 0;
        boolean stopped = false;
        boolean released = false;

        @Override
        public boolean isInitialized() {
            return initialized;
        }

        @Override
        public boolean matchesFormat(final PcmFormat format) {
            return formatMatches;
        }

        @Override
        public boolean setPreferredFmDevice() {
            return preferredAccepted;
        }

        @Override
        public void startRecording() {
        }

        @Override
        public int getRecordingState() {
            return recordingState;
        }

        @Override
        public Integer getRoutedDeviceType() {
            if (routedSequence.length == 0) {
                return null;
            }
            final int idx = Math.min(routedIndex++, routedSequence.length - 1);
            return routedSequence[idx];
        }

        @Override
        public void stop() {
            stopped = true;
        }

        @Override
        public void release() {
            released = true;
        }
    }

    @Test
    public void rejectsCandidateWhenPreferredDeviceIsRejectedAndFallsBackToVerifiedSource() {
        final FakeCaptureCandidate rejected = new FakeCaptureCandidate();
        rejected.preferredAccepted = false;
        final FakeCaptureCandidate valid = new FakeCaptureCandidate();
        final List<Integer> attemptedSources = new ArrayList<>();

        final RoutingAudioService.CaptureCandidate selected = RoutingAudioService.selectVerifiedFmCapture(
                new int[]{11, 0},
                PcmFormat.PCM16_STEREO_48000,
                source -> {
                    attemptedSources.add(source);
                    return source == 11 ? rejected : valid;
                },
                100,
                5
        );

        assertEquals(List.of(11, 0), attemptedSources);
        assertTrue(rejected.released);
        assertSame(valid, selected);
        assertFalse(valid.released);
    }

    @Test
    public void rejectsCaptureRoutedToMicrophoneWithoutSilentFallback() {
        final FakeCaptureCandidate micRouted = new FakeCaptureCandidate();
        micRouted.routedSequence = new Integer[]{AudioDeviceInfo.TYPE_BUILTIN_MIC};

        final RoutingAudioService.CaptureCandidate selected = RoutingAudioService.selectVerifiedFmCapture(
                new int[]{11},
                PcmFormat.PCM16_STEREO_48000,
                source -> micRouted,
                100,
                5
        );

        assertNull(selected);
        assertTrue(micRouted.stopped);
        assertTrue(micRouted.released);
    }

    @Test
    public void waitsForDelayedFmRouteEstablishmentWithinTimeout() {
        final FakeCaptureCandidate delayed = new FakeCaptureCandidate();
        delayed.routedSequence = new Integer[]{null, null, AudioDeviceInfo.TYPE_FM_TUNER};

        final RoutingAudioService.CaptureCandidate selected = RoutingAudioService.selectVerifiedFmCapture(
                new int[]{11},
                PcmFormat.PCM16_STEREO_48000,
                source -> delayed,
                200,
                5
        );

        assertNotNull(selected);
        assertSame(delayed, selected);
        assertFalse(delayed.released);
    }

    @Test
    public void detectsFmRouteLossDuringCapture() {
        assertTrue(RoutingAudioService.isVerifiedFmRoute(
                AudioRecord.RECORDSTATE_RECORDING,
                AudioDeviceInfo.TYPE_FM_TUNER
        ));
        assertFalse(RoutingAudioService.isVerifiedFmRoute(
                AudioRecord.RECORDSTATE_RECORDING,
                AudioDeviceInfo.TYPE_BUILTIN_MIC
        ));
        assertFalse(RoutingAudioService.isVerifiedFmRoute(
                AudioRecord.RECORDSTATE_STOPPED,
                AudioDeviceInfo.TYPE_FM_TUNER
        ));
    }
}
