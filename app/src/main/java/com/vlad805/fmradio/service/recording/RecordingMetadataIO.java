package com.vlad805.fmradio.service.recording;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Container-level reader and writer for embedded {@code RFM1} metadata across WAV, MP3, and Ogg Opus.
 *
 * <ul>
 *   <li>WAV: custom RIFF subchunk {@code rfmr} appended after {@code data}</li>
 *   <li>MP3: ID3v2.3 {@code PRIV} frame with owner {@code com.vlad805.fmradio.rfm1}</li>
 *   <li>Ogg Opus: Vorbis comment {@code RFM_META=<Base64(RFM1)>} in {@code OpusTags}</li>
 * </ul>
 */
public final class RecordingMetadataIO {
    public static final String WAV_CHUNK_ID = "rfmr";
    public static final String MP3_PRIV_OWNER = "com.vlad805.fmradio.rfm1";
    public static final String OPUS_COMMENT_KEY = "RFM_META";

    private static final byte[] RIFF_MAGIC = "RIFF".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] WAVE_MAGIC = "WAVE".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ID3_MAGIC = "ID3".getBytes(StandardCharsets.US_ASCII);

    private static final int MAX_METADATA_BYTES = 2 * 1024 * 1024;

    private RecordingMetadataIO() {}

    @Nullable
    public static RfmMetadata readFromFile(@NonNull final File file) throws IOException {
        final String name = file.getName();
        final int dot = name.lastIndexOf('.');
        final String ext = dot >= 0 ? name.substring(dot + 1) : "";

        try (InputStream in = new BufferedInputStream(new FileInputStream(file), 32768)) {
            return readFromStream(in, ext);
        }
    }

    @Nullable
    public static RfmMetadata readFromStream(
            @NonNull final InputStream in,
            @NonNull final String extension
    ) throws IOException {
        final String ext = extension.toLowerCase(Locale.US);

        switch (ext) {
            case "wav": {
                return readWavMetadata(in);
            }

            case "mp3": {
                return readMp3Metadata(in);
            }

            case "opus":
            case "ogg": {
                return readOpusMetadata(in);
            }

            default: {
                return null;
            }
        }
    }

    // --- WAV ---

    public static void writeWavChunk(
            @NonNull final File wavFile,
            final long pcmDataLength,
            @NonNull final byte[] rfmBytes
    ) throws IOException {
        final long chunkDataLen = rfmBytes.length;
        final long pad = chunkDataLen & 1L;
        final long riffSize = 36L + pcmDataLength + 8L + chunkDataLen + pad;

        try (RandomAccessFile raf = new RandomAccessFile(wavFile, "rw")) {
            raf.seek(44L + pcmDataLength);
            raf.write(WAV_CHUNK_ID.getBytes(StandardCharsets.US_ASCII));
            raf.write(le32((int) chunkDataLen));
            raf.write(rfmBytes);

            if (pad != 0L) {
                raf.write(0);
            }
            raf.setLength(raf.getFilePointer());

            raf.seek(4L);
            raf.write(le32((int) riffSize));
            raf.seek(40L);
            raf.write(le32((int) pcmDataLength));
        }
    }

    @Nullable
    private static RfmMetadata readWavMetadata(final InputStream in) throws IOException {
        final byte[] riffHeader = new byte[12];
        if (!tryReadFully(in, riffHeader, 0, 12)) {
            return null;
        }

        if (!startsWith(riffHeader, RIFF_MAGIC) || !startsWith(riffHeader, 8, WAVE_MAGIC)) {
            return null;
        }

        final byte[] chunkHeader = new byte[8];

        while (tryReadFully(in, chunkHeader, 0, 8)) {
            final String id = new String(chunkHeader, 0, 4, StandardCharsets.US_ASCII);
            final long size = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;

            if (WAV_CHUNK_ID.equals(id)) {
                if (size > MAX_METADATA_BYTES) {
                    throw new IOException("RFM1 WAV chunk too large: " + size);
                }

                final byte[] data = new byte[(int) size];
                if (!tryReadFully(in, data, 0, (int) size)) {
                    throw new EOFException("Truncated RFM1 WAV chunk");
                }

                return RfmMetadataCodec.decode(data);
            }

            final long toSkip = size + (size & 1L);
            if (!skipFully(in, toSkip)) {
                return null;
            }
        }
        return null;
    }

    // --- MP3 (ID3v2.3 PRIV) ---

    public static void prependMp3Id3WithMetadata(
            @NonNull final File rawMp3File,
            @NonNull final File outputFile,
            @NonNull final String title,
            final long startedAtMillis,
            @Nullable final byte[] rfmBytes
    ) throws IOException {
        final byte[] tag = buildId3v23Tag(title, startedAtMillis, rfmBytes);

        try (
                InputStream in = new BufferedInputStream(new FileInputStream(rawMp3File), 65536);
                OutputStream out = new BufferedOutputStream(new FileOutputStream(outputFile, false), 65536)
        ) {
            out.write(tag);

            final byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
    }

    @NonNull
    public static byte[] buildId3v23Tag(
            @NonNull final String title,
            final long startedAtMillis,
            @Nullable final byte[] rfmBytes
    ) throws IOException {
        final ByteArrayOutputStream frames = new ByteArrayOutputStream(
                128 + (rfmBytes != null ? rfmBytes.length + 40 : 0)
        );
        final String recordedAt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                .format(new Date(startedAtMillis));

        writeId3TextFrame(frames, "TIT2", title);
        writeId3TextFrame(frames, "TDRC", recordedAt);
        writeId3TextFrame(frames, "TENC", "RFM Radio");

        if (rfmBytes != null && rfmBytes.length > 0) {
            final byte[] owner = MP3_PRIV_OWNER.getBytes(StandardCharsets.US_ASCII);
            final byte[] payload = new byte[owner.length + 1 + rfmBytes.length];

            System.arraycopy(owner, 0, payload, 0, owner.length);

            payload[owner.length] = 0;

            System.arraycopy(rfmBytes, 0, payload, owner.length + 1, rfmBytes.length);
            writeId3Frame(frames, "PRIV", payload);
        }

        final byte[] framesData = frames.toByteArray();
        final int size = framesData.length;
        final ByteArrayOutputStream tag = new ByteArrayOutputStream(10 + size);
        tag.write(new byte[]{
                'I', 'D', '3',
                3, 0,
                0,
                (byte) ((size >>> 21) & 0x7F),
                (byte) ((size >>> 14) & 0x7F),
                (byte) ((size >>> 7) & 0x7F),
                (byte) (size & 0x7F),
        });
        tag.write(framesData);

        return tag.toByteArray();
    }

    @Nullable
    private static RfmMetadata readMp3Metadata(final InputStream in) throws IOException {
        final byte[] header = new byte[10];

        if (!tryReadFully(in, header, 0, 10)) {
            return null;
        }

        if (!startsWith(header, ID3_MAGIC)) {
            return null;
        }

        final int majorVersion = header[3] & 0xFF;
        if (majorVersion != 3 && majorVersion != 4) {
            return null;
        }

        final int tagSize = (
                ((header[6] & 0x7F) << 21) |
                ((header[7] & 0x7F) << 14) |
                ((header[8] & 0x7F) << 7) |
                (header[9] & 0x7F)
        );

        int remaining = tagSize;
        final byte[] frameHeader = new byte[10];
        final byte[] ownerPrefix = (MP3_PRIV_OWNER + "\0").getBytes(StandardCharsets.US_ASCII);

        while (remaining >= 10) {
            if (!tryReadFully(in, frameHeader, 0, 10)) {
                return null;
            }

            remaining -= 10;

            if (frameHeader[0] == 0) {
                // ID3v2 padding reached.
                return null;
            }

            final String frameId = new String(frameHeader, 0, 4, StandardCharsets.US_ASCII);
            final int frameSize;

            if (majorVersion == 4) {
                frameSize = (
                        ((frameHeader[4] & 0x7F) << 21) |
                        ((frameHeader[5] & 0x7F) << 14) |
                        ((frameHeader[6] & 0x7F) << 7) |
                        (frameHeader[7] & 0x7F)
                );
            } else {
                frameSize = (
                        ((frameHeader[4] & 0xFF) << 24) |
                        ((frameHeader[5] & 0xFF) << 16) |
                        ((frameHeader[6] & 0xFF) << 8) |
                        (frameHeader[7] & 0xFF)
                );
            }

            if (frameSize < 0 || frameSize > remaining) {
                return null;
            }

            if ("PRIV".equals(frameId) && frameSize > ownerPrefix.length && frameSize <= MAX_METADATA_BYTES) {
                final byte[] payload = new byte[frameSize];

                if (!tryReadFully(in, payload, 0, frameSize)) {
                    throw new EOFException("Truncated ID3 PRIV frame");
                }

                remaining -= frameSize;

                if (startsWith(payload, ownerPrefix)) {
                    final byte[] rfmBytes = new byte[frameSize - ownerPrefix.length];
                    System.arraycopy(payload, ownerPrefix.length, rfmBytes, 0, rfmBytes.length);
                    return RfmMetadataCodec.decode(rfmBytes);
                }
            } else {
                if (!skipFully(in, frameSize)) {
                    return null;
                }

                remaining -= frameSize;
            }
        }
        return null;
    }

    // --- Ogg Opus ---

    public static void embedInOggOpus(
            @NonNull final File opusFile,
            @NonNull final File outputFile,
            @NonNull final byte[] rfmBytes
    ) throws IOException {
        final String encoded = OggOpusTagRewriter.base64Encode(rfmBytes);
        OggOpusTagRewriter.rewriteWithComment(opusFile, outputFile, OPUS_COMMENT_KEY, encoded);
    }

    @Nullable
    private static RfmMetadata readOpusMetadata(final InputStream in) throws IOException {
        final String encoded = OggOpusTagRewriter.readComment(in, OPUS_COMMENT_KEY);

        if (encoded == null || encoded.isEmpty()) {
            return null;
        }

        final byte[] rfmBytes = OggOpusTagRewriter.base64Decode(encoded);
        return RfmMetadataCodec.decode(rfmBytes);
    }

    // --- Helpers ---

    private static void writeId3TextFrame(
            final ByteArrayOutputStream out,
            final String id,
            final String value
    ) throws IOException {
        final byte[] text = value.getBytes(StandardCharsets.ISO_8859_1);
        final byte[] payload = new byte[text.length + 1];
        payload[0] = 0; // ISO-8859-1 encoding byte
        System.arraycopy(text, 0, payload, 1, text.length);
        writeId3Frame(out, id, payload);
    }

    private static void writeId3Frame(
            final ByteArrayOutputStream out,
            final String id,
            final byte[] payload
    ) throws IOException {
        out.write(id.getBytes(StandardCharsets.US_ASCII));
        out.write(new byte[]{
                (byte) ((payload.length >>> 24) & 0xFF),
                (byte) ((payload.length >>> 16) & 0xFF),
                (byte) ((payload.length >>> 8) & 0xFF),
                (byte) (payload.length & 0xFF),
                0,
                0,
        });
        out.write(payload);
    }

    private static byte[] le32(final int value) {
        return new byte[]{
                (byte) (value & 0xFF),
                (byte) ((value >>> 8) & 0xFF),
                (byte) ((value >>> 16) & 0xFF),
                (byte) ((value >>> 24) & 0xFF),
        };
    }

    private static boolean startsWith(final byte[] data, final byte[] prefix) {
        return startsWith(data, 0, prefix);
    }

    private static boolean startsWith(final byte[] data, final int offset, final byte[] prefix) {
        if (offset < 0 || data.length < offset + prefix.length) {
            return false;
        }

        for (int i = 0; i < prefix.length; i++) {
            if (data[offset + i] != prefix[i]) {
                return false;
            }
        }

        return true;
    }

    private static boolean tryReadFully(final InputStream in, final byte[] buf, final int off, final int len)
            throws IOException {
        int remaining = len;
        int pos = off;
        while (remaining > 0) {
            final int read = in.read(buf, pos, remaining);
            if (read < 0) {
                return false;
            }
            pos += read;
            remaining -= read;
        }
        return true;
    }

    private static boolean skipFully(final InputStream in, final long bytes) throws IOException {
        long remaining = bytes;
        final byte[] scratch = new byte[4096];

        while (remaining > 0) {
            final long skipped = in.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
                continue;
            }

            final int read = in.read(scratch, 0, (int) Math.min(scratch.length, remaining));
            if (read < 0) {
                return false;
            }
            remaining -= read;
        }

        return true;
    }
}
