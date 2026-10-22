package com.vlad805.fmradio.service.recording;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

public class RecordingStorageTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void subtitleUsesTheAudioStemForBothFormats() {
        assertEquals("recording.srt", RecordingStorage.subtitleName("recording.mp3"));
        assertEquals("recording.srt", RecordingStorage.subtitleName("recording.wav"));
        assertEquals("radio.99.8.srt", RecordingStorage.subtitleName("radio.99.8.mp3"));
        assertEquals("recording (1).srt", RecordingStorage.subtitleName("recording (1).mp3"));
    }

    @Test
    public void acceptsFileInsideRecordingDirectory() throws Exception {
        final File root = temporaryFolder.newFolder("RFM-Recordings");
        final File recording = new File(new File(root, "station"), "recording.mp3");

        assertTrue(RecordingStorage.isFileInsideDirectory(root, recording));
    }

    @Test
    public void rejectsFileOutsideRecordingDirectory() throws Exception {
        final File root = temporaryFolder.newFolder("RFM-Recordings");
        final File sibling = temporaryFolder.newFolder("RFM-Recordings-old");
        final File recording = new File(sibling, "recording.mp3");

        assertFalse(RecordingStorage.isFileInsideDirectory(root, recording));
    }

    @Test
    public void rejectsPathTraversal() throws Exception {
        final File root = temporaryFolder.newFolder("RFM-Recordings");
        final File recording = new File(root, "../recording.mp3");

        assertFalse(RecordingStorage.isFileInsideDirectory(root, recording));
    }
}
