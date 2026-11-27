package com.vlad805.fmradio.service.recording;

import androidx.annotation.NonNull;

import java.util.Objects;

/** One timestamped RDS and tuner telemetry snapshot inside an {@link RfmMetadata} timeline. */
public final class RfmMetadataEvent {
    private final long timeMs;
    @NonNull
    private final RdsSnapshot snapshot;

    public RfmMetadataEvent(final long timeMs, @NonNull final RdsSnapshot snapshot) {
        this.timeMs = Math.max(0L, timeMs);
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }

    public long getTimeMs() {
        return timeMs;
    }

    @NonNull
    public RdsSnapshot getSnapshot() {
        return snapshot;
    }

    @Override
    public boolean equals(final Object other) {
        if (!(other instanceof RfmMetadataEvent)) {
            return false;
        }

        final RfmMetadataEvent event = (RfmMetadataEvent) other;
        return timeMs == event.timeMs && snapshot.equals(event.snapshot);
    }

    @Override
    public int hashCode() {
        return Objects.hash(timeMs, snapshot);
    }
}
