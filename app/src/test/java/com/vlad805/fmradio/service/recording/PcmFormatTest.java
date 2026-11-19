package com.vlad805.fmradio.service.recording;

import com.vlad805.fmradio.enums.TunerDriver;
import com.vlad805.fmradio.service.audio.AudioSelection;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PcmFormatTest {
    @Test
    public void exposesExpectedRatesAndChannelsForStereoFormats() {
        assertEquals(44100, PcmFormat.PCM16_STEREO_44100.sampleRateHz());
        assertEquals(2, PcmFormat.PCM16_STEREO_44100.channelCount());

        assertEquals(48000, PcmFormat.PCM16_STEREO_48000.sampleRateHz());
        assertEquals(2, PcmFormat.PCM16_STEREO_48000.channelCount());
    }

    @Test
    public void matchesExactFormatAndRejectsMismatchedRateOrChannels() {
        assertTrue(PcmFormat.PCM16_STEREO_44100.matches(44100, 2));
        assertFalse(PcmFormat.PCM16_STEREO_44100.matches(48000, 2));
        assertFalse(PcmFormat.PCM16_STEREO_44100.matches(44100, 1));

        assertTrue(PcmFormat.PCM16_STEREO_48000.matches(48000, 2));
        assertFalse(PcmFormat.PCM16_STEREO_48000.matches(44100, 2));
        assertFalse(PcmFormat.PCM16_STEREO_48000.matches(48000, 1));
    }

    @Test
    public void routedV4l2Uses48kHzIndependentOfDriverIdentity() {
        final AudioSelection routedV4l2 = AudioSelection.resolve(TunerDriver.V4L2_QCOM, 26);
        final AudioSelection routedHelium = AudioSelection.resolve(TunerDriver.HAL_HELIUM, 26);
        final AudioSelection loopbackV4l2 = AudioSelection.resolve(TunerDriver.V4L2_QCOM, 25);

        assertEquals(AudioSelection.Path.QUALCOMM_ROUTED, routedV4l2.path());
        assertEquals(AudioSelection.Path.QUALCOMM_ROUTED, routedHelium.path());
        assertEquals(AudioSelection.Path.PCM_LOOPBACK, loopbackV4l2.path());

        assertEquals(PcmFormat.PCM16_STEREO_48000, routedV4l2.pcmFormat());
        assertEquals(PcmFormat.PCM16_STEREO_48000, routedHelium.pcmFormat());
        assertEquals(PcmFormat.PCM16_STEREO_44100, loopbackV4l2.pcmFormat());
    }
}
