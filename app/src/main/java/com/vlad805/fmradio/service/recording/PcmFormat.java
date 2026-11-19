package com.vlad805.fmradio.service.recording;

public final class PcmFormat {
    public static final PcmFormat PCM16_STEREO_44100 = new PcmFormat(44100, 2);
    public static final PcmFormat PCM16_STEREO_48000 = new PcmFormat(48000, 2);

    private final int mSampleRateHz;
    private final int mChannelCount;

    private PcmFormat(final int sampleRateHz, final int channelCount) {
        mSampleRateHz = sampleRateHz;
        mChannelCount = channelCount;
    }

    public int sampleRateHz() {
        return mSampleRateHz;
    }

    public int channelCount() {
        return mChannelCount;
    }

    public boolean matches(final int sampleRateHz, final int channelCount) {
        return mSampleRateHz == sampleRateHz && mChannelCount == channelCount;
    }
}
