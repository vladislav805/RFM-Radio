package com.vlad805.fmradio.service.recording;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.vlad805.fmradio.models.RtPlusInfo;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

public class RecordingMetadataIOTest {
    @Rule
    public final TemporaryFolder tempFolder = new TemporaryFolder();

    private static RfmMetadata sampleMetadata() {
        final RdsSnapshot s1 = new RdsSnapshot(
                105300,
                "EUROPA+",
                "Track — Artist",
                "7701",
                new RtPlusInfo(Collections.singletonMap("ITEM.TITLE", "Track")),
                "RU",
                10,
                true,
                true,
                -72,
                20
        );
        return new RfmMetadata(
                new byte[]{0, 40, 120, (byte) 255, 80},
                Collections.singletonList(new RfmMetadataEvent(0L, s1))
        );
    }

    @Test
    public void wavRoundTripEvenAndOddPayloadLengths() throws Exception {
        for (final int extraLen : new int[]{0, 1}) {
            final File wav = tempFolder.newFile("test-" + extraLen + ".wav");
            final byte[] pcm = new byte[1920];
            for (int i = 0; i < pcm.length; i++) {
                pcm[i] = (byte) (i & 0xFF);
            }
            writeMinimalWav(wav, pcm);

            final RfmMetadata meta = sampleMetadata();
            final byte[] encoded = RfmMetadataCodec.encode(meta);
            // Force odd or even length by adding a trailing comment event if needed
            final byte[] rfmBytes;
            if ((encoded.length & 1) == extraLen) {
                rfmBytes = encoded;
            } else {
                final RdsSnapshot s2 = new RdsSnapshot(105300, "EUROPA+X", "Other", "7701");
                final RfmMetadata alt = new RfmMetadata(
                        meta.getWaveformPeaks(),
                        Arrays.asList(meta.getEvents().get(0), new RfmMetadataEvent(1500L, s2))
                );
                rfmBytes = RfmMetadataCodec.encode(alt);
            }

            RecordingMetadataIO.writeWavChunk(wav, pcm.length, rfmBytes);

            final byte[] fileBytes = Files.readAllBytes(wav.toPath());
            final int riffSize = ByteBuffer.wrap(fileBytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            final int dataSize = ByteBuffer.wrap(fileBytes, 40, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            assertEquals(fileBytes.length - 8, riffSize);
            assertEquals(pcm.length, dataSize);
            assertArrayEquals(pcm, Arrays.copyOfRange(fileBytes, 44, 44 + pcm.length));

            final RfmMetadata readBack = RecordingMetadataIO.readFromFile(wav);
            assertNotNull(readBack);
            assertEquals(RfmMetadataCodec.decode(rfmBytes), readBack);
        }
    }

    @Test
    public void mp3Id3v23PrivRoundTripPreservesAudioFrames() throws Exception {
        final File rawMp3 = tempFolder.newFile("raw.mp3");
        final File taggedMp3 = tempFolder.newFile("tagged.mp3");
        final byte[] fakeFrames = new byte[512];
        fakeFrames[0] = (byte) 0xFF;
        fakeFrames[1] = (byte) 0xFB;
        for (int i = 2; i < fakeFrames.length; i++) {
            fakeFrames[i] = (byte) ((i * 7) & 0xFF);
        }
        Files.write(rawMp3.toPath(), fakeFrames);

        final RfmMetadata meta = sampleMetadata();
        final byte[] rfmBytes = RfmMetadataCodec.encode(meta);
        RecordingMetadataIO.prependMp3Id3WithMetadata(rawMp3, taggedMp3, "105.3 MHz", 1764250000000L, rfmBytes);

        final RfmMetadata readBack = RecordingMetadataIO.readFromFile(taggedMp3);
        assertEquals(meta, readBack);

        // Verify raw MP3 frames follow immediately after ID3v2 header.
        final byte[] all = Files.readAllBytes(taggedMp3.toPath());
        final int tagSize = ((all[6] & 0x7F) << 21) | ((all[7] & 0x7F) << 14)
                | ((all[8] & 0x7F) << 7) | (all[9] & 0x7F);
        assertArrayEquals(fakeFrames, Arrays.copyOfRange(all, 10 + tagSize, all.length));
    }

    @Test
    public void oggOpusRoundTripSingleAndMultiPageTagsWithValidCrcAndSequence() throws Exception {
        final File rawOpus = tempFolder.newFile("raw.opus");
        final File taggedOpus = tempFolder.newFile("tagged.opus");
        final byte[] audioPayload = new byte[]{0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};
        writeMinimalOggOpus(rawOpus, audioPayload);

        final RfmMetadata meta = sampleMetadata();
        final byte[] rfmBytes = RfmMetadataCodec.encode(meta);
        RecordingMetadataIO.embedInOggOpus(rawOpus, taggedOpus, rfmBytes);

        assertEquals(meta, RecordingMetadataIO.readFromFile(taggedOpus));
        verifyAllOggPagesValid(Files.readAllBytes(taggedOpus.toPath()), audioPayload);

        // Also test multi-page OpusTags (> 65025 bytes comment)
        final File largeOpus = tempFolder.newFile("large.opus");
        final char[] bigChars = new char[70_000];
        Arrays.fill(bigChars, 'A');
        final String bigValue = new String(bigChars);
        OggOpusTagRewriter.rewriteWithComment(rawOpus, largeOpus, "BIG_KEY", bigValue);

        try (java.io.InputStream in = Files.newInputStream(largeOpus.toPath())) {
            assertEquals(bigValue, OggOpusTagRewriter.readComment(in, "BIG_KEY"));
        }
        verifyAllOggPagesValid(Files.readAllBytes(largeOpus.toPath()), audioPayload);
    }

    @Test
    public void returnsNullWhenNoMetadataPresent() throws Exception {
        final File wav = tempFolder.newFile("plain.wav");
        writeMinimalWav(wav, new byte[64]);
        assertNull(RecordingMetadataIO.readFromFile(wav));

        final File opus = tempFolder.newFile("plain.opus");
        writeMinimalOggOpus(opus, new byte[]{1, 2, 3, 4});
        assertNull(RecordingMetadataIO.readFromFile(opus));
    }

    private static void writeMinimalWav(final File file, final byte[] pcm) throws IOException {
        final ByteBuffer hdr = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        hdr.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        hdr.putInt(36 + pcm.length);
        hdr.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        hdr.putInt(16);
        hdr.putShort((short) 1);
        hdr.putShort((short) 2);
        hdr.putInt(48000);
        hdr.putInt(48000 * 4);
        hdr.putShort((short) 4);
        hdr.putShort((short) 16);
        hdr.put("data".getBytes(StandardCharsets.US_ASCII));
        hdr.putInt(pcm.length);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(hdr.array());
            out.write(pcm);
        }
    }

    private static void writeMinimalOggOpus(final File file, final byte[] audioPayload) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        // Page 0: OpusHead (19 bytes)
        final byte[] opusHead = new byte[19];
        System.arraycopy("OpusHead".getBytes(StandardCharsets.US_ASCII), 0, opusHead, 0, 8);
        opusHead[8] = 1; // version
        opusHead[9] = 2; // channels
        writeOggPage(out, 0x02, 0L, 0x12345678, 0, opusHead);

        // Page 1: OpusTags
        final ByteArrayOutputStream tags = new ByteArrayOutputStream();
        tags.write("OpusTags".getBytes(StandardCharsets.US_ASCII));
        final byte[] vendor = "libopusenc 0.3".getBytes(StandardCharsets.UTF_8);
        tags.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(vendor.length).array());
        tags.write(vendor);
        tags.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0).array());
        writeOggPage(out, 0x00, 0L, 0x12345678, 1, tags.toByteArray());

        // Page 2: Audio page
        writeOggPage(out, 0x04, 960L, 0x12345678, 2, audioPayload);
        Files.write(file.toPath(), out.toByteArray());
    }

    private static void writeOggPage(
            final ByteArrayOutputStream out,
            final int headerType,
            final long granule,
            final int serial,
            final int seq,
            final byte[] payload
    ) throws IOException {
        final byte[] header = new byte[27];
        final ByteBuffer buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        buf.put("OggS".getBytes(StandardCharsets.US_ASCII));
        buf.put((byte) 0);
        buf.put((byte) headerType);
        buf.putLong(granule);
        buf.putInt(serial);
        buf.putInt(seq);
        buf.putInt(0);
        buf.put((byte) 1);
        final byte[] lacing = new byte[]{(byte) payload.length};
        int crc = OggCrc32.compute(header, 0, header.length);
        crc = OggCrc32.update(crc, lacing, 0, lacing.length);
        crc = OggCrc32.update(crc, payload, 0, payload.length);
        buf.putInt(22, crc);
        out.write(header);
        out.write(lacing);
        out.write(payload);
    }

    private static void verifyAllOggPagesValid(final byte[] bytes, final byte[] expectedLastAudio) {
        int pos = 0;
        int expectedSeq = 0;
        byte[] lastPayload = null;
        while (pos < bytes.length) {
            final ByteBuffer hdr = ByteBuffer.wrap(bytes, pos, 27).slice().order(ByteOrder.LITTLE_ENDIAN);
            assertEquals('O', bytes[pos]);
            assertEquals('g', bytes[pos + 1]);
            assertEquals('g', bytes[pos + 2]);
            assertEquals('S', bytes[pos + 3]);
            assertEquals(expectedSeq++, hdr.getInt(18));
            final int storedCrc = hdr.getInt(22);
            final int segCount = bytes[pos + 26] & 0xFF;
            int payloadLen = 0;
            for (int i = 0; i < segCount; i++) {
                payloadLen += bytes[pos + 27 + i] & 0xFF;
            }
            final int pageLen = 27 + segCount + payloadLen;
            final byte[] copy = Arrays.copyOfRange(bytes, pos, pos + pageLen);
            copy[22] = 0;
            copy[23] = 0;
            copy[24] = 0;
            copy[25] = 0;
            assertEquals(storedCrc, OggCrc32.compute(copy, 0, copy.length));
            lastPayload = Arrays.copyOfRange(bytes, pos + 27 + segCount, pos + pageLen);
            pos += pageLen;
        }
        assertArrayEquals(expectedLastAudio, lastPayload);
    }
}
