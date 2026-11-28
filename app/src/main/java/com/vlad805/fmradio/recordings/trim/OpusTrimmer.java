package com.vlad805.fmradio.recordings.trim;

import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.vlad805.fmradio.recordings.OggOpusDecoder;
import com.vlad805.fmradio.service.recording.OggOpusEncoder;
import com.vlad805.fmradio.service.recording.RecordingMetadataIO;
import com.vlad805.fmradio.service.recording.RfmMetadata;
import com.vlad805.fmradio.service.recording.RfmMetadataCodec;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Sample-accurate Ogg Opus trimmer: decodes {@code [startMs, endMs]} through {@link OggOpusDecoder}
 * (libopus), re-encodes via {@link OggOpusEncoder} (96 kbps stereo VBR), and injects sliced
 * {@code RFM1} metadata into {@code OpusTags}.
 */
public final class OpusTrimmer {
    private static final int PCM_CHUNK_SHORTS = 4096;

    private OpusTrimmer() {}

    public static void trim(
            @NonNull final ParcelFileDescriptor inputPfd,
            @NonNull final File tempDir,
            @NonNull final File output,
            final long startMs,
            final long endMs,
            @Nullable final RfmMetadata metadata,
            @Nullable final TrimProgressListener progress
    ) throws IOException {
        if (endMs <= startMs) {
            throw new IOException("Invalid trim range");
        }

        final File rawEncoded = File.createTempFile("rfm-opus-trim-", ".opus", tempDir);

        try {
            final long totalDurationMs;

            try (OggOpusDecoder decoder = OggOpusDecoder.open(inputPfd)) {
                final long totalSamples = decoder.getTotalSamples();
                totalDurationMs = decoder.getDurationMillis();

                // Convert millisecond boundaries to 48 kHz per-channel sample indices.
                final long startSample = Math.max(0L, (startMs * OggOpusDecoder.SAMPLE_RATE) / 1000L);
                final long endSample = Math.min(totalSamples, (endMs * OggOpusDecoder.SAMPLE_RATE) / 1000L);
                final long targetSamples = endSample - startSample;

                if (targetSamples <= 0L) {
                    throw new IOException("Trimmed Opus selection is empty");
                }

                decoder.seekSamples(startSample);

                try (OutputStream rawOut = new BufferedOutputStream(new FileOutputStream(rawEncoded, false), 65536)) {
                    final OggOpusEncoder encoder = new OggOpusEncoder(OggOpusDecoder.SAMPLE_RATE, rawOut);
                    final short[] pcm = new short[PCM_CHUNK_SHORTS];
                    long remainingSamples = targetSamples;
                    int lastPercent = -1;

                    try {
                        while (remainingSamples > 0L) {
                            final int shortsRead = decoder.decode(pcm);
                            if (shortsRead <= 0) {
                                break;
                            }

                            final int framesRead = shortsRead / OggOpusDecoder.CHANNELS;
                            final int framesToWrite = (int) Math.min(framesRead, remainingSamples);

                            if (framesToWrite > 0) {
                                encoder.write(pcm, framesToWrite * OggOpusDecoder.CHANNELS);
                                remainingSamples -= framesToWrite;
                            }

                            if (progress != null) {
                                final long done = targetSamples - remainingSamples;
                                final int pct = (int) Math.min(95L, (done * 95L) / targetSamples);
                                if (pct != lastPercent) {
                                    lastPercent = pct;
                                    progress.onProgress(pct);
                                }
                            }
                        }
                    } finally {
                        encoder.finish();
                    }
                }
            }

            if (metadata != null) {
                final RfmMetadata sliced = metadata.slice(totalDurationMs, startMs, endMs);
                final byte[] rfmBytes = RfmMetadataCodec.encode(sliced);
                RecordingMetadataIO.embedInOggOpus(rawEncoded, output, rfmBytes);
            } else {
                if (!rawEncoded.renameTo(output)) {
                    copyFile(rawEncoded, output);
                }
            }

            if (progress != null) {
                progress.onProgress(100);
            }
        } finally {
            if (rawEncoded.exists()) {
                //noinspection ResultOfMethodCallIgnored
                rawEncoded.delete();
            }
        }
    }

    private static void copyFile(@NonNull final File src, @NonNull final File dst) throws IOException {
        try (
                InputStream in = new FileInputStream(src);
                OutputStream out = new FileOutputStream(dst, false)
        ) {
            final byte[] buf = new byte[65536];
            int read;
            while ((read = in.read(buf)) > 0) {
                out.write(buf, 0, read);
            }
        }
    }
}
