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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Pure-Java Ogg Opus {@code OpusTags} Vorbis-comment reader and rewriter.
 *
 * Replaces only the {@code OpusTags} packet (pages 1..K) and updates {@code page_sequence_number}
 * and Ogg CRC-32 on all subsequent audio pages without touching Opus packets or granule positions.
 */
public final class OggOpusTagRewriter {
    private static final byte[] OGG_CAPTURE = new byte[]{'O', 'g', 'g', 'S'};
    private static final byte[] OPUS_TAGS_MAGIC = "OpusTags".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_PAGE_PAYLOAD = 255 * 255;

    private static final char[] B64_ENC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
    private static final int[] B64_DEC = new int[128];

    static {
        Arrays.fill(B64_DEC, -1);

        for (int i = 0; i < B64_ENC.length; i++) {
            B64_DEC[B64_ENC[i]] = i;
        }
    }

    private OggOpusTagRewriter() {}

    /** Rewrites {@code input} to {@code output}, setting Vorbis comment {@code key=value}. */
    public static void rewriteWithComment(
            @NonNull final File input,
            @NonNull final File output,
            @NonNull final String key,
            @NonNull final String value
    ) throws IOException {
        try (
                InputStream in = new BufferedInputStream(new FileInputStream(input), 65536);
                OutputStream out = new BufferedOutputStream(new FileOutputStream(output, false), 65536)
        ) {
            final OggPage headPage = readPage(in);
            if (headPage == null) {
                throw new IOException("Empty Ogg Opus stream");
            }

            headPage.writeTo(out);

            final List<OggPage> tagPages = new ArrayList<>();
            OggPage firstTagPage = readPage(in);

            if (firstTagPage == null) {
                throw new IOException("Missing OpusTags page");
            }

            tagPages.add(firstTagPage);

            OggPage nextPage = readPage(in);
            while (nextPage != null && (nextPage.headerType & 0x01) != 0) {
                tagPages.add(nextPage);
                nextPage = readPage(in);
            }

            final ByteArrayOutputStream oldTagsPacket = new ByteArrayOutputStream();
            for (final OggPage p : tagPages) {
                oldTagsPacket.write(p.payload);
            }

            final byte[] newTagsPacket = upsertComment(oldTagsPacket.toByteArray(), key, value);
            int pageSeq = 1;
            final int serial = headPage.serialNumber;
            int offset = 0;

            while (offset < newTagsPacket.length || pageSeq == 1) {
                final int chunkLen = Math.min(MAX_PAGE_PAYLOAD, newTagsPacket.length - offset);
                final boolean isContinuation = offset > 0;
                final boolean isLast = (offset + chunkLen) >= newTagsPacket.length;
                final byte[] chunk = Arrays.copyOfRange(newTagsPacket, offset, offset + chunkLen);
                final byte[] lacing = buildLacing(chunkLen, isLast);
                final OggPage page = new OggPage(
                        isContinuation ? 0x01 : 0x00,
                        isLast ? 0L : -1L,
                        serial,
                        pageSeq++,
                        lacing,
                        chunk
                );
                page.writeTo(out);
                offset += chunkLen;

                if (isLast) {
                    break;
                }
            }

            while (nextPage != null) {
                final OggPage updated = nextPage.withSequenceNumber(pageSeq++);
                updated.writeTo(out);
                nextPage = readPage(in);
            }
        }
    }

    /** Reads the Vorbis comment value for {@code key} from an Ogg Opus stream, or {@code null}. */
    @Nullable
    public static String readComment(@NonNull final InputStream in, @NonNull final String key) throws IOException {
        final OggPage headPage = readPage(in);
        if (headPage == null) {
            return null;
        }

        final OggPage firstTagPage = readPage(in);
        if (firstTagPage == null) {
            return null;
        }

        final ByteArrayOutputStream tagsPacket = new ByteArrayOutputStream();
        tagsPacket.write(firstTagPage.payload);

        // If the last lacing value is 255, the packet continues onto subsequent pages.
        boolean continues = (
                firstTagPage.lacing.length > 0 &&
                (firstTagPage.lacing[firstTagPage.lacing.length - 1] & 0xFF) == 255
        );

        while (continues) {
            final OggPage cont = readPage(in);

            if (cont == null || (cont.headerType & 0x01) == 0) {
                break;
            }

            tagsPacket.write(cont.payload);
            continues = (
                    cont.lacing.length > 0 &&
                    (cont.lacing[cont.lacing.length - 1] & 0xFF) == 255
            );
        }

        final byte[] packet = tagsPacket.toByteArray();
        if (!startsWith(packet, OPUS_TAGS_MAGIC)) {
            return null;
        }

        final ByteBuffer buf = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
        buf.position(OPUS_TAGS_MAGIC.length);
        if (buf.remaining() < 4) {
            return null;
        }

        final int vendorLen = buf.getInt();
        if (vendorLen < 0 || buf.remaining() < vendorLen + 4) {
            return null;
        }

        buf.position(buf.position() + vendorLen);
        final int commentCount = buf.getInt();
        if (commentCount < 0) {
            return null;
        }

        final String prefix = key + "=";
        for (int i = 0; i < commentCount; i++) {
            if (buf.remaining() < 4) {
                return null;
            }

            final int len = buf.getInt();

            if (len < 0 || buf.remaining() < len) {
                return null;
            }

            final byte[] commentBytes = new byte[len];
            buf.get(commentBytes);

            final String comment = new String(commentBytes, StandardCharsets.UTF_8);
            if (comment.startsWith(prefix)) {
                return comment.substring(prefix.length());
            }
        }

        return null;
    }

