package com.vlad805.fmradio.service.recording;

import java.io.IOException;
import java.io.Writer;
import java.util.Locale;

/** Writes complete RDS snapshots as consecutive SRT cues. Times are PCM-relative. */
public final class RdsSubtitleWriter {
    private final Writer output;
    private RdsSnapshot current;
    private long startMillis;
    private int cueNumber;

    public RdsSubtitleWriter(final Writer output) {
        this.output = output;
    }

    public void update(final long positionMillis, final RdsSnapshot snapshot) throws IOException {
        if (snapshot.equals(current)) {
            return;
        }
        final long position = Math.max(startMillis, positionMillis);
        writeCue(position);
        startMillis = position;
        current = snapshot;
    }

    public void finish(final long durationMillis) throws IOException {
        try {
            writeCue(durationMillis);
        } finally {
            output.close();
        }
    }

    private void writeCue(final long endMillis) throws IOException {
        if (current == null || endMillis <= startMillis) {
            return;
        }
        output.write(Integer.toString(++cueNumber));
        output.write("\n" + timestamp(startMillis) + " --> " + timestamp(endMillis) + "\n");
        output.write(current.toSubtitleText());
        output.write("\n\n");
    }

    private static String timestamp(final long millis) {
        return String.format(
                Locale.US,
                "%02d:%02d:%02d,%03d",
                millis / 3600000,
                (millis / 60000) % 60,
                (millis / 1000) % 60,
                millis % 1000
        );
    }
}
