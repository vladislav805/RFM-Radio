package com.vlad805.fmradio.controller;

import com.vlad805.fmradio.models.RtPlusInfo;
import com.vlad805.fmradio.service.fm.RadioStatePatch;
import com.vlad805.fmradio.view.SignalStrength;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
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
        state.setRtPlus(new RtPlusInfo(Map.of("ITEM.TITLE", "Title", "ITEM.ARTIST", "Artist", "ITEM.ALBUM", "Album")));
        state.setAf(new int[]{87500, 101700});

        assertEquals("Title", state.getRtPlus().get("ITEM.TITLE"));
        assertEquals("Artist", state.getRtPlus().get("ITEM.ARTIST"));
        assertEquals("Album", state.getRtPlus().get("ITEM.ALBUM"));
        assertEquals(2, state.getAf().length);
        assertEquals(87500, state.getAf()[0]);
        assertEquals(101700, state.getAf()[1]);
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
        assertEquals(0, state.getAf().length);
    }

    @Test
    public void handlesNullRtPlusValues() {
        final RadioState state = new RadioState();
        state.setRtPlus(null);

        assertTrue(state.getRtPlus().isEmpty());
    }

    @Test
    public void preservesRtPlusFieldsOnPartialUpdates() {
        final RadioState state = new RadioState();
        state.setRtPlus(new RtPlusInfo(Map.of("ITEM.TITLE", "Title A", "ITEM.ARTIST", "Artist A", "ITEM.ALBUM", "Album A")));

        final RadioStatePatch update = new RadioStatePatch();
        update.setRtPlusFields(Map.of("ITEM.TITLE", "Title B"));
        state.applyPatch(update);

        assertEquals("Title B", state.getRtPlus().get("ITEM.TITLE"));
        assertEquals("Artist A", state.getRtPlus().get("ITEM.ARTIST"));
        assertEquals("Album A", state.getRtPlus().get("ITEM.ALBUM"));
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

    @Test
    public void applyStatusClearsTransientDataOnIdleWhilePreservingFrequency() {
        final RadioState state = new RadioState();
        state.applyFrequency(101700);
        state.applyStatus(TunerStatus.ENABLED);
        state.setPs("ROCK FM");
        state.setRt("Now playing");
        state.setPi("1234");
        state.setCountry("DE");
        state.setPty(11);
        state.setStereo(true);
        state.setRmssi(-65);
        state.setSinr(14);
        state.setRtPlus(new RtPlusInfo(Map.of("ITEM.TITLE", "Song", "ITEM.ARTIST", "Band", "ITEM.ALBUM", "LP")));
        state.setAf(new int[]{102100});

        assertEquals(RadioState.SET_STATUS, state.applyStatus(TunerStatus.DISABLING));
        assertEquals(TunerStatus.DISABLING, state.getStatus());

        final int idleMask = state.applyStatus(TunerStatus.IDLE);
        assertEquals(TunerStatus.IDLE, state.getStatus());
        assertEquals(101700, state.getFrequency());
        assertEquals("", state.getPs());
        assertEquals("", state.getRt());
        assertEquals("", state.getPi());
        assertEquals("", state.getCountry());
        assertEquals(0, state.getPty());
        assertFalse(state.isStereo());
        assertEquals(RadioState.RMSSI_UNKNOWN, state.getRmssi());
        assertEquals(RadioState.SINR_UNKNOWN, state.getSinr());
        assertTrue(state.getRtPlus().isEmpty());
        assertEquals(0, state.getAf().length);

        final int expectedClearedMask =
                RadioState.SET_STATUS | RadioState.SET_RMSSI | RadioState.SET_SINR
                        | RadioState.SET_PS | RadioState.SET_RT | RadioState.SET_PTY
                        | RadioState.SET_PI | RadioState.SET_COUNTRY | RadioState.SET_STEREO
                        | RadioState.SET_RT_PLUS | RadioState.SET_AF | RadioState.SET_TP;
        assertEquals(expectedClearedMask, idleMask);
    }

    @Test
    public void trafficProgrammeSurvivesUnrelatedUpdatesAndClearsOnStationResetOrStop() {
        final RadioState state = new RadioState();
        final RadioStatePatch patch = new RadioStatePatch();
        assertFalse(state.hasTrafficProgramme());
        patch.setTp(true);
        assertEquals(RadioState.SET_TP, state.applyPatch(patch));
        state.applyPatch(new RadioStatePatch());
        assertTrue(state.hasTrafficProgramme());
        patch.setTp(false);
        assertEquals(RadioState.SET_TP, state.applyPatch(patch));
        assertFalse(state.hasTrafficProgramme());
        patch.setTp(true);
        state.applyPatch(patch);
        state.applyStatus(TunerStatus.IDLE);
        assertFalse(state.hasTrafficProgramme());
    }

    @Test
    public void applyFrequencyResetsSignalMetricsOnlyWhenFrequencyChanges() {
        final RadioState state = new RadioState();
        state.applyFrequency(98500);
        state.setRmssi(-72);
        state.setSinr(10);
        state.setAf(new int[]{99100});

        // Same frequency keeps existing signal/AF values
        final int sameMask = state.applyFrequency(98500);
        assertEquals(RadioState.SET_FREQUENCY, sameMask);
        assertEquals(-72, state.getRmssi());
        assertEquals(10, state.getSinr());
        assertArrayEquals(new int[]{99100}, state.getAf());

        // Different frequency clears signal/AF
        final int changedMask = state.applyFrequency(101700);
        assertEquals(
                RadioState.SET_FREQUENCY | RadioState.SET_RMSSI | RadioState.SET_SINR | RadioState.SET_AF,
                changedMask
        );
        assertEquals(101700, state.getFrequency());
        assertEquals(RadioState.RMSSI_UNKNOWN, state.getRmssi());
        assertEquals(RadioState.SINR_UNKNOWN, state.getSinr());
        assertEquals(0, state.getAf().length);
    }

    @Test
    public void applyPatchAppliesFieldsPreservesNullsAndSupportsPartialRtPlus() {
        final RadioState state = new RadioState();
        state.applyFrequency(100000);
        state.setRmssi(-60);
        state.setSinr(15);
        state.setAf(new int[]{100500});

        final RadioStatePatch fullPatch = new RadioStatePatch();
        fullPatch.setFrequency(101700);
        fullPatch.setPs("STATION");
        fullPatch.setRt("Hello World");
        fullPatch.setPi("ABCD");
        fullPatch.setCountry("UA");
        fullPatch.setPty(4);
        fullPatch.setStereo(true);
        fullPatch.setRtPlusFields(Map.of("ITEM.TITLE", "Title A", "ITEM.ARTIST", "Artist A", "ITEM.ALBUM", "Album A"));

        final int fullMask = state.applyPatch(fullPatch);
        assertEquals(
                RadioState.SET_FREQUENCY | RadioState.SET_RMSSI | RadioState.SET_SINR | RadioState.SET_AF
                        | RadioState.SET_PS | RadioState.SET_RT | RadioState.SET_PI
                        | RadioState.SET_COUNTRY | RadioState.SET_PTY | RadioState.SET_STEREO
                        | RadioState.SET_RT_PLUS,
                fullMask
        );
        assertEquals(101700, state.getFrequency());
        assertEquals(RadioState.RMSSI_UNKNOWN, state.getRmssi());
        assertEquals(RadioState.SINR_UNKNOWN, state.getSinr());
        assertEquals(0, state.getAf().length);
        assertEquals("STATION", state.getPs());
        assertEquals("Hello World", state.getRt());
        assertEquals("ABCD", state.getPi());
        assertEquals("UA", state.getCountry());
        assertEquals(4, state.getPty());
        assertTrue(state.isStereo());
        assertEquals("Title A", state.getRtPlus().get("ITEM.TITLE"));
        assertEquals("Artist A", state.getRtPlus().get("ITEM.ARTIST"));
        assertEquals("Album A", state.getRtPlus().get("ITEM.ALBUM"));

        // Partial patch updates only RT+ title and RMSSI, leaving other fields intact
        final RadioStatePatch partialPatch = new RadioStatePatch();
        partialPatch.setRmssi(-75);
        partialPatch.setRtPlusFields(Map.of("ITEM.TITLE", "Title B"));

        final int partialMask = state.applyPatch(partialPatch);
        assertEquals(RadioState.SET_RMSSI | RadioState.SET_RT_PLUS, partialMask);
        assertEquals(-75, state.getRmssi());
        assertEquals("STATION", state.getPs());
        assertEquals("Title B", state.getRtPlus().get("ITEM.TITLE"));
        assertEquals("Artist A", state.getRtPlus().get("ITEM.ARTIST"));
        assertEquals("Album A", state.getRtPlus().get("ITEM.ALBUM"));

        // Empty patch returns 0 and mutates nothing
        assertEquals(0, state.applyPatch(new RadioStatePatch()));
    }

    @Test
    public void applyRecordingAndSpeakerMutateStateWithExpectedMasks() {
        final RadioState state = new RadioState();

        assertEquals(RadioState.SET_RECORDING, state.applyRecordingStarted(5));
        assertTrue(state.isRecording());
        assertTrue(state.getRecordingDuration() >= 5L);

        assertEquals(RadioState.SET_RECORDING, state.applyRecordingProgress(12));
        assertTrue(state.getRecordingDuration() >= 12L);

        assertEquals(RadioState.SET_RECORDING, state.applyRecordingStopped());
        assertFalse(state.isRecording());
        assertEquals(-1L, state.getRecordingStarted());

        assertEquals(RadioState.SET_SPEAKER, state.applyForceSpeaker(true));
        assertTrue(state.isForceSpeaker());
        assertEquals(RadioState.SET_SPEAKER, state.applyForceSpeaker(false));
        assertFalse(state.isForceSpeaker());
    }
}
