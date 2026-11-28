package com.vlad805.fmradio.recordings.trim;

import static org.junit.Assert.assertArrayEquals;
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

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

public class WavTrimmerTest {
    @Rule
    public final TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void trimsPcmRangeLosslesslyAndSlicesEmbeddedMetadata() throws Exception {
        final int sampleRate = 1000;
        final int blockAlign = 4; // 16-bit stereo
        final int byteRate = sampleRate * blockAlign;
        final int totalSeconds = 4;
        final byte[] pcm = new byte[byteRate * totalSeconds];

        // Tag each second with a distinct byte pattern so we verify the exact slice.
        for (int sec = 0; sec < totalSeconds; sec++) {
            Arrays.fill(pcm, sec * byteRate, (sec + 1) * byteRate, (byte) (sec + 1));
        }

        final File input = tempFolder.newFile("source.wav");
        final ByteBuffer hdr = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        hdr.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        hdr.putInt(36 + pcm.length);
        hdr.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        hdr.putInt(16);
        hdr.putShort((short) 1);
        hdr.putShort((short) 2);
        hdr.putInt(sampleRate);
        hdr.putInt(byteRate);
        hdr.putShort((short) blockAlign);
        hdr.putShort((short) 16);
        hdr.put("data".getBytes(StandardCharsets.US_ASCII));
        hdr.putInt(pcm.length);

        try (FileOutputStream out = new FileOutputStream(input)) {
            out.write(hdr.array());
            out.write(pcm);
        }

        final RdsSnapshot s1 = new RdsSnapshot(100000, "S1", "First", "1111");
        final RdsSnapshot s2 = new RdsSnapshot(102000, "S2", "Second", "2222");
        final RfmMetadata meta = new RfmMetadata(
                new byte[]{10, 20, 30, 40},
                Arrays.asList(
                        new RfmMetadataEvent(0L, s1),
                        new RfmMetadataEvent(2000L, s2)
                )
        );
        RecordingMetadataIO.writeWavChunk(input, pcm.length, RfmMetadataCodec.encode(meta));

        final File output = tempFolder.newFile("trimmed.wav");
        WavTrimmer.trim(input, output, 1000L, 3000L, meta, null);

        final byte[] outBytes = Files.readAllBytes(output.toPath());
        final int dataSize = ByteBuffer.wrap(outBytes, 40, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        assertEquals(2 * byteRate, dataSize);

        // First half of trimmed slice must be second 1 (byte value 2), second half must be second 2 (byte value 3).
        assertEquals(2, outBytes[44]);
        assertEquals(2, outBytes[44 + byteRate - 1]);
        assertEquals(3, outBytes[44 + byteRate]);
        assertEquals(3, outBytes[44 + 2 * byteRate - 1]);

        final RfmMetadata sliced = RecordingMetadataIO.readFromFile(output);
        assertNotNull(sliced);
        assertEquals(2, sliced.getEvents().size());
        assertEquals(0L, sliced.getEvents().get(0).getTimeMs());
        assertEquals(s1, sliced.getEvents().get(0).getSnapshot());
        assertEquals(1000L, sliced.getEvents().get(1).getTimeMs());
        assertEquals(s2, sliced.getEvents().get(1).getSnapshot());
        assertArrayEquals(new byte[]{20, 30}, sliced.getWaveformPeaks());
    }
}
