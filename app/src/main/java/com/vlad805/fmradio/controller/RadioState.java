package com.vlad805.fmradio.controller;

import androidx.annotation.NonNull;

import com.vlad805.fmradio.models.RtPlusInfo;
import com.vlad805.fmradio.service.fm.RadioStatePatch;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Single in-process state model for the radio tuner, audio routing, and recording status.
 * Owned and mutated exclusively on the main thread by {@link com.vlad805.fmradio.service.FMService}.
 * vlad805 (c) 2021
 */
public final class RadioState {
    public static final int SET_STATUS = 1;
    public static final int SET_FREQUENCY = 1 << 1;
    public static final int SET_PS = 1 << 2;
    public static final int SET_RT = 1 << 3;
    public static final int SET_PTY = 1 << 4;
    public static final int SET_PI = 1 << 5;
    public static final int SET_STEREO = 1 << 6;
    public static final int SET_RECORDING = 1 << 7;
    public static final int SET_SPEAKER = 1 << 8;
    public static final int SET_COUNTRY = 1 << 9;
    public static final int SET_RMSSI = 1 << 10;
    public static final int SET_RT_PLUS = 1 << 11;
    public static final int SET_SINR = 1 << 12;
    public static final int SET_AF = 1 << 13;
    public static final int SET_TP = 1 << 14;
    public static final int SET_INITIAL = 1 << 31;

    public static final int INITIAL_SNAPSHOT_MASK =
            SET_INITIAL |
            SET_STATUS |
            SET_FREQUENCY |
            SET_PS |
            SET_RT |
            SET_PTY |
            SET_TP |
            SET_PI |
            SET_COUNTRY |
            SET_STEREO |
            SET_RMSSI |
            SET_SINR |
            SET_RT_PLUS |
            SET_AF |
            SET_RECORDING |
            SET_SPEAKER;

    public static final int RMSSI_UNKNOWN = Integer.MIN_VALUE;
    public static final int SINR_UNKNOWN = Integer.MIN_VALUE;
    private static final int[] EMPTY_AF = new int[0];

    // State of tuner
    private TunerStatus status = TunerStatus.IDLE;

    // Frequency in kHz
    private int frequency = 0;

    // PI - Program ID (hex)
    private String pi;

    // ISO 3166-1 alpha-2 country code decoded from RDS ECC and PI
    private String country = "";

    // PTY - Program TYpe
    private int pty;

    private boolean tp;

    // PS - Program Service
    private String ps = "";

    // RT - Radio Text
    private String rt = "";

    // RT+ Metadata
    private RtPlusInfo rtPlus = RtPlusInfo.EMPTY;

    // AF - Alternative Frequencies in kHz
    private int[] af = EMPTY_AF;

    // Stereo - is stereo audio?
    private boolean stereo;

    // Signed receiver signal strength reported by the tuner.
    private int rmssi = RMSSI_UNKNOWN;

    // Signal-to-Interference-plus-Noise Ratio (dB) reported by tuner.
    private int sinr = SINR_UNKNOWN;

    // Is recording started
    private boolean recording = false;

    // Effective recording start timestamp, shifted back by included pre-roll
    private long recordingStarted = -1L;

    // Outputting sound from speakers
    private boolean forceSpeaker = false;

    public RadioState() {

    }

    public TunerStatus getStatus() {
        return status;
    }

    void setStatus(final TunerStatus status) {
        this.status = status;
    }

    public int getFrequency() {
        return frequency;
    }

    void setFrequency(final int frequency) {
        this.frequency = frequency;
    }

    public String getPi() {
        return pi;
    }

    void setPi(final String pi) {
        this.pi = pi;
    }

    public String getCountry() {
        return country;
    }

    void setCountry(final String country) {
        this.country = country;
    }

    public int getPty() {
        return pty;
    }

    void setPty(final int pty) {
        this.pty = pty;
    }

    public boolean hasTrafficProgramme() {
        return tp;
    }

    public String getPs() {
        return ps;
    }

    void setPs(final String ps) {
        this.ps = ps;
    }

    public String getRt() {
        return rt;
    }

    void setRt(final String rt) {
        this.rt = rt;
    }

    public RtPlusInfo getRtPlus() {
        return rtPlus;
    }

    void setRtPlus(final RtPlusInfo rtPlus) {
        this.rtPlus = rtPlus != null ? rtPlus : RtPlusInfo.EMPTY;
    }

    public int[] getAf() {
        return af;
    }

    void setAf(final int[] af) {
        this.af = af != null && af.length > 0 ? Arrays.copyOf(af, af.length) : EMPTY_AF;
    }

    void clear() {
        pi = "";
        country = "";
        pty = 0;
        tp = false;
        ps = "";
        rt = "";
        rtPlus = RtPlusInfo.EMPTY;
        af = EMPTY_AF;
        stereo = false;
        rmssi = RMSSI_UNKNOWN;
        sinr = SINR_UNKNOWN;
    }

