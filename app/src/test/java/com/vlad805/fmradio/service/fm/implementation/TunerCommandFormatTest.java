package com.vlad805.fmradio.service.fm.implementation;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TunerCommandFormatTest {
    @Test
    public void formatsAtomicEnable() {
        assertEquals(
                "enable freq=76100 region=jp_wide spacing=50 stereo=1 soft_mute=0 antenna=2 af=1",
                TunerCommandFormat.enable(76100, 3, 1, true, false, 2, true)
        );
        assertEquals(
                "enable freq=87500 region=eu spacing=100 stereo=0 soft_mute=1 antenna=0 af=0",
                TunerCommandFormat.enable(87500, 1, 2, false, true, 0, false)
        );
    }

    @Test
    public void mapsStoredRegionIds() {
        assertEquals("eu", TunerCommandFormat.regionName(1));
        assertEquals("jp", TunerCommandFormat.regionName(2));
        assertEquals("jp_wide", TunerCommandFormat.regionName(3));
        assertEquals("us", TunerCommandFormat.regionName(4));
        assertEquals("eu", TunerCommandFormat.regionName(0));
        assertEquals("eu", TunerCommandFormat.regionName(99));
    }

    @Test
    public void mapsStoredSpacingIds() {
        assertEquals(50, TunerCommandFormat.spacingKhz(1));
        assertEquals(100, TunerCommandFormat.spacingKhz(2));
        assertEquals(200, TunerCommandFormat.spacingKhz(3));
        assertEquals(100, TunerCommandFormat.spacingKhz(0));
        assertEquals(100, TunerCommandFormat.spacingKhz(99));
    }
}
