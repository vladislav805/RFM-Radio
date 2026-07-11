package com.vlad805.fmradio.service.recording;

import java.io.IOException;

final class LameMp3Encoder {
    static {
        System.loadLibrary("mp3encoder");
    }

    private long mHandle;

    LameMp3Encoder(final int sampleRate, final int channels, final int bitrateKbps, final int quality) throws IOException {
        mHandle = nativeInit(sampleRate, channels, bitrateKbps, quality);
        if (mHandle == 0L) {
            throw new IOException("Cannot initialize MP3 encoder");
        }
    }

    int encodeInterleaved(final short[] pcm, final int samplesPerChannel, final byte[] output) throws IOException {
        final int written = nativeEncodeInterleaved(mHandle, pcm, samplesPerChannel, output);
        if (written < 0) {
            throw new IOException("MP3 encode failed");
        }
        return written;
    }

    int flush(final byte[] output) throws IOException {
        final int written = nativeFlush(mHandle, output);
        if (written < 0) {
            throw new IOException("MP3 encoder flush failed");
        }
        return written;
    }

    void close() {
        if (mHandle != 0L) {
            nativeClose(mHandle);
            mHandle = 0L;
        }
    }

    private static native long nativeInit(int sampleRate, int channels, int bitrate, int quality);

    private static native int nativeEncodeInterleaved(long handle, short[] pcm, int samplesPerChannel, byte[] output);

    private static native int nativeFlush(long handle, byte[] output);

    private static native void nativeClose(long handle);
}
