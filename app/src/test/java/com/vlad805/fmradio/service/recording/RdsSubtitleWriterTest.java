package com.vlad805.fmradio.service.recording;

import org.junit.Test;

import java.io.StringWriter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RdsSubtitleWriterTest {
    @Test
    public void usesRequestedLayoutAndZeroPiWhenUnavailable() {
        assertEquals("99.8 MHz [0000]\nPS: RADIO\nRT: Reklama 789456123",
                new RdsSnapshot(99800, "RADIO", "Reklama 789456123", null).toSubtitleText());
        assertEquals("99.85 MHz [00AF]\nPS: —\nRT: —",
                new RdsSnapshot(99850, null, "", "af").toSubtitleText());
        assertTrue(new RdsSnapshot(99800, "", "", "garbage").toSubtitleText().contains("[0000]"));
    }

    @Test
    public void closesEachCueOnChangeAndTheLastOnStop() throws Exception {
        final StringWriter text = new StringWriter();
        final RdsSubtitleWriter writer = new RdsSubtitleWriter(text);
        writer.update(0, new RdsSnapshot(99800, "RADIO", "News", "1234"));
        writer.update(12500, new RdsSnapshot(99800, "RADIO", "Music", "1234"));
        writer.finish(37000);

        assertEquals("1\n00:00:00,000 --> 00:00:12,500\n99.8 MHz [1234]\nPS: RADIO\nRT: News\n\n"
                + "2\n00:00:12,500 --> 00:00:37,000\n99.8 MHz [1234]\nPS: RADIO\nRT: Music\n\n",
                text.toString());
    }

    @Test
    public void skipsDuplicatesAndZeroDurationIntermediateStates() throws Exception {
        final StringWriter text = new StringWriter();
        final RdsSubtitleWriter writer = new RdsSubtitleWriter(text);
        final RdsSnapshot state = new RdsSnapshot(99800, "RADIO", "", "");
        writer.update(0, RdsSnapshot.EMPTY);
        writer.update(0, state);
        writer.update(100, state);
        writer.finish(1000);
        assertEquals("1\n00:00:00,000 --> 00:00:01,000\n99.8 MHz [0000]\nPS: RADIO\nRT: —\n\n", text.toString());
    }

    @Test
    public void emitsNewCueWhenOnlyFrequencyOrPiChanges() throws Exception {
        final StringWriter text = new StringWriter();
        final RdsSubtitleWriter writer = new RdsSubtitleWriter(text);
        writer.update(0, new RdsSnapshot(99800, "RADIO", "", "1234"));
        writer.update(100, new RdsSnapshot(99900, "RADIO", "", "1234"));
        writer.update(200, new RdsSnapshot(99900, "RADIO", "", "5678"));
        writer.finish(300);
        assertTrue(text.toString().contains("2\n00:00:00,100 --> 00:00:00,200\n99.9 MHz [1234]"));
        assertTrue(text.toString().contains("3\n00:00:00,200 --> 00:00:00,300\n99.9 MHz [5678]"));
    }

    @Test
    public void sanitizesLineBreaksAndPlayerMarkup() {
        assertEquals("99.8 MHz [0000]\nPS: RADIO FM\nRT: Text &lt;b&gt; &amp; more",
                new RdsSnapshot(99800, "RADIO\r\n\nFM", "Text <b> & more", "").toSubtitleText());
    }

    @Test
    public void supportsLongRecordingsAndEmptyAudio() throws Exception {
        final StringWriter text = new StringWriter();
        final RdsSubtitleWriter writer = new RdsSubtitleWriter(text);
        writer.update(0, RdsSnapshot.EMPTY);
        writer.update(3661001, new RdsSnapshot(99800, "RADIO", "", ""));
        writer.finish(3662002);
        assertTrue(text.toString().contains("01:01:01,001 --> 01:01:02,002"));
        assertFalse(text.toString().contains("ECC"));

        final StringWriter empty = new StringWriter();
        final RdsSubtitleWriter emptyWriter = new RdsSubtitleWriter(empty);
        emptyWriter.update(0, RdsSnapshot.EMPTY);
        emptyWriter.finish(0);
        assertEquals("", empty.toString());
    }
}
