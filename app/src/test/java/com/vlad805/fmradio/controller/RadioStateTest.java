package com.vlad805.fmradio.controller;

import com.vlad805.fmradio.models.RtPlusInfo;

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
        state.setRtPlus(new RtPlusInfo("Title", "Artist", "Album"));

        assertEquals("Title", state.getRtPlus().getTitle());
        assertEquals("Artist", state.getRtPlus().getArtist());
        assertEquals("Album", state.getRtPlus().getAlbum());
        assertEquals("Artist — Title (Album)", state.getRtPlus().getFormattedText());

        state.clear();

        assertEquals("", state.getPs());
        assertEquals("", state.getRt());
        assertEquals("", state.getPi());
        assertEquals("", state.getCountry());
        assertEquals(0, state.getPty());
        assertFalse(state.isStereo());
        assertEquals(RadioState.RMSSI_UNKNOWN, state.getRmssi());
        assertTrue(state.getRtPlus().isEmpty());
    }

    @Test
    public void handlesNullRtPlusValues() {
        final RadioState state = new RadioState();
        state.setRtPlus(null);

        assertTrue(state.getRtPlus().isEmpty());
        assertEquals("", state.getRtPlus().getFormattedText());
    }
}
