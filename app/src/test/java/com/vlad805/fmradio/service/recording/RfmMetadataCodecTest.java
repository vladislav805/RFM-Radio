package com.vlad805.fmradio.service.recording;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.vlad805.fmradio.controller.RadioState;
import com.vlad805.fmradio.models.RtPlusInfo;

import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class RfmMetadataCodecTest {

    @Test
    public void roundTripsFullMetadataWithDeltasUtf8AndTelemetry() throws Exception {
        final Map<String, String> rtPlusFields = new LinkedHashMap<>();
        rtPlusFields.put("ITEM.ARTIST", "Daft Punk");
        rtPlusFields.put("ITEM.TITLE", "Around the World");
        rtPlusFields.put("INFO.WEATHER", "Sunny +22°C");

        final RdsSnapshot first = new RdsSnapshot(
                102500,
                "RADIO FM",
                "Now playing: Daft Punk — Around the World (Café)",
                "D210",
                new RtPlusInfo(rtPlusFields),
                "DE",
                10,
                true,
                true,
                -74,
                18
        );
        // Only RMSSI/SINR changed (tests delta encoding preserves all other fields)
        final RdsSnapshot signalTick = new RdsSnapshot(
                102500,
                "RADIO FM",
                "Now playing: Daft Punk — Around the World (Café)",
                "D210",
                new RtPlusInfo(rtPlusFields),
                "DE",
                10,
                true,
                true,
                -71,
                21
        );
        // Station changed and RT+ / SINR cleared
        final RdsSnapshot second = new RdsSnapshot(
                98500,
                "EUROPA+",
                "Реклама на радио",
                "7701",
                RtPlusInfo.EMPTY,
                "RU",
                11,
                false,
                false,
                -92,
                RadioState.SINR_UNKNOWN
        );

        final byte[] peaks = new byte[240];
        for (int i = 0; i < peaks.length; i++) {
            peaks[i] = (byte) (i & 0xFF);
        }

        final RfmMetadata original = new RfmMetadata(
                peaks,
                Arrays.asList(
                        new RfmMetadataEvent(0L, first),
                        new RfmMetadataEvent(1_000L, signalTick),
                        new RfmMetadataEvent(30_000L, second)
                )
        );

        final byte[] encoded = RfmMetadataCodec.encode(original);
        assertTrue(RfmMetadataCodec.hasMagic(encoded));

        final RfmMetadata decoded = RfmMetadataCodec.decode(encoded);
        assertEquals(original, decoded);
        assertArrayEquals(peaks, decoded.getWaveformPeaks());
        assertEquals(3, decoded.getEvents().size());
        assertEquals(-74, decoded.getEvents().get(0).getSnapshot().getRmssi());
        assertEquals(21, decoded.getEvents().get(1).getSnapshot().getSinr());
        assertEquals(RadioState.SINR_UNKNOWN, decoded.getEvents().get(2).getSnapshot().getSinr());
        assertTrue(decoded.getEvents().get(0).getSnapshot().hasTrafficProgramme());
        assertTrue(decoded.getEvents().get(0).getSnapshot().isStereo());
        assertFalse(decoded.getEvents().get(2).getSnapshot().isStereo());
    }

    @Test
    public void deltaEncodingKeepsSignalOnlyTicksCompactBeforeCompression() {
        final RdsSnapshot base = new RdsSnapshot(
                102500,
                "RADIO FM",
                "A fairly long RadioText string that should not be repeated on signal ticks",
                "D210",
                RtPlusInfo.EMPTY,
                "DE",
                10,
                true,
                true,
                -80,
                15
        );
        final RfmMetadata oneEvent = new RfmMetadata(
                new byte[0],
                Collections.singletonList(new RfmMetadataEvent(0L, base))
        );
        final RfmMetadata twoEvents = new RfmMetadata(
                new byte[0],
                Arrays.asList(
                        new RfmMetadataEvent(0L, base),
                        new RfmMetadataEvent(1_000L, new RdsSnapshot(
                                102500,
                                "RADIO FM",
                                "A fairly long RadioText string that should not be repeated on signal ticks",
                                "D210",
                                RtPlusInfo.EMPTY,
                                "DE",
                                10,
                                true,
                                true,
                                -79,
                                16
                        ))
                )
        );

        final int uncompressed1 = ByteBuffer.wrap(RfmMetadataCodec.encode(oneEvent), 6, 4)
                .order(ByteOrder.LITTLE_ENDIAN).getInt();
        final int uncompressed2 = ByteBuffer.wrap(RfmMetadataCodec.encode(twoEvents), 6, 4)
                .order(ByteOrder.LITTLE_ENDIAN).getInt();

        // Delta for 2nd event: 4B timeMs + 2B mask + 2B rmssi + 2B sinr = 10 bytes.
        assertEquals(10, uncompressed2 - uncompressed1);
    }

    @Test
    public void roundTripsEmptyAndUnknownSentinels() throws Exception {
        final RfmMetadata empty = new RfmMetadata(
                new byte[0],
                Collections.singletonList(new RfmMetadataEvent(0L, RdsSnapshot.EMPTY))
        );

        final byte[] encoded = RfmMetadataCodec.encode(empty);
        final RfmMetadata decoded = RfmMetadataCodec.decode(encoded);

        assertEquals(empty, decoded);
        final RdsSnapshot s = decoded.getEvents().get(0).getSnapshot();
        assertEquals("", s.getPi());
        assertEquals("", s.getCountry());
        assertEquals(RadioState.RMSSI_UNKNOWN, s.getRmssi());
        assertEquals(RadioState.SINR_UNKNOWN, s.getSinr());
    }

    @Test
    public void getEventAtUsesBinarySearchBoundaries() {
        final RdsSnapshot s1 = new RdsSnapshot(100000, "S1", "", "1111");
        final RdsSnapshot s2 = new RdsSnapshot(101000, "S2", "", "2222");
        final RdsSnapshot s3 = new RdsSnapshot(102000, "S3", "", "3333");

        final RfmMetadata meta = new RfmMetadata(
                new byte[0],
                Arrays.asList(
                        new RfmMetadataEvent(5_000L, s2),
                        new RfmMetadataEvent(1_000L, s1),
                        new RfmMetadataEvent(8_000L, s3)
                )
        );

        assertNull(meta.getEventAt(500L));
        assertEquals(s1, meta.getEventAt(1_000L).getSnapshot());
        assertEquals(s1, meta.getEventAt(4_999L).getSnapshot());
        assertEquals(s2, meta.getEventAt(5_000L).getSnapshot());
        assertEquals(s2, meta.getEventAt(7_999L).getSnapshot());
        assertEquals(s3, meta.getEventAt(8_000L).getSnapshot());
        assertEquals(s3, meta.getEventAt(25_000L).getSnapshot());
    }

    @Test
    public void sliceCarriesBaselineEventShiftsTimestampsAndSlicesWaveform() {
        final RdsSnapshot s1 = new RdsSnapshot(100000, "S1", "", "1111");
        final RdsSnapshot s2 = new RdsSnapshot(101000, "S2", "", "2222");
        final RdsSnapshot s3 = new RdsSnapshot(102000, "S3", "", "3333");

        final byte[] peaks = new byte[]{10, 20, 30, 40, 50, 60, 70, 80, 90, 100};
        final RfmMetadata meta = new RfmMetadata(
                peaks,
                Arrays.asList(
                        new RfmMetadataEvent(0L, s1),
                        new RfmMetadataEvent(4_000L, s2),
                        new RfmMetadataEvent(8_000L, s3)
                )
        );

        final RfmMetadata sliced = meta.slice(10_000L, 2_000L, 6_000L);
        assertEquals(2, sliced.getEvents().size());
        assertEquals(0L, sliced.getEvents().get(0).getTimeMs());
        assertEquals(s1, sliced.getEvents().get(0).getSnapshot());
        assertEquals(2_000L, sliced.getEvents().get(1).getTimeMs());
        assertEquals(s2, sliced.getEvents().get(1).getSnapshot());
        assertArrayEquals(new byte[]{30, 40, 50, 60}, sliced.getWaveformPeaks());
    }

    @Test
    public void rejectsCorruptAndInvalidHeaders() {
        assertFalse(RfmMetadataCodec.hasMagic(null));
        assertFalse(RfmMetadataCodec.hasMagic(new byte[]{'R', 'F', 'M'}));
        assertFalse(RfmMetadataCodec.hasMagic(new byte[]{'R', 'F', 'M', '2'}));

        final byte[][] badInputs = new byte[][]{
                new byte[0],
                new byte[]{'R', 'F', 'M', '1', 1, 0},
                new byte[]{'B', 'A', 'D', '!', 1, 0, 0, 0, 0, 0},
                new byte[]{'R', 'F', 'M', '1', 2, 0, 0, 0, 0, 0}, // unsupported version 2
                new byte[]{'R', 'F', 'M', '1', 1, 0, 20, 0, 0, 0, 1, 2, 3, 4}, // corrupt zlib
        };

        for (final byte[] bad : badInputs) {
            try {
                RfmMetadataCodec.decode(bad);
                fail("Expected IOException for invalid input");
            } catch (final IOException expected) {
                // Expected.
            }
        }
    }
}
