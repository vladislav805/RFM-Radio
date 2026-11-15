package com.vlad805.fmradio.service.recording;

import com.vlad805.fmradio.models.RtPlusInfo;

import java.util.Locale;
import java.util.Objects;

/** Immutable RDS state; empty strings represent unknown or cleared values. */
public final class RdsSnapshot {
    public static final RdsSnapshot EMPTY = new RdsSnapshot(0, "", "", "", RtPlusInfo.EMPTY);

    private final int frequencyKhz;
    private final String ps;
    private final String rt;
    private final String pi;
    private final RtPlusInfo rtPlus;

    public RdsSnapshot(
            final int frequencyKhz,
            final String ps,
            final String rt,
            final String pi,
            final RtPlusInfo rtPlus
    ) {
        this.frequencyKhz = frequencyKhz;
        this.ps = normalize(ps);
        this.rt = normalize(rt);
        this.pi = normalize(pi);
        this.rtPlus = rtPlus != null ? rtPlus : RtPlusInfo.EMPTY;
    }

    public RdsSnapshot(final int frequencyKhz, final String ps, final String rt, final String pi) {
        this(frequencyKhz, ps, rt, pi, RtPlusInfo.EMPTY);
    }

    public String toSubtitleText() {
        final String frequency = frequencyKhz > 0
                ? String.format(Locale.US, "%.2f", frequencyKhz / 1000.0).replaceAll("0$", "")
                : "—";

        final String programId = pi.matches("[0-9a-fA-F]{1,4}")
                ? String.format(Locale.US, "%04X", Integer.parseInt(pi, 16))
                : "0000";

        final StringBuilder builder = new StringBuilder();
        builder.append(frequency).append(" MHz [").append(programId).append("]\nPS: ").append(display(ps)).append("\nRT: ").append(display(rt));

        if (!rtPlus.isEmpty()) {
            builder.append("\nRT+: ").append(display(rtPlus.getFormattedText()));
        }

        return builder.toString();
    }

    private static String normalize(final String value) {
        return value == null ? "" : value.replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
    }

    private static String display(final String value) {
        // SRT players often interpret HTML-like formatting in cue text.
        return value.isEmpty()
                ? "—"
                : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @Override
    public boolean equals(final Object other) {
        if (!(other instanceof RdsSnapshot)) {
            return false;
        }
        final RdsSnapshot state = (RdsSnapshot) other;
        return (
                frequencyKhz == state.frequencyKhz &&
                ps.equals(state.ps) &&
                rt.equals(state.rt) &&
                pi.equals(state.pi) &&
                rtPlus.equals(state.rtPlus)
        );
    }

    @Override
    public int hashCode() {
        return Objects.hash(frequencyKhz, ps, rt, pi, rtPlus);
    }
}
