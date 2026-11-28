package com.vlad805.fmradio.recordings.trim;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;

public class RecordingTrimmerTest {

    @Test
    public void buildsTrimCopyFilenamesPreservingDotsInFrequency() {
        assertEquals("FM-153131-1053_trim.opus", RecordingTrimmer.buildTrimCopyName("FM-153131-1053.opus"));
        assertEquals("102.5_20261127_trim.mp3", RecordingTrimmer.buildTrimCopyName("102.5_20261127.mp3"));
        assertEquals("recording_trim.wav", RecordingTrimmer.buildTrimCopyName("recording.wav"));
        assertEquals("noext_trim", RecordingTrimmer.buildTrimCopyName("noext"));
    }

    @Test
    public void validatesTrimRangeBoundsAndMinimumDuration() throws Exception {
        RecordingTrimmer.validateRange(0L, 1000L);
        RecordingTrimmer.validateRange(1500L, 4500L);

        final long[][] invalidRanges = new long[][]{
                {-100L, 2000L},
                {2000L, 2000L},
                {3000L, 1000L},
                {1000L, 1999L},
        };

        for (final long[] range : invalidRanges) {
            try {
                RecordingTrimmer.validateRange(range[0], range[1]);
                fail("Expected IOException for range [" + range[0] + ", " + range[1] + "]");
            } catch (final IOException expected) {
                // Expected.
            }
        }
    }
}
