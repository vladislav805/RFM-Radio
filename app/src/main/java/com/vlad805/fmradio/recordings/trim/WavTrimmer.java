package com.vlad805.fmradio.recordings.trim;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.vlad805.fmradio.service.recording.RecordingMetadataIO;
import com.vlad805.fmradio.service.recording.RfmMetadata;
import com.vlad805.fmradio.service.recording.RfmMetadataCodec;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Lossless PCM WAV trimmer that preserves format parameters and slices embedded {@code RFM1} metadata.
 */
public final class WavTrimmer {
    private static final int COPY_BUFFER_SIZE = 65536;

    private WavTrimmer() {}

    public static void trim(
            @NonNull final File input,
            @NonNull final File output,
            final long startMs,
            final long endMs,
            @Nullable final RfmMetadata metadata,
            @Nullable final TrimProgressListener progress
    ) throws IOException {
        if (endMs <= startMs) {
            throw new IOException("Invalid trim range");
        }

        int numChannels = 0;
        int sampleRate = 0;
        int byteRate = 0;
        int blockAlign = 0;
        int bitsPerSample = 0;
        long dataOffset = -1L;
        long dataSize = -1L;

        try (RandomAccessFile raf = new RandomAccessFile(input, "r")) {
            final byte[] riff = new byte[12];
            raf.readFully(riff);

            if (
                    !"RIFF".equals(new String(riff, 0, 4, StandardCharsets.US_ASCII)) ||
                    !"WAVE".equals(new String(riff, 8, 4, StandardCharsets.US_ASCII))
            ) {
                throw new IOException("Not a valid WAV file");
            }

            final byte[] chunkHdr = new byte[8];
            while (raf.getFilePointer() + 8 <= raf.length()) {
                raf.readFully(chunkHdr);
                final String id = new String(chunkHdr, 0, 4, StandardCharsets.US_ASCII);
                // Unsigned 32-bit little-endian RIFF chunk size.
                final long size = ByteBuffer.wrap(chunkHdr, 4, 4)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .getInt() & 0xFFFFFFFFL;

                if ("fmt ".equals(id) && size >= 16) {
                    final byte[] fmt = new byte[16];
                    raf.readFully(fmt);
                    final ByteBuffer fb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN);
                    final int audioFormat = fb.getShort() & 0xFFFF;

                    // Only uncompressed linear PCM (format tag 1) can be byte-sliced directly.
                    if (audioFormat != 1) {
                        throw new IOException("Unsupported non-PCM WAV format: " + audioFormat);
                    }

                    numChannels = fb.getShort() & 0xFFFF;
                    sampleRate = fb.getInt();
                    byteRate = fb.getInt();
                    blockAlign = fb.getShort() & 0xFFFF;
                    bitsPerSample = fb.getShort() & 0xFFFF;

                    if (size > 16) {
                        // RIFF chunks are word-aligned: odd-sized chunks have a 1-byte pad (size & 1).
                        raf.seek(raf.getFilePointer() + (size - 16) + (size & 1L));
                    }
                } else if ("data".equals(id)) {
                    dataOffset = raf.getFilePointer();
                    dataSize = Math.min(size, raf.length() - dataOffset);
                    break;
                } else {
                    // Skip unknown chunks, including the optional RIFF odd-length pad byte.
                    raf.seek(raf.getFilePointer() + size + (size & 1L));
                }
            }

            if (dataOffset < 0L || dataSize <= 0L || byteRate <= 0 || blockAlign <= 0) {
                throw new IOException("Missing WAV fmt or data chunk");
            }

            final long totalDurationMs = (dataSize * 1000L) / byteRate;
            // Align start down and end up to whole PCM frames (blockAlign = channels * bytesPerSample).
            final long rawStartByte = (Math.max(0L, startMs) * byteRate) / 1000L;
            final long startByte = (rawStartByte / blockAlign) * blockAlign;
            final long rawEndByte = (Math.min(endMs, totalDurationMs) * byteRate) / 1000L;
            final long endByte = Math.min(dataSize, ((rawEndByte + blockAlign - 1) / blockAlign) * blockAlign);
            final long sliceLength = endByte - startByte;

            if (sliceLength <= 0L) {
                throw new IOException("Trimmed WAV selection is empty");
            }

            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(output, false), COPY_BUFFER_SIZE)) {
                writeWavHeader(out, numChannels, sampleRate, byteRate, blockAlign, bitsPerSample, sliceLength);

                raf.seek(dataOffset + startByte);
                final byte[] buffer = new byte[COPY_BUFFER_SIZE];
                long remaining = sliceLength;
                long copied = 0L;
                int lastPercent = -1;

                while (remaining > 0L) {
                    final int toRead = (int) Math.min(buffer.length, remaining);
                    final int read = raf.read(buffer, 0, toRead);

                    if (read <= 0) {
                        break;
                    }

                    out.write(buffer, 0, read);
                    remaining -= read;
                    copied += read;

                    if (progress != null) {
                        final int pct = (int) ((copied * 100L) / sliceLength);
                        if (pct != lastPercent) {
                            lastPercent = pct;
                            progress.onProgress(pct);
                        }
                    }
                }
            }

            if (metadata != null) {
                final RfmMetadata sliced = metadata.slice(totalDurationMs, startMs, endMs);
                final byte[] rfmBytes = RfmMetadataCodec.encode(sliced);
                RecordingMetadataIO.writeWavChunk(output, sliceLength, rfmBytes);
            }
        }
    }

    private static void writeWavHeader(
            @NonNull final OutputStream out,
            final int numChannels,
            final int sampleRate,
            final int byteRate,
            final int blockAlign,
            final int bitsPerSample,
            final long dataSize
    ) throws IOException {
        final ByteBuffer hdr = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        hdr.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        hdr.putInt((int) (36L + dataSize));
        hdr.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        hdr.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        hdr.putInt(16);
        hdr.putShort((short) 1);
        hdr.putShort((short) numChannels);
        hdr.putInt(sampleRate);
        hdr.putInt(byteRate);
        hdr.putShort((short) blockAlign);
        hdr.putShort((short) bitsPerSample);
        hdr.put("data".getBytes(StandardCharsets.US_ASCII));
        hdr.putInt((int) dataSize);
        out.write(hdr.array());
    }
}
