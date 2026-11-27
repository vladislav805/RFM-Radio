package com.vlad805.fmradio.service.recording;

import androidx.annotation.NonNull;

/**
 * Streaming O(1)-memory PCM peak amplitude collector.
 *
 * Accumulates max(|sample|) into fixed-duration buckets. Whenever the bucket array reaches
 * {@link #MAX_BUCKETS}, adjacent pairs are merged (keeping max) and the bucket width doubles,
 * so arbitrarily long recordings stay within a tiny fixed memory footprint.
 */
public final class WaveformAccumulator {
    private static final int MAX_BUCKETS = 1024;

    private final int[] buckets = new int[MAX_BUCKETS];
    private int bucketCount = 0;
    private long samplesPerBucket;
    private long currentBucketSamples = 0;
    private int currentBucketPeak = 0;

    public WaveformAccumulator(final int sampleRateHz, final int channels) {
        final long initialWidth = ((long) Math.max(1, sampleRateHz) * Math.max(1, channels)) / 20L;
        this.samplesPerBucket = Math.max(1L, initialWidth);
    }

    public void addPcm(@NonNull final short[] data, final int length) {
        final int safeLen = Math.max(0, Math.min(length, data.length));

        for (int i = 0; i < safeLen; i++) {
            final short s = data[i];
            final int abs = s == Short.MIN_VALUE ? 32767 : Math.abs(s);

            if (abs > currentBucketPeak) {
                currentBucketPeak = abs;
            }

            currentBucketSamples++;
            if (currentBucketSamples >= samplesPerBucket) {
                commitCurrentBucket();
            }
        }
    }

    @NonNull
    public byte[] finish(final int targetBins) {
        if (targetBins <= 0) {
            return new byte[0];
        }

        final int totalBuckets = bucketCount + (currentBucketSamples > 0 ? 1 : 0);
        if (totalBuckets == 0) {
            return new byte[0];
        }

        final int[] all = new int[totalBuckets];
        System.arraycopy(buckets, 0, all, 0, bucketCount);
        if (currentBucketSamples > 0) {
            all[bucketCount] = currentBucketPeak;
        }

        final int outBins = Math.min(targetBins, totalBuckets);
        final byte[] result = new byte[outBins];

        for (int i = 0; i < outBins; i++) {
            final int start = (int) ((i * (long) totalBuckets) / outBins);
            final int end = Math.max(start + 1, (int) (((i + 1L) * totalBuckets) / outBins));
            int peak = 0;

            for (int j = start; j < end && j < totalBuckets; j++) {
                if (all[j] > peak) {
                    peak = all[j];
                }
            }

            result[i] = (byte) Math.min(255, (peak * 255L) / 32767L);
        }

        return result;
    }

    private void commitCurrentBucket() {
        if (bucketCount >= MAX_BUCKETS) {
            compressBuckets();
        }
        buckets[bucketCount++] = currentBucketPeak;
        currentBucketPeak = 0;
        currentBucketSamples = 0;
    }

    private void compressBuckets() {
        final int half = bucketCount / 2;
        for (int i = 0; i < half; i++) {
            buckets[i] = Math.max(buckets[2 * i], buckets[2 * i + 1]);
        }
        bucketCount = half;
        samplesPerBucket *= 2L;
    }
}
