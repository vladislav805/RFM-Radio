package com.vlad805.fmradio.service.recording;

import com.vlad805.fmradio.models.RtPlusInfo;

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

    public int getFrequencyKhz() {
        return frequencyKhz;
    }

    public String getPs() {
        return ps;
    }

    public String getRt() {
        return rt;
    }

    public String getPi() {
        return pi;
    }

    public RtPlusInfo getRtPlus() {
        return rtPlus;
    }

    private static String normalize(final String value) {
        return value == null ? "" : value.replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
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
