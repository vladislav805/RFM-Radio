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

/**
 * Lossless MPEG Layer III frame-level MP3 trimmer that preserves audio frames without re-encoding
 * and slices embedded {@code RFM1} metadata inside a fresh ID3v2.3 header.
 */
public final class Mp3Trimmer {
    /**
     * MPEG-1 Layer III bitrates (kbps) indexed by header bits 12..15.
     */
    private static final int[] BITRATES_V1_L3 = {
            0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0
    };

    /**
     * MPEG-2 / 2.5 Layer III bitrates (kbps).
     */
    private static final int[] BITRATES_V2_L3 = {
            0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0
    };

    private static final int[] SAMPLE_RATES_V1 = {44100, 48000, 32000, 0};
    private static final int[] SAMPLE_RATES_V2 = {22050, 24000, 16000, 0};
    private static final int[] SAMPLE_RATES_V25 = {11025, 12000, 8000, 0};

    private Mp3Trimmer() {}

    public static void trim(
            @NonNull final File input,
            @NonNull final File output,
            final long startMs,
            final long endMs,
            @NonNull final String title,
            final long recordedAtMillis,
            @Nullable final RfmMetadata metadata,
            @Nullable final TrimProgressListener progress
    ) throws IOException {
        if (endMs <= startMs) {
            throw new IOException("Invalid trim range");
        }

        final File rawTemp = File.createTempFile("rfm-mp3-trim-", ".mp3", output.getParentFile());

        try {
            final long totalDurationMs;

            try (
                    RandomAccessFile raf = new RandomAccessFile(input, "r");
                    OutputStream rawOut = new BufferedOutputStream(new FileOutputStream(rawTemp, false), 65536)
            ) {
                final long fileLen = raf.length();
                long pos = skipId3v2(raf, fileLen);
                double currentMs = 0.0;
                int framesWritten = 0;
                int lastPercent = -1;
                final byte[] hdr = new byte[4];
                byte[] frameBuf = new byte[4096];

                while (pos + 4 <= fileLen) {
                    raf.seek(pos);
                    raf.readFully(hdr);

                    final FrameInfo info = parseFrameHeader(hdr);
                    if (info == null || pos + info.frameSize > fileLen) {
                        pos++;
                        continue;
                    }

                    final double frameStartMs = currentMs;
                    final double frameEndMs = currentMs + info.durationMs;

                    if (frameEndMs > startMs && frameStartMs < endMs) {
                        if (frameBuf.length < info.frameSize) {
                            frameBuf = new byte[info.frameSize];
                        }

                        System.arraycopy(hdr, 0, frameBuf, 0, 4);
                        if (info.frameSize > 4) {
                            raf.readFully(frameBuf, 4, info.frameSize - 4);
                        }
                        rawOut.write(frameBuf, 0, info.frameSize);
                        framesWritten++;
                    }

                    currentMs = frameEndMs;
                    pos += info.frameSize;

                    if (progress != null && fileLen > 0L) {
                        final int pct = (int) Math.min(99L, (pos * 100L) / fileLen);
                        if (pct != lastPercent) {
                            lastPercent = pct;
                            progress.onProgress(pct);
                        }
                    }
                }

                if (framesWritten == 0) {
                    throw new IOException("No MP3 frames found in trim range");
                }

                totalDurationMs = Math.round(currentMs);
            }

            final byte[] rfmBytes = metadata != null
                    ? RfmMetadataCodec.encode(metadata.slice(totalDurationMs, startMs, endMs))
                    : null;

            RecordingMetadataIO.prependMp3Id3WithMetadata(rawTemp, output, title, recordedAtMillis, rfmBytes);

            if (progress != null) {
                progress.onProgress(100);
            }
        } finally {
            if (rawTemp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                rawTemp.delete();
            }
        }
    }

    private static long skipId3v2(@NonNull final RandomAccessFile raf, final long fileLen) throws IOException {
        if (fileLen < 10) {
            return 0L;
        }

        final byte[] tag = new byte[10];
        raf.seek(0L);
        raf.readFully(tag);

        if (tag[0] != 'I' || tag[1] != 'D' || tag[2] != '3') {
            return 0L;
        }

        // ID3v2 stores total tag payload size as a 28-bit synchsafe integer (7 bits per byte, MSB=0).
        final int tagSize = (
                ((tag[6] & 0x7F) << 21) |
                ((tag[7] & 0x7F) << 14) |
                ((tag[8] & 0x7F) << 7) |
                (tag[9] & 0x7F)
        );

        return Math.min(fileLen, 10L + tagSize);
    }

    /**
     * Parses a 4-byte MPEG Audio frame header:
     *   byte 0: 11111111 (sync bits 0..7)
     *   byte 1: 111VVLLP (sync 8..10, VV=MPEG version, LL=Layer, P=CRC flag)
     *   byte 2: BBBBSSPX (BBBB=bitrate index, SS=sample rate index, P=padding, X=private)
     */
    @Nullable
    static FrameInfo parseFrameHeader(@NonNull final byte[] h) {
        final int b0 = h[0] & 0xFF;
        final int b1 = h[1] & 0xFF;
        final int b2 = h[2] & 0xFF;

        // 11-bit frame sync word: 0xFF followed by top 3 bits set (0xE0).
        if (b0 != 0xFF || (b1 & 0xE0) != 0xE0) {
            return null;
        }

        // MPEG version: 0=v2.5, 1=reserved, 2=v2, 3=v1. Layer: 1=Layer III (MP3).
        final int versionBits = (b1 >>> 3) & 0x03;
        final int layerBits = (b1 >>> 1) & 0x03;

        if (versionBits == 1 || layerBits != 1) {
            return null;
        }

        // Bitrate index (1..14 valid; 0=free, 15=bad), sample rate index (0..2 valid; 3=reserved), padding (0/1).
        final int bitrateIdx = (b2 >>> 4) & 0x0F;
        final int sampleRateIdx = (b2 >>> 2) & 0x03;
        final int padding = (b2 >>> 1) & 0x01;

        if (bitrateIdx == 0 || bitrateIdx == 15 || sampleRateIdx == 3) {
            return null;
        }

        final boolean isMpeg1 = (versionBits == 3);
        final int bitrateKbps = isMpeg1 ? BITRATES_V1_L3[bitrateIdx] : BITRATES_V2_L3[bitrateIdx];
        final int sampleRate;

        if (versionBits == 3) {
            sampleRate = SAMPLE_RATES_V1[sampleRateIdx];
        } else if (versionBits == 2) {
            sampleRate = SAMPLE_RATES_V2[sampleRateIdx];
        } else {
            sampleRate = SAMPLE_RATES_V25[sampleRateIdx];
        }

        if (bitrateKbps <= 0 || sampleRate <= 0) {
            return null;
        }

        // MPEG-1 Layer III has 1152 samples/frame; MPEG-2/2.5 Layer III has 576.
        final int samplesPerFrame = isMpeg1 ? 1152 : 576;
        final int frameSize = ((samplesPerFrame / 8) * (bitrateKbps * 1000)) / sampleRate + padding;

        if (frameSize < 4) {
            return null;
        }

        final double durationMs = (samplesPerFrame * 1000.0) / sampleRate;
        return new FrameInfo(frameSize, durationMs);
    }

    static final class FrameInfo {
        final int frameSize;
        final double durationMs;

        FrameInfo(final int frameSize, final double durationMs) {
            this.frameSize = frameSize;
            this.durationMs = durationMs;
        }
    }
}
