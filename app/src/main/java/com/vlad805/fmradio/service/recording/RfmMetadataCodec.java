package com.vlad805.fmradio.service.recording;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.vlad805.fmradio.controller.RadioState;
import com.vlad805.fmradio.models.RtPlusInfo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Encodes and decodes the compact binary {@code RFM1} metadata container.
 *
 * Outer header (10 bytes, little-endian):
 *   0..3  ASCII "RFM1"
 *   4..5  uint16 version (1)
 *   6..9  uint32 uncompressed inner payload length (safety cap: 4 MiB)
 *   10..  zlib-compressed inner payload
 *
 * Inner payload (delta-encoded events against previous snapshot, starting from {@link RdsSnapshot#EMPTY}):
 *   uint16 waveformCount (0..512) + uint8[waveformCount]
 *   uint32 eventCount
 *   repeated eventCount:
 *     uint32 timeMs
 *     uint16 changedMask (only changed fields follow in bit order)
 */
public final class RfmMetadataCodec {
    private static final byte[] MAGIC = new byte[]{'R', 'F', 'M', '1'};
    private static final int HEADER_SIZE = 10;
    private static final int VERSION = 1;
    private static final int MAX_UNCOMPRESSED_SIZE = 4 * 1024 * 1024;
    private static final int MAX_WAVEFORM_BINS = 512;

    private static final int FIELD_FREQUENCY = 1;
    private static final int FIELD_PI = 1 << 1;
    private static final int FIELD_PTY = 1 << 2;
    private static final int FIELD_TP = 1 << 3;
    private static final int FIELD_STEREO = 1 << 4;
    private static final int FIELD_RMSSI = 1 << 5;
    private static final int FIELD_SINR = 1 << 6;
    private static final int FIELD_COUNTRY = 1 << 7;
    private static final int FIELD_PS = 1 << 8;
    private static final int FIELD_RT = 1 << 9;
    private static final int FIELD_RT_PLUS = 1 << 10;

    private static final int PI_UNKNOWN = 0xFFFF;
    private static final short METRIC_UNKNOWN = Short.MAX_VALUE;

    private RfmMetadataCodec() {}

    public static boolean hasMagic(@Nullable final byte[] data) {
        return data != null
                && data.length >= MAGIC.length
                && data[0] == MAGIC[0]
                && data[1] == MAGIC[1]
                && data[2] == MAGIC[2]
                && data[3] == MAGIC[3];
    }

    @NonNull
    public static byte[] encode(@NonNull final RfmMetadata metadata) {
        final byte[] raw = encodeInner(metadata);
        final byte[] compressed = compress(raw);

        final ByteBuffer out = ByteBuffer.allocate(HEADER_SIZE + compressed.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        out.put(MAGIC);
        out.putShort((short) VERSION);
        out.putInt(raw.length);
        out.put(compressed);
        return out.array();
    }

    @NonNull
    public static RfmMetadata decode(@NonNull final byte[] data) throws IOException {
        if (data.length < HEADER_SIZE || !hasMagic(data)) {
            throw new IOException("Invalid RFM1 metadata header");
        }
        final ByteBuffer header = ByteBuffer.wrap(data, 0, HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        header.position(4);
        final int version = header.getShort() & 0xFFFF;
        if (version != VERSION) {
            throw new IOException("Unsupported RFM1 metadata version: " + version);
        }
        final int uncompressedSize = header.getInt();
        if (uncompressedSize < 0 || uncompressedSize > MAX_UNCOMPRESSED_SIZE) {
            throw new IOException("Invalid RFM1 uncompressed size: " + uncompressedSize);
        }

        final byte[] inner = decompress(data, HEADER_SIZE, data.length - HEADER_SIZE, uncompressedSize);
        return decodeInner(inner);
    }

    private static byte[] encodeInner(final RfmMetadata metadata) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(256);

        final byte[] peaks = metadata.getWaveformPeaks();
        final int peakCount = Math.min(peaks.length, MAX_WAVEFORM_BINS);
        writeU16(out, peakCount);
        out.write(peaks, 0, peakCount);

        final List<RfmMetadataEvent> events = metadata.getEvents();
        writeU32(out, events.size());

        RdsSnapshot prev = RdsSnapshot.EMPTY;
        for (final RfmMetadataEvent event : events) {
            final RdsSnapshot cur = event.getSnapshot();
            int mask = 0;
            if (cur.getFrequencyKhz() != prev.getFrequencyKhz()) mask |= FIELD_FREQUENCY;
            if (parsePi(cur.getPi()) != parsePi(prev.getPi())) mask |= FIELD_PI;
            if (cur.getPty() != prev.getPty()) mask |= FIELD_PTY;
            if (cur.hasTrafficProgramme() != prev.hasTrafficProgramme()) mask |= FIELD_TP;
            if (cur.isStereo() != prev.isStereo()) mask |= FIELD_STEREO;
            if (cur.getRmssi() != prev.getRmssi()) mask |= FIELD_RMSSI;
            if (cur.getSinr() != prev.getSinr()) mask |= FIELD_SINR;
            if (!cur.getCountry().equals(prev.getCountry())) mask |= FIELD_COUNTRY;
            if (!cur.getPs().equals(prev.getPs())) mask |= FIELD_PS;
            if (!cur.getRt().equals(prev.getRt())) mask |= FIELD_RT;
            if (!cur.getRtPlus().equals(prev.getRtPlus())) mask |= FIELD_RT_PLUS;

            writeU32(out, event.getTimeMs());
            writeU16(out, mask);

            if ((mask & FIELD_FREQUENCY) != 0) writeI32(out, cur.getFrequencyKhz());
            if ((mask & FIELD_PI) != 0) writeU16(out, parsePi(cur.getPi()));
            if ((mask & FIELD_PTY) != 0) out.write(Math.max(0, Math.min(255, cur.getPty())));
            if ((mask & FIELD_TP) != 0) out.write(cur.hasTrafficProgramme() ? 1 : 0);
            if ((mask & FIELD_STEREO) != 0) out.write(cur.isStereo() ? 1 : 0);
            if ((mask & FIELD_RMSSI) != 0) writeMetric(out, cur.getRmssi(), RadioState.RMSSI_UNKNOWN);
            if ((mask & FIELD_SINR) != 0) writeMetric(out, cur.getSinr(), RadioState.SINR_UNKNOWN);
            if ((mask & FIELD_COUNTRY) != 0) writeStringU8(out, cur.getCountry());
            if ((mask & FIELD_PS) != 0) writeStringU8(out, cur.getPs());
            if ((mask & FIELD_RT) != 0) writeStringU16(out, cur.getRt());
            if ((mask & FIELD_RT_PLUS) != 0) {
                final Map<String, String> rtPlus = cur.getRtPlus().getFields();
                final int count = Math.min(255, rtPlus.size());
                out.write(count);
                int written = 0;
                for (final Map.Entry<String, String> entry : rtPlus.entrySet()) {
                    if (written >= count) {
                        break;
                    }
                    writeStringU8(out, entry.getKey());
                    writeStringU16(out, entry.getValue());
                    written++;
                }
            }

            prev = cur;
        }
        return out.toByteArray();
    }

    private static RfmMetadata decodeInner(final byte[] inner) throws IOException {
        final ByteBuffer buf = ByteBuffer.wrap(inner).order(ByteOrder.LITTLE_ENDIAN);
        requireRemaining(buf, 6);
        final int waveformCount = buf.getShort() & 0xFFFF;
        if (waveformCount > MAX_WAVEFORM_BINS) {
            throw new IOException("Excessive waveform bin count: " + waveformCount);
        }
        requireRemaining(buf, waveformCount + 4);
        final byte[] peaks = new byte[waveformCount];
        buf.get(peaks);

        final int eventCount = buf.getInt();
        if (eventCount < 0) {
            throw new IOException("Invalid event count: " + eventCount);
        }

        final List<RfmMetadataEvent> events = new ArrayList<>(Math.min(eventCount, 1024));
        int frequencyKhz = 0;
        String pi = "";
        int pty = 0;
        boolean tp = false;
        boolean stereo = false;
        int rmssi = RadioState.RMSSI_UNKNOWN;
        int sinr = RadioState.SINR_UNKNOWN;
        String country = "";
        String ps = "";
        String rt = "";
        RtPlusInfo rtPlus = RtPlusInfo.EMPTY;

        for (int i = 0; i < eventCount; i++) {
            requireRemaining(buf, 6);
            final long timeMs = buf.getInt() & 0xFFFFFFFFL;
            final int mask = buf.getShort() & 0xFFFF;

            if ((mask & FIELD_FREQUENCY) != 0) {
                requireRemaining(buf, 4);
                frequencyKhz = buf.getInt();
            }
            if ((mask & FIELD_PI) != 0) {
                requireRemaining(buf, 2);
                final int piRaw = buf.getShort() & 0xFFFF;
                pi = piRaw == PI_UNKNOWN ? "" : String.format(Locale.US, "%04X", piRaw);
            }
            if ((mask & FIELD_PTY) != 0) {
                requireRemaining(buf, 1);
                pty = buf.get() & 0xFF;
            }
            if ((mask & FIELD_TP) != 0) {
                requireRemaining(buf, 1);
                tp = buf.get() != 0;
            }
            if ((mask & FIELD_STEREO) != 0) {
                requireRemaining(buf, 1);
                stereo = buf.get() != 0;
            }
            if ((mask & FIELD_RMSSI) != 0) {
                rmssi = readMetric(buf, RadioState.RMSSI_UNKNOWN);
            }
            if ((mask & FIELD_SINR) != 0) {
                sinr = readMetric(buf, RadioState.SINR_UNKNOWN);
            }
            if ((mask & FIELD_COUNTRY) != 0) {
                country = readStringU8(buf);
            }
            if ((mask & FIELD_PS) != 0) {
                ps = readStringU8(buf);
            }
            if ((mask & FIELD_RT) != 0) {
                rt = readStringU16(buf);
            }
            if ((mask & FIELD_RT_PLUS) != 0) {
                requireRemaining(buf, 1);
                final int count = buf.get() & 0xFF;
                if (count == 0) {
                    rtPlus = RtPlusInfo.EMPTY;
                } else {
                    final Map<String, String> fields = new LinkedHashMap<>(count);
                    for (int j = 0; j < count; j++) {
                        final String key = readStringU8(buf);
                        final String value = readStringU16(buf);
                        if (!key.isEmpty() && !value.isEmpty()) {
                            fields.put(key, value);
                        }
                    }
                    rtPlus = new RtPlusInfo(fields);
                }
            }

            final RdsSnapshot snapshot = new RdsSnapshot(
                    frequencyKhz,
                    ps,
                    rt,
                    pi,
                    rtPlus,
                    country,
                    pty,
                    tp,
                    stereo,
                    rmssi,
                    sinr
            );
            events.add(new RfmMetadataEvent(timeMs, snapshot));
        }

        return new RfmMetadata(peaks, events);
    }

    private static int parsePi(final String pi) {
        if (pi == null || !pi.matches("[0-9a-fA-F]{1,4}")) {
            return PI_UNKNOWN;
        }
        final int value = Integer.parseInt(pi, 16);
        return value == PI_UNKNOWN ? PI_UNKNOWN : (value & 0xFFFF);
    }

    private static void writeMetric(final ByteArrayOutputStream out, final int value, final int unknownSentinel) {
        final short encoded = value == unknownSentinel
                ? METRIC_UNKNOWN
                : (short) Math.max(-128, Math.min(127, value));
        writeU16(out, encoded & 0xFFFF);
    }

    private static int readMetric(final ByteBuffer buf, final int unknownSentinel) throws IOException {
        requireRemaining(buf, 2);
        final short raw = buf.getShort();
        return raw == METRIC_UNKNOWN ? unknownSentinel : raw;
    }

    private static void writeU16(final ByteArrayOutputStream out, final int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
    }

    private static void writeU32(final ByteArrayOutputStream out, final long value) {
        out.write((int) (value & 0xFF));
        out.write((int) ((value >>> 8) & 0xFF));
        out.write((int) ((value >>> 16) & 0xFF));
        out.write((int) ((value >>> 24) & 0xFF));
    }

    private static void writeI32(final ByteArrayOutputStream out, final int value) {
        writeU32(out, value & 0xFFFFFFFFL);
    }

    private static void writeStringU8(final ByteArrayOutputStream out, final String value) {
        final byte[] utf8 = value != null ? value.getBytes(StandardCharsets.UTF_8) : new byte[0];
        final int len = Math.min(255, utf8.length);
        out.write(len);
        out.write(utf8, 0, len);
    }

    private static void writeStringU16(final ByteArrayOutputStream out, final String value) {
        final byte[] utf8 = value != null ? value.getBytes(StandardCharsets.UTF_8) : new byte[0];
        final int len = Math.min(65535, utf8.length);
        writeU16(out, len);
        out.write(utf8, 0, len);
    }

    private static String readStringU8(final ByteBuffer buf) throws IOException {
        requireRemaining(buf, 1);
        final int len = buf.get() & 0xFF;
        requireRemaining(buf, len);
        final byte[] bytes = new byte[len];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String readStringU16(final ByteBuffer buf) throws IOException {
        requireRemaining(buf, 2);
        final int len = buf.getShort() & 0xFFFF;
        requireRemaining(buf, len);
        final byte[] bytes = new byte[len];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void requireRemaining(final ByteBuffer buf, final int needed) throws IOException {
        if (buf.remaining() < needed) {
            throw new IOException("Truncated RFM1 payload");
        }
    }

    private static byte[] compress(final byte[] raw) {
        final Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        try {
            deflater.setInput(raw);
            deflater.finish();
            final ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, raw.length));
            final byte[] buffer = new byte[1024];
            while (!deflater.finished()) {
                final int count = deflater.deflate(buffer);
                if (count > 0) {
                    out.write(buffer, 0, count);
                }
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static byte[] decompress(
            final byte[] data,
            final int offset,
            final int length,
            final int expectedSize
    ) throws IOException {
        final Inflater inflater = new Inflater();
        try {
            inflater.setInput(data, offset, length);
            final byte[] out = new byte[expectedSize];
            int total = 0;
            while (total < expectedSize && !inflater.finished()) {
                final int count = inflater.inflate(out, total, expectedSize - total);
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                total += count;
            }
            if (total != expectedSize) {
                throw new IOException("RFM1 decompression size mismatch: expected " + expectedSize + ", got " + total);
            }
            return out;
        } catch (final DataFormatException e) {
            throw new IOException("Corrupt RFM1 compressed payload", e);
        } finally {
            inflater.end();
        }
    }
}
