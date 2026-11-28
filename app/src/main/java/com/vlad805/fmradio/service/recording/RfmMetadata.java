package com.vlad805.fmradio.service.recording;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Immutable embedded recording metadata: precomputed waveform peaks and a chronologically
 * sorted timeline of complete RDS + signal telemetry snapshots.
 */
public final class RfmMetadata {
    public static final int DEFAULT_WAVEFORM_BINS = 240;

    private final byte[] waveformPeaks;
    private final List<RfmMetadataEvent> events;

    public RfmMetadata(
            @Nullable final byte[] waveformPeaks,
            @Nullable final List<RfmMetadataEvent> events
    ) {
        this.waveformPeaks = waveformPeaks != null
                ? Arrays.copyOf(waveformPeaks, waveformPeaks.length)
                : new byte[0];
        if (events == null || events.isEmpty()) {
            this.events = Collections.emptyList();
        } else {
            final List<RfmMetadataEvent> sorted = new ArrayList<>(events);
            sorted.sort(Comparator.comparingLong(RfmMetadataEvent::getTimeMs));
            this.events = Collections.unmodifiableList(sorted);
        }
    }

    @NonNull
    public byte[] getWaveformPeaks() {
        return Arrays.copyOf(waveformPeaks, waveformPeaks.length);
    }

    public int getWaveformBinCount() {
        return waveformPeaks.length;
    }

    @NonNull
    public List<RfmMetadataEvent> getEvents() {
        return events;
    }

    /**
     * Returns the active event at {@code positionMs} (the last event with {@code timeMs <= positionMs}),
     * or {@code null} if no event precedes {@code positionMs}.
     */
    @Nullable
    public RfmMetadataEvent getEventAt(final long positionMs) {
        if (events.isEmpty() || positionMs < events.get(0).getTimeMs()) {
            return null;
        }
        int low = 0;
        int high = events.size() - 1;
        while (low < high) {
            final int mid = (low + high + 1) >>> 1;
            if (events.get(mid).getTimeMs() <= positionMs) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return events.get(low);
    }

    /**
     * Slices this metadata to {@code [startMs, endMs]} within a recording of {@code totalDurationMs},
     * rebasing event timestamps to {@code 0} and proportionally slicing/resampling waveform peaks.
     */
    @NonNull
    public RfmMetadata slice(final long totalDurationMs, final long startMs, final long endMs) {
        final long safeDuration = Math.max(0L, totalDurationMs);
        final long clampedStart = Math.max(0L, Math.min(startMs, safeDuration));
        final long clampedEnd = Math.max(clampedStart, Math.min(endMs, safeDuration));

        final List<RfmMetadataEvent> slicedEvents = new ArrayList<>();
        final RfmMetadataEvent initial = getEventAt(clampedStart);
        if (initial != null) {
            slicedEvents.add(new RfmMetadataEvent(0L, initial.getSnapshot()));
        }
        for (final RfmMetadataEvent event : events) {
            final long t = event.getTimeMs();
            if (t > clampedStart && t <= clampedEnd) {
                slicedEvents.add(new RfmMetadataEvent(t - clampedStart, event.getSnapshot()));
            }
        }

        final byte[] slicedPeaks = sliceWaveform(safeDuration, clampedStart, clampedEnd);
        return new RfmMetadata(slicedPeaks, slicedEvents);
    }

    private byte[] sliceWaveform(final long totalDurationMs, final long startMs, final long endMs) {
        if (waveformPeaks.length == 0 || totalDurationMs <= 0L || endMs <= startMs) {
            return new byte[0];
        }
        final int n = waveformPeaks.length;
        final int startIdx = Math.max(0, Math.min(n - 1, (int) ((startMs * n) / totalDurationMs)));
        final int endIdx = Math.max(
                startIdx + 1,
                Math.min(n, (int) Math.ceil((endMs * (double) n) / totalDurationMs))
        );
        final int srcLen = endIdx - startIdx;
        if (srcLen <= DEFAULT_WAVEFORM_BINS) {
            return Arrays.copyOfRange(waveformPeaks, startIdx, endIdx);
        }
        final byte[] resampled = new byte[DEFAULT_WAVEFORM_BINS];
        for (int i = 0; i < DEFAULT_WAVEFORM_BINS; i++) {
            final int binStart = startIdx + (int) ((i * (long) srcLen) / DEFAULT_WAVEFORM_BINS);
            final int binEnd = Math.max(
                    binStart + 1,
                    startIdx + (int) (((i + 1L) * srcLen) / DEFAULT_WAVEFORM_BINS)
            );
            int maxPeak = 0;
            for (int j = binStart; j < binEnd && j < endIdx; j++) {
                final int v = waveformPeaks[j] & 0xFF;
                if (v > maxPeak) {
                    maxPeak = v;
                }
            }
            resampled[i] = (byte) maxPeak;
        }
        return resampled;
    }

    @Override
    public boolean equals(final Object other) {
        if (!(other instanceof RfmMetadata)) {
            return false;
        }
        final RfmMetadata that = (RfmMetadata) other;
        return Arrays.equals(waveformPeaks, that.waveformPeaks) && events.equals(that.events);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(events);
        result = 31 * result + Arrays.hashCode(waveformPeaks);
        return result;
    }
}
