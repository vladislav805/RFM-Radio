package com.vlad805.fmradio.service.recording;

import java.util.Locale;
import java.util.Objects;

/** Immutable RDS state; empty strings represent unknown or cleared values. */
public final class RdsSnapshot {
    public static final RdsSnapshot EMPTY = new RdsSnapshot(0, "", "", "");

    private final int frequencyKhz;
    private final String ps;
    private final String rt;
    private final String pi;

    public RdsSnapshot(final int frequencyKhz, final String ps, final String rt, final String pi) {
        this.frequencyKhz = frequencyKhz;
        this.ps = normalize(ps);
        this.rt = normalize(rt);
        this.pi = normalize(pi);
    }

    public String toSubtitleText() {
        final String frequency = frequencyKhz > 0
                ? String.format(Locale.US, "%.2f", frequencyKhz / 1000.0).replaceAll("0$", "")
                : "—";

        final String programId = pi.matches("[0-9a-fA-F]{1,4}")
                ? String.format(Locale.US, "%04X", Integer.parseInt(pi, 16))
                : "0000";

        return frequency + " MHz [" + programId + "]"
                + "\nPS: " + display(ps) + "\nRT: " + display(rt);
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
                pi.equals(state.pi)
        );
    }

    @Override
    public int hashCode() {
        return Objects.hash(frequencyKhz, ps, rt, pi);
    }
}
