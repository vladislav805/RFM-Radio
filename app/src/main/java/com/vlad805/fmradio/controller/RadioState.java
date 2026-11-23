package com.vlad805.fmradio.controller;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import com.vlad805.fmradio.models.RtPlusInfo;
import com.vlad805.fmradio.service.fm.RadioStatePatch;

import java.util.Arrays;

/**
 * A class that stores the current state of the radio tuner, audio service and other
 * parameters.
 * vlad805 (c) 2021
 */
public final class RadioState implements Parcelable {
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
    public static final int SET_INITIAL = 1 << 31;

    public static final int INITIAL_SNAPSHOT_MASK =
            SET_INITIAL |
            SET_STATUS |
            SET_FREQUENCY |
            SET_PS |
            SET_RT |
            SET_PTY |
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
                    | SET_PI | SET_COUNTRY | SET_STEREO | SET_RT_PLUS | SET_AF;
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
        if (patch.hasRtPlus()) {
            final RtPlusInfo current = rtPlus != null ? rtPlus : RtPlusInfo.EMPTY;
            final String title = patch.getRtPlusTitle() != null ? patch.getRtPlusTitle() : current.getTitle();
            final String artist = patch.getRtPlusArtist() != null ? patch.getRtPlusArtist() : current.getArtist();
            final String album = patch.getRtPlusAlbum() != null ? patch.getRtPlusAlbum() : current.getAlbum();
            setRtPlus(title.isEmpty() && artist.isEmpty() && album.isEmpty()
                    ? null
                    : new RtPlusInfo(title, artist, album));
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

    RadioState(final Parcel in) {
        status = (TunerStatus) in.readValue(TunerStatus.class.getClassLoader());
        frequency = in.readInt();
        pi = in.readString();
        country = in.readString();
        pty = in.readInt();
        ps = in.readString();
        rt = in.readString();
        stereo = in.readInt() > 0;
        rmssi = in.readInt();
        sinr = in.readInt();
        recording = in.readInt() > 0;
        recordingStarted = in.readLong();
        forceSpeaker = in.readInt() > 0;
        final RtPlusInfo parsedRtPlus = in.readTypedObject(RtPlusInfo.CREATOR);
        rtPlus = parsedRtPlus != null ? parsedRtPlus : RtPlusInfo.EMPTY;
        final int[] parsedAf = in.createIntArray();
        af = parsedAf != null && parsedAf.length > 0 ? parsedAf : EMPTY_AF;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(final Parcel dest, int flags) {
        dest.writeValue(status);
        dest.writeInt(frequency);
        dest.writeString(pi);
        dest.writeString(country);
        dest.writeInt(pty);
        dest.writeString(ps);
        dest.writeString(rt);
        dest.writeInt(stereo ? 1 : 0);
        dest.writeInt(rmssi);
        dest.writeInt(sinr);
        dest.writeInt(recording ? 1 : 0);
        dest.writeLong(recordingStarted);
        dest.writeInt(forceSpeaker ? 1 : 0);
        dest.writeTypedObject(rtPlus, flags);
        dest.writeIntArray(af);
    }

    public static final Parcelable.Creator<RadioState> CREATOR = new Parcelable.Creator<RadioState>() {
        @Override
        public RadioState createFromParcel(Parcel in) {
            return new RadioState(in);
        }

        @Override
        public RadioState[] newArray(int size) {
            return new RadioState[size];
        }
    };

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
