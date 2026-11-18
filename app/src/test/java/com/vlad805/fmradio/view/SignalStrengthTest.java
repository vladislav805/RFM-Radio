package com.vlad805.fmradio.view;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SignalStrengthTest {
    @Test
    public void mapsRmssiBoundariesToFiveBars() {
        assertEquals(0, SignalStrength.levelForRmssi(-106));
        assertEquals(1, SignalStrength.levelForRmssi(-105));
        assertEquals(1, SignalStrength.levelForRmssi(-91));
        assertEquals(2, SignalStrength.levelForRmssi(-90));
        assertEquals(2, SignalStrength.levelForRmssi(-81));
        assertEquals(3, SignalStrength.levelForRmssi(-80));
        assertEquals(3, SignalStrength.levelForRmssi(-71));
        assertEquals(4, SignalStrength.levelForRmssi(-70));
        assertEquals(4, SignalStrength.levelForRmssi(-61));
        assertEquals(5, SignalStrength.levelForRmssi(-60));
    }

    @Test
    public void handlesMetricFallbackWhenSinrIsUnknown() {
        assertEquals(5, SignalStrength.levelForMetrics(-60, Integer.MIN_VALUE));
        assertEquals(0, SignalStrength.levelForMetrics(-106, Integer.MIN_VALUE));
    }

    @Test
    public void usesMinimumWhileBothPresentAndRecoversAfterClear() {
        // Stale low SINR limits strong RMSSI until SINR is explicitly cleared.
        assertEquals(0, SignalStrength.levelForMetrics(-60, -5));
        assertEquals(5, SignalStrength.levelForMetrics(-60, Integer.MIN_VALUE));

        // Stale low RMSSI limits strong SINR until RMSSI is explicitly cleared.
        assertEquals(0, SignalStrength.levelForMetrics(-106, 16));
        assertEquals(5, SignalStrength.levelForMetrics(Integer.MIN_VALUE, 16));

        // No metrics present yields zero bars.
        assertEquals(0, SignalStrength.levelForMetrics(Integer.MIN_VALUE, Integer.MIN_VALUE));
    }
}