    private static byte[] upsertComment(final byte[] oldPacket, final String key, final String value) throws IOException {
        if (!startsWith(oldPacket, OPUS_TAGS_MAGIC)) {
            throw new IOException("Invalid OpusTags packet magic");
        }

        final ByteBuffer buf = ByteBuffer.wrap(oldPacket).order(ByteOrder.LITTLE_ENDIAN);
        buf.position(OPUS_TAGS_MAGIC.length);
        if (buf.remaining() < 4) {
            throw new IOException("Truncated OpusTags vendor length");
        }

        final int vendorLen = buf.getInt();
        if (vendorLen < 0 || buf.remaining() < vendorLen + 4) {
            throw new IOException("Truncated OpusTags vendor string");
        }

        final byte[] vendorBytes = new byte[vendorLen];
        buf.get(vendorBytes);

        final int commentCount = buf.getInt();

        if (commentCount < 0) {
            throw new IOException("Invalid OpusTags comment count");
        }

        final String prefix = key + "=";
        final List<byte[]> comments = new ArrayList<>(commentCount + 1);

        for (int i = 0; i < commentCount; i++) {
            if (buf.remaining() < 4) {
                throw new IOException("Truncated OpusTags comment length");
            }

            final int len = buf.getInt();
            if (len < 0 || buf.remaining() < len) {
                throw new IOException("Truncated OpusTags comment");
            }

            final byte[] raw = new byte[len];
            buf.get(raw);
            final String text = new String(raw, StandardCharsets.UTF_8);

            if (!text.startsWith(prefix)) {
                comments.add(raw);
            }
        }

        comments.add((prefix + value).getBytes(StandardCharsets.UTF_8));

        final ByteArrayOutputStream out = new ByteArrayOutputStream(oldPacket.length + value.length() + 32);
        out.write(OPUS_TAGS_MAGIC);
        writeLe32(out, vendorBytes.length);
        out.write(vendorBytes);
        writeLe32(out, comments.size());

        for (final byte[] comment : comments) {
            writeLe32(out, comment.length);
            out.write(comment);
        }

        return out.toByteArray();
    }

    private static byte[] buildLacing(final int payloadLen, final boolean terminatesPacket) {
        final int fullSegments = payloadLen / 255;
        final int remainder = payloadLen % 255;
        // Per Ogg spec, a packet whose length is a multiple of 255 ends with a trailing 0-byte segment.
        final boolean needZeroTerminator = terminatesPacket && (remainder == 0);
        final int count = fullSegments + (remainder > 0 ? 1 : 0) + (needZeroTerminator ? 1 : 0);
        final byte[] lacing = new byte[count];

        for (int i = 0; i < fullSegments; i++) {
            lacing[i] = (byte) 255;
        }

        if (remainder > 0) {
            lacing[fullSegments] = (byte) remainder;
        } else if (needZeroTerminator) {
            lacing[fullSegments] = 0;
        }

        return lacing;
    }

