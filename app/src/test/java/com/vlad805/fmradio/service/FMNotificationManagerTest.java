package com.vlad805.fmradio.service;

import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class FMNotificationManagerTest {
    private static String frequencyLabel(final int frequencyKhz) {
        return String.format(Locale.US, "%.1f MHz", frequencyKhz / 1000d);
    }

    @Test
    public void playbackTitleAndBody_withRdsShown_prefersPsOverFavoriteAndDisplaysRt() {
        final String label = frequencyLabel(101700);

        assertEquals(
                "101.7 MHz | Station PS",
                FMNotificationManager.formatPlaybackTitle(label, "  Station PS  ", "Favorite name", true)
        );
        assertEquals(
                "Current Track - Artist",
                FMNotificationManager.formatPlaybackBody("Current Track - Artist", true)
        );
    }

    @Test
    public void playbackTitleAndBody_withRdsShownAndEmptyPs_fallsBackToFavorite() {
        final String label = frequencyLabel(101700);

        assertEquals(
                "101.7 MHz | Favorite name",
                FMNotificationManager.formatPlaybackTitle(label, "", "Favorite name", true)
        );
        assertEquals("", FMNotificationManager.formatPlaybackBody("", true));
    }

    @Test
    public void playbackTitleAndBody_withRdsHidden_usesFavoriteAndEmptyBody() {
        final String label = frequencyLabel(101700);

        assertEquals(
                "101.7 MHz | Favorite name",
                FMNotificationManager.formatPlaybackTitle(label, "Station PS", "Favorite name", false)
        );
        assertEquals(
                "",
                FMNotificationManager.formatPlaybackBody("Current Track - Artist", false)
        );
    }

    @Test
    public void playbackTitleAndBody_whenPsAndFavoriteAbsent_rendersFrequencyAlone() {
        final String label = frequencyLabel(101700);

        assertEquals(
                "101.7 MHz",
                FMNotificationManager.formatPlaybackTitle(label, null, null, true)
        );
        assertEquals(
                "101.7 MHz",
                FMNotificationManager.formatPlaybackTitle(label, "Station PS", null, false)
        );
        assertEquals("", FMNotificationManager.formatPlaybackBody(null, true));
    }

    @Test
    public void compactActionIndices_reflectRecordingState() {
        assertArrayEquals(new int[]{1, 2}, FMNotificationManager.compactActionIndices(false));
        assertArrayEquals(new int[]{1}, FMNotificationManager.compactActionIndices(true));
    }

    @Test
    public void recordingNotificationTag_prefersUriAndFallsBackToFilePath() {
        assertEquals(
                "recording:content://media/external/audio/media/42",
                FMNotificationManager.recordingNotificationTag(
                        "content://media/external/audio/media/42",
                        "/storage/emulated/0/Music/test.wav"
                )
        );
        assertEquals(
                "recording:/storage/emulated/0/Music/test.wav",
                FMNotificationManager.recordingNotificationTag(
                        null,
                        "/storage/emulated/0/Music/test.wav"
                )
        );
    }

    @Test
    public void preservesNotificationAndChannelIdentifiers() {
        assertEquals(1027, FMNotificationManager.NOTIFICATION_ID);
        assertEquals(1029, FMNotificationManager.NOTIFICATION_RECORD_ID);
        assertEquals("playback_channel_v3", FMNotificationManager.CHANNEL_ID);
        assertEquals("record_channel", FMNotificationManager.CHANNEL_RECORD_ID);
        assertEquals("recording_channel_v2", FMNotificationManager.CHANNEL_RECORDING_ID);
    }
}
