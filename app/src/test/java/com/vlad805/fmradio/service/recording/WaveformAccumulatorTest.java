package com.vlad805.fmradio.service.recording;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public class WaveformAccumulatorTest {

    @Test
    public void silenceProducesZeroPeaks() {
        final WaveformAccumulator acc = new WaveformAccumulator(48_000, 2);
        final short[] silence = new short[48_000 * 2]; // 1 second
        acc.addPcm(silence, silence.length);

        final byte[] peaks = acc.finish(240);
        assertEquals(20, peaks.length);
        for (final byte peak : peaks) {
            assertEquals(0, peak & 0xFF);
        }
    }

    @Test
    public void fullScaleAndMinShortProduceMaxPeak255() {
        final WaveformAccumulator acc = new WaveformAccumulator(1_000, 2);
        final short[] chunk = new short[100];
        chunk[10] = Short.MAX_VALUE;
        acc.addPcm(chunk, chunk.length);

        final short[] chunkMin = new short[100];
        chunkMin[20] = Short.MIN_VALUE;
        acc.addPcm(chunkMin, chunkMin.length);

        final byte[] peaks = acc.finish(2);
        assertEquals(2, peaks.length);
        assertEquals(255, peaks[0] & 0xFF);
        assertEquals(255, peaks[1] & 0xFF);
    }

    @Test
    public void longStreamCompressesBucketsAndPreservesPeaks() {
        // 100 Hz stereo -> initial bucket width = 10 samples (50ms).
        // Feed 3000 buckets (30,000 samples) to force multiple 1024->512 merges.
        final WaveformAccumulator acc = new WaveformAccumulator(100, 2);
        final short[] quiet = new short[100];
        Arrays.fill(quiet, (short) 3277); // ~10% -> ~25

        for (int i = 0; i < 300; i++) {
            if (i == 150) {
                final short[] loud = new short[100];
                loud[5] = 32767;
                acc.addPcm(loud, loud.length);
            } else {
                acc.addPcm(quiet, quiet.length);
            }
        }

        final byte[] peaks = acc.finish(240);
        assertEquals(240, peaks.length);
        int maxFound = 0;
        for (final byte p : peaks) {
            maxFound = Math.max(maxFound, p & 0xFF);
        }
        assertEquals(255, maxFound);
        assertTrue((peaks[0] & 0xFF) >= 20 && (peaks[0] & 0xFF) <= 30);
    }
}
