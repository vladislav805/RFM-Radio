package com.vlad805.fmradio.service.audio;

import android.media.AudioManager;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

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
}
