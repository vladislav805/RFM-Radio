package com.vlad805.fmradio.recordings.trim;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.vlad805.fmradio.service.recording.RdsSnapshot;
import com.vlad805.fmradio.service.recording.RecordingMetadataIO;
import com.vlad805.fmradio.service.recording.RfmMetadata;
import com.vlad805.fmradio.service.recording.RfmMetadataCodec;
import com.vlad805.fmradio.service.recording.RfmMetadataEvent;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;

public class Mp3TrimmerTest {
    @Rule
    public final TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void trimsMpeg1Layer3FramesLosslesslyAndSlicesMetadata() throws Exception {
        // MPEG-1 Layer III, 192 kbps, 48 kHz, no padding:
        // Header bytes: 0xFF 0xFB 0xB4 0x00 -> frameSize = (144 * 192000) / 48000 = 576 bytes, duration = 24.0 ms.
        final byte[] header = new byte[]{(byte) 0xFF, (byte) 0xFB, (byte) 0xB4, 0x00};
        final Mp3Trimmer.FrameInfo info = Mp3Trimmer.parseFrameHeader(header);
        assertNotNull(info);
        assertEquals(576, info.frameSize);
        assertEquals(24.0, info.durationMs, 0.01);

        // 100 frames = 2400 ms total.
        final ByteArrayOutputStream rawFrames = new ByteArrayOutputStream();
        for (int i = 0; i < 100; i++) {
            final byte[] frame = new byte[576];
            System.arraycopy(header, 0, frame, 0, 4);
            Arrays.fill(frame, 4, 576, (byte) (i & 0xFF));
            rawFrames.write(frame);
        }

        final File rawInput = tempFolder.newFile("raw.mp3");
        Files.write(rawInput.toPath(), rawFrames.toByteArray());

        final RdsSnapshot s1 = new RdsSnapshot(105300, "A", "", "1111");
        final RdsSnapshot s2 = new RdsSnapshot(105300, "B", "Song", "1111");
        final RfmMetadata meta = new RfmMetadata(
                new byte[]{10, 20, 30, 40},
                Arrays.asList(
                        new RfmMetadataEvent(0L, s1),
                        new RfmMetadataEvent(1200L, s2)
                )
        );

        final File input = tempFolder.newFile("source.mp3");
        RecordingMetadataIO.prependMp3Id3WithMetadata(
                rawInput,
                input,
                "105.3 MHz",
                1764250000000L,
                RfmMetadataCodec.encode(meta)
        );

        // Trim [480ms, 1680ms] -> exactly 50 frames (frames 20..69).
        final File output = tempFolder.newFile("trimmed.mp3");
        Mp3Trimmer.trim(input, output, 480L, 1680L, "105.3 MHz", 1764250000000L, meta, null);

        final byte[] outBytes = Files.readAllBytes(output.toPath());
        final int tagSize = ((outBytes[6] & 0x7F) << 21)
                | ((outBytes[7] & 0x7F) << 14)
                | ((outBytes[8] & 0x7F) << 7)
                | (outBytes[9] & 0x7F);
        final int audioBytes = outBytes.length - (10 + tagSize);
        assertEquals(50 * 576, audioBytes);

        // Verify first copied frame is frame #20 and last copied frame is frame #69.
        assertEquals(20, outBytes[10 + tagSize + 4] & 0xFF);
        assertEquals(69, outBytes[10 + tagSize + 49 * 576 + 4] & 0xFF);

        final RfmMetadata sliced = RecordingMetadataIO.readFromFile(output);
        assertNotNull(sliced);
        assertEquals(2, sliced.getEvents().size());
        assertEquals(0L, sliced.getEvents().get(0).getTimeMs());
        assertEquals(s1, sliced.getEvents().get(0).getSnapshot());
        assertEquals(720L, sliced.getEvents().get(1).getTimeMs());
        assertEquals(s2, sliced.getEvents().get(1).getSnapshot());
    }
}
