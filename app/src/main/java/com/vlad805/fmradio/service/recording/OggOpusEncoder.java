package com.vlad805.fmradio.service.recording;

import java.io.IOException;
import java.io.OutputStream;

/** One Ogg Opus stream, owned by the PCM recording writer or Opus trimmer. */
public final class OggOpusEncoder {
    static {
        System.loadLibrary("opusencoder");
    }

    private final OutputStream output;
    private long handle;

    public OggOpusEncoder(int sampleRate, OutputStream output) throws IOException {
        this.output = output;
        handle = nativeCreate(sampleRate, 96000);
    }

    public int write(short[] samples, int length) throws IOException {
        if (handle == 0) {
            throw new IOException("Opus encoder is closed");
        }
        nativeWrite(handle, samples, length);
        return writePages();
    }

    public int finish() throws IOException {
        if (handle == 0) {
            return 0;
        }
        try {
            nativeDrain(handle);
            return writePages();
        } finally {
            nativeDestroy(handle);
            handle = 0;
        }
    }

    private int writePages() throws IOException {
        int written = 0;
        byte[] page;
        while ((page = nativeNextPage(handle)) != null) {
            output.write(page);
            written += page.length;
        }
        return written;
    }

    private static native long nativeCreate(int sampleRate, int bitrate) throws IOException;
    private static native void nativeWrite(long handle, short[] samples, int length) throws IOException;
    private static native byte[] nativeNextPage(long handle) throws IOException;
    private static native void nativeDrain(long handle) throws IOException;
    private static native void nativeDestroy(long handle);
}