    @Nullable
    private static OggPage readPage(final InputStream in) throws IOException {
        final byte[] header = new byte[27];
        final int first = in.read();
        if (first < 0) {
            return null;
        }

        header[0] = (byte) first;
        readFully(in, header, 1, 26);

        if (
                header[0] != OGG_CAPTURE[0] ||
                header[1] != OGG_CAPTURE[1] ||
                header[2] != OGG_CAPTURE[2] ||
                header[3] != OGG_CAPTURE[3]
        ) {
            throw new IOException("Invalid OggS page capture pattern");
        }

        final ByteBuffer buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        final int headerType = buf.get(5) & 0xFF;
        final long granulePosition = buf.getLong(6);
        final int serialNumber = buf.getInt(14);
        final int pageSeq = buf.getInt(18);
        final int segmentCount = buf.get(26) & 0xFF;

        final byte[] lacing = new byte[segmentCount];
        readFully(in, lacing, 0, segmentCount);

        int payloadSize = 0;
        for (final byte seg : lacing) {
            payloadSize += (seg & 0xFF);
        }

        final byte[] payload = new byte[payloadSize];
        readFully(in, payload, 0, payloadSize);

        return new OggPage(headerType, granulePosition, serialNumber, pageSeq, lacing, payload);
    }

    private static void readFully(final InputStream in, final byte[] buf, final int off, final int len) throws IOException {
        int remaining = len;
        int pos = off;

        while (remaining > 0) {
            final int read = in.read(buf, pos, remaining);

            if (read < 0) {
                throw new EOFException("Unexpected EOF in Ogg stream");
            }

            pos += read;
            remaining -= read;
        }
    }

    private static boolean startsWith(final byte[] data, final byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }

        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }

        return true;
    }

    private static void writeLe32(final OutputStream out, final int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }

    /** Pure-Java RFC 4648 Base64 encoder (API 23 & host JVM compatible). */
    @NonNull
    public static String base64Encode(@NonNull final byte[] data) {
        final StringBuilder sb = new StringBuilder(((data.length + 2) / 3) * 4);
        int i = 0;

        while (i < data.length) {
            final int b0 = data[i++] & 0xFF;
            final boolean hasB1 = i < data.length;
            final int b1 = hasB1 ? (data[i++] & 0xFF) : 0;
            final boolean hasB2 = i < data.length;
            final int b2 = hasB2 ? (data[i++] & 0xFF) : 0;

            sb.append(B64_ENC[b0 >>> 2]);
            sb.append(B64_ENC[((b0 & 0x03) << 4) | (b1 >>> 4)]);
            sb.append(hasB1 ? B64_ENC[((b1 & 0x0F) << 2) | (b2 >>> 6)] : '=');
            sb.append(hasB2 ? B64_ENC[b2 & 0x3F] : '=');
        }

        return sb.toString();
    }

    /** Pure-Java RFC 4648 Base64 decoder (API 23 & host JVM compatible). */
    @NonNull
    public static byte[] base64Decode(@NonNull final String input) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream((input.length() * 3) / 4);
        int buf = 0;
        int bits = 0;

        for (int i = 0; i < input.length(); i++) {
            final char c = input.charAt(i);
            if (c == '=' || c == '\r' || c == '\n' || c == ' ') {
                continue;
            }

            if (c >= B64_DEC.length || B64_DEC[c] < 0) {
                throw new IOException("Invalid Base64 character");
            }

            buf = (buf << 6) | B64_DEC[c];
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((buf >>> bits) & 0xFF);
            }
        }

        return out.toByteArray();
    }

    private static final class OggPage {
        final int headerType;
        final long granulePosition;
        final int serialNumber;
        final int pageSequenceNumber;
        final byte[] lacing;
        final byte[] payload;

        OggPage(
                final int headerType,
                final long granulePosition,
                final int serialNumber,
                final int pageSequenceNumber,
                final byte[] lacing,
                final byte[] payload
        ) {
            this.headerType = headerType;
            this.granulePosition = granulePosition;
            this.serialNumber = serialNumber;
            this.pageSequenceNumber = pageSequenceNumber;
            this.lacing = lacing;
            this.payload = payload;
        }

        OggPage withSequenceNumber(final int newSeq) {
            return new OggPage(headerType, granulePosition, serialNumber, newSeq, lacing, payload);
        }

        void writeTo(final OutputStream out) throws IOException {
            final byte[] header = new byte[27];
            final ByteBuffer buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            buf.put(OGG_CAPTURE);
            buf.put((byte) 0); // structure version
            buf.put((byte) headerType);
            buf.putLong(granulePosition);
            buf.putInt(serialNumber);
            buf.putInt(pageSequenceNumber);
            buf.putInt(0); // CRC placeholder
            buf.put((byte) lacing.length);

            int crc = OggCrc32.compute(header, 0, header.length);
            crc = OggCrc32.update(crc, lacing, 0, lacing.length);
            crc = OggCrc32.update(crc, payload, 0, payload.length);
            buf.putInt(22, crc);

            out.write(header);
            out.write(lacing);
            out.write(payload);
        }
    }
}
