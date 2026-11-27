package com.vlad805.fmradio.recordings;

import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;

/**
 * JNI wrapper around {@code libopus} for Ogg Opus decoding and sample-accurate seeking
 * across API 23+ (used for Opus playback on API < 29 and for Opus trimming on all APIs).
 */
public final class OggOpusDecoder implements Closeable {
    public static final int SAMPLE_RATE = 48_000;
    public static final int CHANNELS = 2;

    static {
        System.loadLibrary("opusencoder");
    }

    private long mHandle;
    private final long mTotalSamples;

    private OggOpusDecoder(final long handle) {
        mHandle = handle;
        mTotalSamples = nativeGetTotalSamples(handle);
    }

    @NonNull
    public static OggOpusDecoder open(@NonNull final ParcelFileDescriptor pfd) throws IOException {
        final long handle = nativeOpenFd(pfd.getFd());

        if (handle == 0L) {
            throw new IOException("Cannot open Ogg Opus decoder");
        }

        return new OggOpusDecoder(handle);
    }

    @NonNull
    public static OggOpusDecoder open(@NonNull final File file) throws IOException {
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) {
            return open(pfd);
        }
    }

    public long getTotalSamples() {
        return mTotalSamples;
    }

    public long getDurationMillis() {
        return (mTotalSamples * 1000L) / SAMPLE_RATE;
    }

    public void seekSamples(final long targetSample) {
        if (mHandle != 0L) {
            nativeSeek(mHandle, targetSample);
        }
    }

    public void seekMillis(final long positionMs) {
        final long sample = (Math.max(0L, positionMs) * SAMPLE_RATE) / 1000L;
        seekSamples(sample);
    }

    /**
     * Decodes up to {@code outPcm.length} interleaved stereo 16-bit PCM samples.
     * Returns {@code 0} on EOF.
     */
    public int decode(@NonNull final short[] outPcm) {
        if (mHandle == 0L) {
            return 0;
        }

        return nativeDecode(mHandle, outPcm);
    }

    @Override
    public void close() {
        if (mHandle != 0L) {
            nativeClose(mHandle);
            mHandle = 0L;
        }
    }

    private static native long nativeOpenFd(int fd) throws IOException;

    private static native long nativeGetTotalSamples(long handle);

    private static native void nativeSeek(long handle, long targetSample);

    private static native int nativeDecode(long handle, short[] outPcm);

    private static native void nativeClose(long handle);
}
