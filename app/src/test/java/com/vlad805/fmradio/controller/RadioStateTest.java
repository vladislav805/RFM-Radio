package com.vlad805.fmradio.controller;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

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

        state.clear();

        assertEquals("", state.getPs());
        assertEquals("", state.getRt());
        assertEquals("", state.getPi());
        assertEquals("", state.getCountry());
        assertEquals(0, state.getPty());
        assertFalse(state.isStereo());
        assertEquals(RadioState.RMSSI_UNKNOWN, state.getRmssi());
    }
}