    public int applyStatus(@NonNull final TunerStatus nextStatus) {
        status = nextStatus;
        if (nextStatus == TunerStatus.IDLE) {
            clear();
            return SET_STATUS | SET_RMSSI | SET_SINR | SET_PS | SET_RT | SET_PTY
                    | SET_PI | SET_COUNTRY | SET_STEREO | SET_RT_PLUS | SET_AF | SET_TP;
        }
        return SET_STATUS;
    }

    public int applyFrequency(final int frequencyKhz) {
        int mode = SET_FREQUENCY;
        if (frequencyKhz != frequency) {
            rmssi = RMSSI_UNKNOWN;
            sinr = SINR_UNKNOWN;
            af = EMPTY_AF;
            mode |= SET_RMSSI | SET_SINR | SET_AF;
        }
        frequency = frequencyKhz;
        return mode;
    }

    public int applyPatch(@NonNull final RadioStatePatch patch) {
        int mode = 0;

        if (patch.getFrequency() != null) {
            mode |= applyFrequency(patch.getFrequency());
        }
        if (patch.getPs() != null) {
            ps = patch.getPs();
            mode |= SET_PS;
        }
        if (patch.getRt() != null) {
            rt = patch.getRt();
            mode |= SET_RT;
        }
        if (patch.getPty() != null) {
            pty = patch.getPty();
            mode |= SET_PTY;
        }
        if (patch.getTp() != null) {
            tp = patch.getTp();
            mode |= SET_TP;
        }
        if (patch.getPi() != null) {
            pi = patch.getPi();
            mode |= SET_PI;
        }
        if (patch.getCountry() != null) {
            country = patch.getCountry();
            mode |= SET_COUNTRY;
        }
        if (patch.getAf() != null) {
            setAf(patch.getAf());
            mode |= SET_AF;
        }
        if (patch.getStereo() != null) {
            stereo = patch.getStereo();
            mode |= SET_STEREO;
        }
        if (patch.getRmssi() != null) {
            rmssi = patch.getRmssi();
            mode |= SET_RMSSI;
        }
        if (patch.getSinr() != null) {
            sinr = patch.getSinr();
            mode |= SET_SINR;
        }
        final Map<String, String> changes = patch.getRtPlusFields();
        if (changes != null) {
            if (changes.isEmpty()) {
                setRtPlus(null);
            } else {
                final Map<String, String> merged = new HashMap<>(getRtPlus().getFields());
                merged.putAll(changes);
                setRtPlus(new RtPlusInfo(merged));
            }
            mode |= SET_RT_PLUS;
        }

        return mode;
    }

    public int applyRecordingStarted(final int durationSeconds) {
        recording = true;
        recordingStarted = System.currentTimeMillis() - Math.max(0, durationSeconds) * 1000L;
        return SET_RECORDING;
    }

    public int applyRecordingProgress(final int durationSeconds) {
        recordingStarted = System.currentTimeMillis() - Math.max(0, durationSeconds) * 1000L;
        return SET_RECORDING;
    }

    public int applyRecordingStopped() {
        recording = false;
        recordingStarted = -1L;
        return SET_RECORDING;
    }

    public int applyForceSpeaker(final boolean forceSpeaker) {
        this.forceSpeaker = forceSpeaker;
        return SET_SPEAKER;
    }

    public boolean isStereo() {
        return stereo;
    }

    void setStereo(final boolean stereo) {
        this.stereo = stereo;
    }

    public int getRmssi() {
        return rmssi;
    }

    void setRmssi(final int rmssi) {
        this.rmssi = rmssi;
    }

    public int getSinr() {
        return sinr;
    }

    void setSinr(final int sinr) {
        this.sinr = sinr;
    }

    public boolean isRecording() {
        return recording;
    }

    void setRecording(final boolean recording) {
        this.recording = recording;
    }

    public long getRecordingDuration() {
        return recording ? (System.currentTimeMillis() - recordingStarted) / 1000 : -1L;
    }

    public long getRecordingStarted() {
        return recordingStarted;
    }

    /**
     * Sets the effective recording start, including any pre-roll duration.
     *
     * @param recordingStarted Start timestamp in milliseconds
     */
    void setRecordingStarted(final long recordingStarted) {
        this.recordingStarted = recordingStarted;
    }

    public boolean isForceSpeaker() {
        return forceSpeaker;
    }

    void setForceSpeaker(boolean forceSpeaker) {
        this.forceSpeaker = forceSpeaker;
    }

    @Override
    public String toString() {
        return "TunerState{" +
                status +
                ", kHz=" + frequency +
                ", pi=" + pi +
                ", pty=" + pty +
                // ", ps='" + ps + '\'' +
                // ", rt='" + rt + '\'' +
                ", rec_st=" + recording +
                ", rec_be=" + recordingStarted +
                '}';
    }
}
