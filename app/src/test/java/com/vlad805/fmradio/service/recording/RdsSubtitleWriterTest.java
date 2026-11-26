package com.vlad805.fmradio.service.recording;

import com.vlad805.fmradio.models.RtPlusFields;
import com.vlad805.fmradio.models.RtPlusInfo;

import org.junit.Test;

import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RdsSubtitleWriterTest {
    private final Map<String, String> rtPlusLabels = new LinkedHashMap<>();

    @Test
    public void usesRequestedLayoutAndZeroPiWhenUnavailable() {
        assertEquals("99.8 MHz [0000]\nPS: RADIO\nRT: Reklama 789456123",
                new RdsSnapshot(99800, "RADIO", "Reklama 789456123", null).toSubtitleText(rtPlusLabels));
        assertEquals("99.85 MHz [00AF]\nPS: —\nRT: —",
                new RdsSnapshot(99850, null, "", "af").toSubtitleText(rtPlusLabels));
        assertTrue(new RdsSnapshot(99800, "", "", "garbage").toSubtitleText(rtPlusLabels).contains("[0000]"));
    }

    @Test
    public void includesRtPlusTitleAndArtistInSubtitleText() {
        assertEquals("99.8 MHz [1234]\nPS: RADIO\nRT: Bravo; Valeriy Syutkin - Stil'nyy oranjevyy galstuk\nRT+: Valeriy Syutkin — Stil'nyy oranjevyy galstuk",
                new RdsSnapshot(99800, "RADIO", "Bravo; Valeriy Syutkin - Stil'nyy oranjevyy galstuk", "1234", new RtPlusInfo(Map.of("ITEM.TITLE", "Stil'nyy oranjevyy galstuk", "ITEM.ARTIST", "Valeriy Syutkin"))).toSubtitleText(rtPlusLabels));
    }

    @Test
    public void includesEveryPopulatedRtPlusFieldInContentTypeOrder() {
        final Map<String, String> fields = new TreeMap<>();
        final StringBuilder expected = new StringBuilder("99.8 MHz [1234]\nPS: RADIO\nRT: —\nRT+: ITEM.ARTIST — ITEM.TITLE (ITEM.ALBUM)");
        for (String key : RtPlusFields.names()) {
            fields.put(key, key);
            rtPlusLabels.put(key, "Field " + key);
            if (!key.equals("ITEM.ARTIST") && !key.equals("ITEM.TITLE") && !key.equals("ITEM.ALBUM")) {
                expected.append("\nRT+ Field ").append(key).append(": ").append(key);
            }
        }
        final RdsSnapshot snapshot = new RdsSnapshot(99800, "RADIO", "", "1234", new RtPlusInfo(fields));
        assertEquals(expected.toString(), snapshot.toSubtitleText(rtPlusLabels));
    }

    @Test
    public void startsNewCuesWhenOnlyAnExtendedRtPlusFieldChangesOrClears() throws Exception {
        rtPlusLabels.put("INFO.WEATHER", "Погода");
        final StringWriter text = new StringWriter();
        final RdsSubtitleWriter writer = new RdsSubtitleWriter(text, rtPlusLabels);
        final RtPlusInfo rain = new RtPlusInfo(Map.of("INFO.WEATHER", "Дождь\r\n<5° & ветер"));
        writer.update(0, new RdsSnapshot(99800, "FM", "", "1234", rain));
        writer.update(250, new RdsSnapshot(99800, "FM", "", "1234", new RtPlusInfo(rain.getFields())));
        writer.update(500, new RdsSnapshot(99800, "FM", "", "1234", new RtPlusInfo(Map.of("INFO.WEATHER", "Снег"))));
        writer.update(1000, new RdsSnapshot(99800, "FM", "", "1234"));
        writer.finish(1500);

        final String base = "99.8 MHz [1234]\nPS: FM\nRT: —";
        assertEquals("1\n00:00:00,000 --> 00:00:00,500\n" + base + "\nRT+ Погода: Дождь &lt;5° &amp; ветер\n\n"
                + "2\n00:00:00,500 --> 00:00:01,000\n" + base + "\nRT+ Погода: Снег\n\n"
                + "3\n00:00:01,000 --> 00:00:01,500\n" + base + "\n\n", text.toString());
    }

    @Test
    public void closesEachCueOnChangeAndTheLastOnStop() throws Exception {
        final StringWriter text = new StringWriter();
        final RdsSubtitleWriter writer = new RdsSubtitleWriter(text, rtPlusLabels);
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
        final RdsSubtitleWriter writer = new RdsSubtitleWriter(text, rtPlusLabels);
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
        final RdsSubtitleWriter writer = new RdsSubtitleWriter(text, rtPlusLabels);
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
                new RdsSnapshot(99800, "RADIO\r\n\nFM", "Text <b> & more", "").toSubtitleText(rtPlusLabels));
    }

    @Test
    public void supportsLongRecordingsAndEmptyAudio() throws Exception {
        final StringWriter text = new StringWriter();
        final RdsSubtitleWriter writer = new RdsSubtitleWriter(text, rtPlusLabels);
        writer.update(0, RdsSnapshot.EMPTY);
        writer.update(3661001, new RdsSnapshot(99800, "RADIO", "", ""));
        writer.finish(3662002);
        assertTrue(text.toString().contains("01:01:01,001 --> 01:01:02,002"));
        assertFalse(text.toString().contains("ECC"));

        final StringWriter empty = new StringWriter();
        final RdsSubtitleWriter emptyWriter = new RdsSubtitleWriter(empty, rtPlusLabels);
        emptyWriter.update(0, RdsSnapshot.EMPTY);
        emptyWriter.finish(0);
        assertEquals("", empty.toString());
    }
}
