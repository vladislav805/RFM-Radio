package com.vlad805.fmradio.service.audio;

import com.vlad805.fmradio.enums.TunerDriver;
import com.vlad805.fmradio.helper.TunerDriverDetector;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AudioSelectionTest {
    @Test
    public void resolvesMatrixAcrossSupportedApiLevels() {
        final int[] legacyApis = {23, 25};
        for (final int api : legacyApis) {
            final AudioSelection v4l2 = AudioSelection.resolve(TunerDriver.V4L2_QCOM, api);
            assertEquals(AudioSelection.Path.PCM_LOOPBACK, v4l2.path());
            assertFalse(v4l2.startsBeforeTuner());

            final AudioSelection helium = AudioSelection.resolve(TunerDriver.HAL_HELIUM, api);
            assertEquals(AudioSelection.Path.PCM_LOOPBACK, helium.path());
            assertTrue(helium.startsBeforeTuner());

            final AudioSelection none = AudioSelection.resolve(TunerDriver.NONE, api);
            assertEquals(AudioSelection.Path.NONE, none.path());
            assertFalse(none.startsBeforeTuner());
        }

        final int[] routedApis = {26, 31};
        for (final int api : routedApis) {
            final AudioSelection v4l2 = AudioSelection.resolve(TunerDriver.V4L2_QCOM, api);
            assertEquals(AudioSelection.Path.QUALCOMM_ROUTED, v4l2.path());
            assertFalse(v4l2.startsBeforeTuner());

            final AudioSelection helium = AudioSelection.resolve(TunerDriver.HAL_HELIUM, api);
            assertEquals(AudioSelection.Path.QUALCOMM_ROUTED, helium.path());
            assertTrue(helium.startsBeforeTuner());

            final AudioSelection none = AudioSelection.resolve(TunerDriver.NONE, api);
            assertEquals(AudioSelection.Path.NONE, none.path());
            assertFalse(none.startsBeforeTuner());
        }
    }

    @Test
    public void provisionalV4l2OverriddenByHeliumUsesHeliumStartOrdering() {
        final TunerDriver resolved = TunerDriverDetector.resolveNativeBackend(
                TunerDriver.V4L2_QCOM,
                "hal_helium"
        );
        final AudioSelection selection = AudioSelection.resolve(resolved, 31);

        assertEquals(TunerDriver.HAL_HELIUM, resolved);
        assertEquals(AudioSelection.Path.QUALCOMM_ROUTED, selection.path());
        assertTrue(selection.startsBeforeTuner());
    }
}
