package com.vlad805.fmradio.controller;

import com.vlad805.fmradio.models.RtPlusInfo;
import com.vlad805.fmradio.view.SignalStrength;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RadioStateTest {
    @Test
    public void clearsTransientTunerData() {
        final RadioState state = new RadioState();
        state.setPs("STATION");
        state.setRt("Radio text");
        state.setPi("7756");
        state.setCountry("UA");
        state.setPty(7);
        state.setStereo(true);
        state.setRmssi(-70);
        state.setSinr(12);
        state.setRtPlus(new RtPlusInfo("Title", "Artist", "Album"));

        assertEquals("Title", state.getRtPlus().getTitle());
        assertEquals("Artist", state.getRtPlus().getArtist());
        assertEquals("Album", state.getRtPlus().getAlbum());
        assertEquals("Artist — Title (Album)", state.getRtPlus().getFormattedText());
        assertEquals(-70, state.getRmssi());
        assertEquals(12, state.getSinr());

        state.clear();

        assertEquals("", state.getPs());
        assertEquals("", state.getRt());
        assertEquals("", state.getPi());
        assertEquals("", state.getCountry());
        assertEquals(0, state.getPty());
        assertFalse(state.isStereo());
        assertEquals(RadioState.RMSSI_UNKNOWN, state.getRmssi());
        assertEquals(RadioState.SINR_UNKNOWN, state.getSinr());
        assertTrue(state.getRtPlus().isEmpty());
    }

    @Test
    public void handlesNullRtPlusValues() {
        final RadioState state = new RadioState();
        state.setRtPlus(null);

        assertTrue(state.getRtPlus().isEmpty());
        assertEquals("", state.getRtPlus().getFormattedText());
    }

    @Test
    public void preservesRtPlusFieldsOnPartialUpdates() {
        final RadioState state = new RadioState();
        state.setRtPlus(new RtPlusInfo("Title A", "Artist A", "Album A"));

        final RtPlusInfo current = state.getRtPlus();
        final String newTitle = "Title B";
        final String newArtist = current != null ? current.getArtist() : "";
        final String newAlbum = current != null ? current.getAlbum() : "";

        state.setRtPlus(new RtPlusInfo(newTitle, newArtist, newAlbum));

        assertEquals("Title B", state.getRtPlus().getTitle());
        assertEquals("Artist A", state.getRtPlus().getArtist());
        assertEquals("Album A", state.getRtPlus().getAlbum());
    }

    @Test
    public void clearsStaleMetricWhenSwitchingSignalQualityMetric() {
        final RadioState state = new RadioState();
        state.setFrequency(101700);
        state.setSinr(-5);
        state.setRmssi(-60);
        assertEquals(0, SignalStrength.levelForMetrics(state.getRmssi(), state.getSinr()));

        // Null sinr patch resets SINR_UNKNOWN so strong RMSSI determines bars.
        state.setSinr(RadioState.SINR_UNKNOWN);
        assertEquals(5, SignalStrength.levelForMetrics(state.getRmssi(), state.getSinr()));

        // Reverse transition: weak RMSSI replaced by strong SINR after clearing RMSSI.
        state.setRmssi(-106);
        state.setSinr(16);
        assertEquals(0, SignalStrength.levelForMetrics(state.getRmssi(), state.getSinr()));
        state.setRmssi(RadioState.RMSSI_UNKNOWN);
        assertEquals(5, SignalStrength.levelForMetrics(state.getRmssi(), state.getSinr()));
    }
}
