package com.vlad805.fmradio.service.recording;

import android.content.Context;
import androidx.annotation.Nullable;

import java.io.IOException;

public final class RecordOpusService extends RecordService {
    private OggOpusEncoder encoder;

    public RecordOpusService(Context context, int kHz, int sampleRate, @Nullable RecordingListener listener) {
        super(context, kHz, sampleRate, listener);
    }

    @Override
    protected String getExtension() {
        return "opus";
    }

    @Override
    protected String getMimeType() {
        return "audio/ogg";
    }

    @Override
    protected void onFileCreated() throws IOException {
        encoder = new OggOpusEncoder(getSampleRate(), mBufferOutStream);
    }

    @Override
    protected int writePcm(short[] data, int length) throws IOException {
        return encoder.write(data, length);
    }

    @Override
    protected void onFinishRecording() throws IOException {
        mRecordLength += encoder.finish();
    }
}
