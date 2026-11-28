package com.vlad805.fmradio.service.recording;

import com.vlad805.fmradio.controller.RadioState;
import com.vlad805.fmradio.models.RtPlusInfo;

import java.util.Locale;
import java.util.Objects;

/** Immutable RDS and tuner telemetry snapshot; empty strings / sentinels represent unknown values. */
public final class RdsSnapshot {
    public static final RdsSnapshot EMPTY = new RdsSnapshot(
            0,
            "",
            "",
            "",
            RtPlusInfo.EMPTY,
            "",
            0,
            false,
            false,
            RadioState.RMSSI_UNKNOWN,
            RadioState.SINR_UNKNOWN
    );

    private final int frequencyKhz;
    private final String ps;
    private final String rt;
    private final String pi;
    private final RtPlusInfo rtPlus;
    private final String country;
    private final int pty;
    private final boolean tp;
    private final boolean stereo;
    private final int rmssi;
    private final int sinr;

    public RdsSnapshot(
            final int frequencyKhz,
            final String ps,
            final String rt,
            final String pi,
            final RtPlusInfo rtPlus,
            final String country,
            final int pty,
            final boolean tp,
            final boolean stereo,
            final int rmssi,
            final int sinr
    ) {
        this.frequencyKhz = frequencyKhz;
        this.ps = normalize(ps);
        this.rt = normalize(rt);
        this.pi = normalize(pi);
        this.rtPlus = rtPlus != null ? rtPlus : RtPlusInfo.EMPTY;
        this.country = normalize(country).toUpperCase(Locale.US);
        this.pty = Math.max(0, pty);
        this.tp = tp;
        this.stereo = stereo;
        this.rmssi = rmssi;
        this.sinr = sinr;
    }

    public RdsSnapshot(
            final int frequencyKhz,
            final String ps,
            final String rt,
            final String pi,
            final RtPlusInfo rtPlus
    ) {
        this(
                frequencyKhz,
                ps,
                rt,
                pi,
                rtPlus,
                "",
                0,
                false,
                false,
                RadioState.RMSSI_UNKNOWN,
                RadioState.SINR_UNKNOWN
        );
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

    public String getCountry() {
        return country;
    }

    public int getPty() {
        return pty;
    }

    public boolean hasTrafficProgramme() {
        return tp;
    }

    public boolean isStereo() {
        return stereo;
    }

    public int getRmssi() {
        return rmssi;
    }

    public int getSinr() {
        return sinr;
    }

    /**
     * Returns true when all station/RDS/stereo fields match, ignoring RMSSI and SINR.
     * Used to throttle pure signal-level jitter while recording metadata changes immediately.
     */
    public boolean sameRdsCore(final RdsSnapshot other) {
        if (other == null) {
            return false;
        }
        return (
                frequencyKhz == other.frequencyKhz &&
                ps.equals(other.ps) &&
                rt.equals(other.rt) &&
                pi.equals(other.pi) &&
                rtPlus.equals(other.rtPlus) &&
                country.equals(other.country) &&
                pty == other.pty &&
                tp == other.tp &&
                stereo == other.stereo
        );
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
                sameRdsCore(state) &&
                rmssi == state.rmssi &&
                sinr == state.sinr
        );
    }

    @Override
    public int hashCode() {
        return Objects.hash(frequencyKhz, ps, rt, pi, rtPlus, country, pty, tp, stereo, rmssi, sinr);
    }
}
